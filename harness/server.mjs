// The Pi harness for Ubiq (docs/AGENT_DESIGN.md): Pi runs the agent loop, the phone keeps the tools and the guards.
//
//   phone (Session) ── POST /turn ──►  this server: one Pi session per Ubiq conversation
//   this server     ── MCP over HTTP ──►  phone's ToolHost (tap, navigate, read_list, connectors, ...)
//
// The phone is the source of truth for settings: model, effort, tier, key, system prompt and tool host come with
// every turn. This server keeps only the conversations (Pi session files under STATE_DIR/sessions/<id>, so a restart
// continues where each one left off).
//
// Every request needs `Authorization: Bearer <token>`, the phone's harness token (scripts/pi-dev.sh reads it from the
// phone): other apps on the phone can reach 127.0.0.1 too.

import http from 'node:http';
import { mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getModel } from '@earendil-works/pi-ai/compat';
import {
  createAgentSession, createCodemodeExtension, createMcpExtension, createToolSearchExtension, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager, compact,
} from '@earendil-works/pi-coding-agent';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.AGENTOS_HARNESS_PORT ?? 8788);
const HOST = process.env.AGENTOS_HARNESS_HOST ?? '127.0.0.1';
const TOKEN = process.env.AGENTOS_HARNESS_TOKEN;
const STATE_DIR = process.env.AGENTOS_HARNESS_STATE ?? path.join(HERE, '.state');
if (!TOKEN) { console.error('AGENTOS_HARNESS_TOKEN is not set (scripts/pi-dev.sh sets it from the phone).'); process.exit(1); }

// The phone's tools Pi declares up front; the rest (connectors, jobs, notifications, mini apps) are found with tool_search.
const DIRECT = ['get_screen', 'screenshot', 'tap', 'long_press', 'tap_xy', 'navigate', 'read_list', 'reconcile_effect', 'type_text',
  'scroll', 'swipe', 'press', 'open_app', 'wait', 'ask_user', 'start_task', 'task_status', 'pass_to_task', 'update_user', 'set_alarm', 'set_timer', 'phone_modes', 'open_link', 'request_ride', 'unreplied_messages', 'show_user', 'recall'];
const THINKING = { minimal: 'minimal', low: 'low', medium: 'medium', high: 'high', xhigh: 'high' };

const NAMING = `
## Tool names
The phone's tools reach you as mcp__phone__<name>: mcp__phone__navigate is navigate, mcp__phone__tap is tap, and so on. The screen, app and ask_user tools are always loaded. Connectors, notifications, jobs and Space tools are found with tool_search (ask for a few results by what you need, e.g. "calendar events", "add job").
## Code mode
\`codemode\` runs a short JavaScript script that calls the phone's tools (tools.mcp__phone__<name>({...})) and returns only what you print. Use it to combine several reads in one step (search contacts, then calendar, then filter) or to cut a long result down. It is not for operating screens step by step: tap, type and navigate yourself. Every call in a script passes the same receipts and approvals as a direct call.
`;

/**
 * Per-app guides (apps/*.md, front matter \`package: <pkg>\`): the first time a tool result in a session shows that app's
 * screen (its "app=<pkg>" line), the guide is added to that result, so no step goes into looking it up (as Cyclon does).
 */
const GUIDES = (() => {
  const dir = path.join(HERE, 'apps');
  const out = new Map();
  let files = [];
  try { files = readdirSync(dir).filter(f => f.endsWith('.md')); } catch { return out; }
  for (const f of files) {
    const text = readFileSync(path.join(dir, f), 'utf8');
    const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(text);
    const pkgs = m ? (/^package:\s*(.+)$/m.exec(m[1])?.[1] ?? '').split(',').map(p => p.trim()).filter(Boolean) : [];
    for (const p of pkgs) out.set(p, m[2].trim());
  }
  return out;
})();

/** Ubiq session id → { session, cfg, busy } */
const sessions = new Map();

function log(...a) { console.log(new Date().toISOString().slice(11, 19), ...a); }

/** The phone's tool host as this session's MCP server; the session header binds calls to that conversation's task. */
/** What a phone assistant's summary keeps: the owner and their requests, not the screens it went through. */
const COMPACT_FOCUS = 'This is the ongoing conversation between a phone assistant and the phone\'s owner, not a coding session. Keep: what the owner asked for ' +
  'and said about themselves, their decisions and answers to your questions, people, places, dates and times mentioned, promises and follow-ups still open, ' +
  'what you did for them and its outcome (sent, booked, set, failed), and background tasks still running. Drop: screen contents, element lists, ' +
  'tool call details and the steps taken to get there. Leave out codes and passwords.';

function phoneExtension(id, cfg) {
  return {
    name: 'agentos-phone',
    factory: (pi) => {
      pi.registerMcpServer('phone', {
        url: cfg.toolHost.url,
        headers: { Authorization: `Bearer ${cfg.toolHost.token}`, 'X-AgentOS-Session': id },
        exposure: 'deferred',
        toolExposure: Object.fromEntries(DIRECT.map(n => [n, 'direct'])),
        description: "The user's Android phone: its screen and apps, notifications, connectors (calendar, contacts, email), jobs and Space.",
        timeout: 3600, // ask_user waits for the user; navigate can take two minutes
      });
      // The phone's prompt replaces Pi's coding prompt, for every run (it changes when apps, memories or connectors do).
      pi.on('before_agent_start', () => ({ systemPrompt: cfg.instructions + NAMING }));
      const guided = new Set();
      pi.on('tool_result', (event) => {
        if (event.isError || !GUIDES.size) return undefined;
        const text = (event.content ?? []).filter(c => c.type === 'text').map(c => c.text).join('\n');
        const pkg = /\bapp=([\w.]+)/.exec(text)?.[1];
        if (!pkg || guided.has(pkg) || !GUIDES.has(pkg)) return undefined;
        guided.add(pkg);
        return { content: [...event.content, { type: 'text', text: `\n\nGuide for this app (${pkg}):\n${GUIDES.get(pkg)}` }] };
      });
      // A long conversation is summarised for a phone assistant, not a coding session (docs/MEMORY.md 3.3).
      pi.on('session_before_compact', async (event, ctx) => {
        const model = ctx.model ?? getModel('openai', cfg.model);
        if (!model || !cfg.apiKey) return undefined;
        try {
          return { compaction: await compact(event.preparation, model, cfg.apiKey, undefined, COMPACT_FOCUS, event.signal) };
        } catch (e) {
          if (event.signal?.aborted) throw e;
          console.error('phone compaction failed, using the default', e);
          return undefined;
        }
      });
      // The phone's speed tier (about half a second less per step on fast).
      pi.on('before_provider_request', (event) => (cfg.serviceTier && event.payload && !event.payload.service_tier)
        ? { ...event.payload, service_tier: cfg.serviceTier } : undefined);
    },
  };
}

const sessionDir = (id) => path.join(STATE_DIR, 'sessions', id.replace(/[^\w-]/g, '_'));

/** The user deleted a message on the phone: drop this conversation's Pi session so the model forgets it. */
function forgetSession(id) {
  const cur = sessions.get(id);
  if (cur?.busy) return false;
  if (cur) { try { cur.session.dispose(); } catch {} sessions.delete(id); }
  rmSync(sessionDir(id), { recursive: true, force: true });
  return true;
}

async function openSession(id, cfg) {
  const dir = sessionDir(id);
  mkdirSync(dir, { recursive: true });
  const modelRuntime = await ModelRuntime.create({ authPath: path.join(STATE_DIR, 'auth.json'), modelsPath: path.join(STATE_DIR, 'models.json') });
  if (cfg.apiKey) await modelRuntime.setRuntimeApiKey('openai', cfg.apiKey);
  const model = getModel('openai', cfg.model);
  if (!model) throw new Error(`Pi does not know the model openai/${cfg.model}`);
  const settingsManager = SettingsManager.inMemory({ compaction: { enabled: true }, retry: { enabled: true, maxRetries: 2 }, defaultTools: ['tool_search', 'codemode'] });
  const resourceLoader = new DefaultResourceLoader({
    cwd: dir, agentDir: STATE_DIR, settingsManager,
    noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true,
    systemPrompt: cfg.instructions + NAMING, appendSystemPrompt: [],
    // codemode scripts run in a QuickJS sandbox (no Node APIs, files or network): only the phone's tools reach out.
    extensionFactories: [createToolSearchExtension(), createCodemodeExtension({ mode: 'on' }), createMcpExtension(), phoneExtension(id, cfg)],
  });
  await resourceLoader.reload();
  const { session } = await createAgentSession({
    cwd: dir, agentDir: STATE_DIR, modelRuntime, model, thinkingLevel: THINKING[cfg.effort] ?? 'medium',
    resourceLoader, settingsManager,
    noTools: 'builtin', // no read/write/edit/bash: the phone's tools are the only hands
    sessionManager: SessionManager.continueRecent(dir, dir),
  });
  await session.bindExtensions({}); // session_start: connects the phone's MCP server
  return session;
}

/** The Pi session for [id], made (or continued from its files) on first use. Model or key changes reopen it. */
async function sessionFor(id, cfg) {
  const cur = sessions.get(id);
  if (cur && cur.cfg.model === cfg.model && cur.cfg.apiKey === cfg.apiKey && cur.cfg.toolHost.url === cfg.toolHost.url) {
    Object.assign(cur.cfg, cfg); // prompt, effort and tier apply from the next run on
    if (cur.session.thinkingLevel !== (THINKING[cfg.effort] ?? 'medium')) cur.session.setThinkingLevel?.(THINKING[cfg.effort] ?? 'medium');
    return cur;
  }
  if (cur) { try { cur.session.dispose(); } catch {} }
  const entry = { cfg: { ...cfg }, busy: false };
  entry.session = await openSession(id, entry.cfg);
  sessions.set(id, entry);
  return entry;
}

function lastAssistantText(msg) {
  if (!msg || msg.role !== 'assistant') return '';
  return (msg.content ?? []).filter(c => c.type === 'text').map(c => c.text).join('').trim();
}

async function runTurn(body, res) {
  const id = String(body.session ?? '');
  const send = (ev) => { if (!res.writableEnded) res.write(JSON.stringify(ev) + '\n'); };
  res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Cache-Control': 'no-cache' });
  if (body.fresh && !forgetSession(id)) { send({ type: 'end', text: '', error: 'a turn is already running in this conversation' }); return res.end(); }
  let entry;
  try {
    entry = await sessionFor(id, body);
  } catch (e) {
    send({ type: 'end', text: '', error: `could not start the session: ${e.message}` }); return res.end();
  }
  if (entry.busy) { send({ type: 'end', text: '', error: 'a turn is already running in this conversation' }); return res.end(); }
  entry.busy = true;
  const t0 = Date.now();
  let steps = 0, tools = 0;
  let final = '';
  let error;
  const unsubscribe = entry.session.subscribe((event) => {
    if (event.type === 'tool_execution_start') { tools++; send({ type: 'tool', name: event.toolName }); }
    if (event.type === 'message_end' && event.message?.role === 'assistant') {
      steps++;
      const text = lastAssistantText(event.message);
      const calls = (event.message.content ?? []).some(c => c.type === 'toolCall');
      if (calls) { if (text) send({ type: 'text', text }); }
      else if (text) final = text;
      if (event.message.stopReason === 'error' && event.message.errorMessage) error = event.message.errorMessage;
    }
  });
  res.on('close', () => { if (entry.busy && !res.writableFinished) entry.session.abort().catch(() => {}); });
  try {
    const images = (body.images ?? []).map(i => ({ type: 'image', data: i.data, mimeType: i.mimeType }));
    await entry.session.prompt(String(body.text ?? ''), { expandPromptTemplates: false, ...(images.length ? { images } : {}) });
    await entry.session.waitForIdle?.();
  } catch (e) {
    error = e.message ?? String(e);
  } finally {
    unsubscribe();
    entry.busy = false;
  }
  log(`${id}: steps=${steps} tools=${tools} secs=${((Date.now() - t0) / 1000).toFixed(1)}${error ? ` error=${error}` : ''}`);
  send({ type: 'end', text: final, ...(error && !final ? { error } : {}) });
  res.end();
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.setEncoding('utf8');
    req.on('data', c => { data += c; if (data.length > 32 * 1024 * 1024) reject(new Error('too large')); });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.headers.authorization !== `Bearer ${TOKEN}`) { res.writeHead(401).end(); return; }
  try {
    if (req.method === 'GET' && req.url === '/health') { res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ ok: true, sessions: sessions.size })); return; }
    if (req.method !== 'POST') { res.writeHead(405).end(); return; }
    const body = await readBody(req);
    const entry = sessions.get(String(body.session ?? ''));
    switch (req.url) {
      case '/turn': return await runTurn(body, res);
      case '/steer':
        if (entry?.busy) await entry.session.steer(String(body.text ?? ''));
        res.writeHead(200).end(); return;
      case '/cancel':
        if (entry?.busy) await entry.session.abort();
        res.writeHead(200).end(); return;
      default: res.writeHead(404).end();
    }
  } catch (e) {
    log('request failed', e);
    if (!res.headersSent) res.writeHead(500).end(String(e.message ?? e)); else res.end();
  }
});
server.listen(PORT, HOST, () => log(`Ubiq Pi harness on http://${HOST}:${PORT}, state in ${STATE_DIR}`));
