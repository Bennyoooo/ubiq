# Agent design

Where the agent is going next: the harness it runs in, the tools it has on a phone, how it picks a model for each call, how it talks to the user while it works, and how it stays within the phone's energy, network and memory. This is the target design. [ARCHITECTURE.md](ARCHITECTURE.md) describes what is built today, and a teardown of a competing agentic ROM (kept privately) describes the competitor we learn from.

Agreed on 2026-10-26. On 2026-10-27 Pi became the default engine, running on the phone (Section 10). Memory and the owner's personal context are designed in [MEMORY.md](MEMORY.md).

## 1. Decisions

1. **Adopt Pi as the harness instead of growing our own Kotlin loop.** Not dsh.
2. **The harness is only the brain.** Every effect goes through a Kotlin tool host that keeps the guards (effect ledger, receipts, secrets, completion check, policy), so the harness stays unprivileged and can be replaced.
3. **The agent climbs a tool ladder:** direct APIs, then code mode, then the GUI as text, then screenshots. No raw bash on the phone.
4. **A strong cloud model plans; a router picks the model for each call.** A cheaper, faster model handles small, bounded decisions. It is never the orchestrator.
5. **Conversation and execution are separate layers.** The user sees outcomes, questions and approvals. The step-by-step trace stays behind Watch and the activity log.
6. **Energy, network and memory are handled by harness policy**, not left to the model to reason about.

## 2. Where we are today

A hand-written Kotlin loop, `Session.runTurn` in `app/.../agent/Session.kt`, built on the OpenAI Responses API:

- **State:** kept on OpenAI's servers through `previous_response_id`. After 60 turns the chain restarts with a recap of the last 8 messages.
- **Each step:** one call to `gpt-6.1-sol` at medium effort. The tool calls it returns run one after another through `AgentTools.execute`, and the loop repeats until `done`, `ask_user` or a plain reply, capped at 40 steps.
- **Tools:** about 20 UI tools plus connectors, jobs and MCP, all sent with every request.
- **Around the loop:**
  - `navigate`: a cheap model (`gpt-6-luna`) picks one action per step from a menu built from the screen.
  - `read_list`: code scrolls a list and collects the rows.
  - The effect ledger and completion check.
  - Memory: picked per request, reviewed at the end of a task.
  - Secrets: codes and passwords reach the model only as tokens.
  - Messages sent during a task are fed in at the next step.
  - Up to 3 sessions run at once, each on its own hidden display.

**What it lacks compared with a harness:**
- conversation state kept on our side, with compaction;
- parallel tool calls;
- `tool_search` and per-app skills;
- sub-agents that fork the conversation;
- code mode.

## 3. How others do it

**Cyclon** runs two engines and switches between them with a signed remote policy (`agent-policy.mjs`). The shipped default is **Pi** (`pi/principal.json` is `{"engine": "pi"}`), vendored as one 7 MB file (`pi-coding-agent@1.1.0`) and run on Node on the phone. Their own hand-written Responses loop is now the fallback.

The Pi setup:
- **Tools:** the built-in tools are only `tool_search` and `codemode`. The `phone_ui_*` tools and `phone_navigate` are always loaded; about 886 Core tools are found through `tool_search`.
- **Skills:** 44 per-app `SKILL.md` guides. An app's guide is added to the result the first time the agent opens that app.
- **Sub-agents** (Codex semantics) fork the parent's conversation, so they reuse its prompt cache. At most 3 run at once, because Core allows 4 app sessions.
- **Sessions:** one Pi session per device, kept in files. It is compacted at 200k tokens with a focus prompt (keep decisions, answers and changes; drop screens and steps). The prompt cache is kept warm, and requests go on the `fast` tier.
- **Verifier:** off. Their note says it "changed no reply and cost seconds per turn".
- **Guards:** every tool call, from either engine, goes through Core's owner rules, approvals, effect guard and trace.

**Pi** is a small coding-agent harness: an agent core, a model-provider layer, extensions, skills, compaction and session files.

**DeepSeek Harness (dsh)** treats everything as a plugin, on the Cordis framework:
- the model adapters, tool registry, session log and the loop itself are all replaceable;
- tools run through before and after hooks;
- "model-visible means logged": the session log is the source of truth;
- it ships 30+ tools (bash, file system, LSP, terminals, sub-agents, jobs, schedules, web, and `run_code`, which lets the model write a program that calls the other tools);
- it is a developer preview with announced breaking changes.

### What a phone changes

The loop is the same everywhere: call the model, run its tools, repeat until it stops calling them. The environment around it is what differs.

| | Coding harness | Phone agent |
|---|---|---|
| Environment | Files and bash: text, mostly predictable | GUI apps: large, noisy screens that change on their own |
| Undo | git | None. A sent message stays sent: receipts, "unknown" states that block further writes, idempotency |
| Latency | Minutes are fine | Seconds, often by voice: low effort, fast tier, a cheap model per step |
| Tool count | 4 to 10 | Hundreds: `tool_search` and per-app skills |
| Trust | The repo is mostly trusted | Every screen is third-party text, so prompt injection is the default case. Typed values come only from the owner's words |
| Privilege | Runs as the user, with bash | The brain is unprivileged; effects go through a privileged broker (Core) |
| Parallelism | Limited by the CPU | Limited by displays and app instances |
| Lifecycle | Started by the user in a terminal | Process kills, tasks started by notifications and alarms, resuming after a restart |

## 4. The harness: Pi, as the brain only

### Why Pi and not dsh

| | Pi | dsh |
|---|---|---|
| Proven on a phone | Yes, Cyclon's default engine | No. Built around a laptop (web and desktop UI, bash, file system, OS process sandboxes) |
| Size | Small core, provider layer, extensions | 50+ package groups on Cordis |
| Stability | Can be pinned and vendored | Developer preview: "THERE WILL BE COMPATIBILITY-BREAKING CHANGES" |
| What we need | Compaction, session files, `tool_search`, skills, sub-agents, codemode, swappable providers | All present, plus much we would strip out |

**From dsh we take ideas, not code:**
- model routes that declare their capabilities (Section 6);
- before and after hooks on every tool call, where the guards and resource checks sit;
- the session log as the single source of truth.

### The split

```
Pi (Node, unprivileged)  ── JSON-RPC, MCP-shaped, over a local socket ──►  Kotlin tool host (in the app)
  loop, compaction, sessions,                                                AgentTools, Navigator, read_list,
  tool_search, skills,                                                       EffectLedger + receipts, Secrets,
  sub-agents, codemode                                                       completion check, policy
                                                                                     │
                                                                            Core / ControlService / Workspace
```

- **The guards cannot be bypassed** by a prompt or a harness bug: they live in the tool host, not in the loop.
- **Replacing the harness is cheap.** Moving to dsh, or back to a Kotlin loop, means writing another client for the same tool host. The hard parts (Core, the navigator, the effect ledger, the desks) are ours in every case.
- **Pi's coding defaults are removed** (`read`, `write`, `edit`, `bash`). Its built-in tools become `tool_search` and code mode, as on Cyclon.

### Costs, measured on Cyclon's Pixel 8a (2026-10-25)

- **Disk:** 45 MB for `libnode_runtime.so`, plus a 38 MB `runtime.zip` that unpacks to 176 MB.
- **Memory:** the Node process at 356 MB RSS, and the launcher app at 377 MB. That Node process runs their whole server (screen memory, egress ledger and more), so a lean Pi host should be smaller.
- **Process management:**
  - a foreground service keeps Node alive, with restarts after Android kills it;
  - the binary ships as a `.so`, because apps on Android 10+ cannot execute files from their data directory.
- **Maintenance:** a second language, and upstream changes to track. Pin a version and vendor it.

## 5. Tools on a phone

### The ladder

The agent uses the cheapest, most reliable rung that works. Cyclon's code credits routing to data sources before UIs with a rise from 35% to 63% success (MAS-Bench).

| Rung | Examples | Notes |
|---|---|---|
| **Direct APIs** | Core typed tools, intents and deep links, content providers (SMS, call log, contacts, calendar, media), AppFunctions, connectors and MCP, web search | Exact, fast, often undoable. Work with a text-only model |
| **Code mode** | A script that calls the direct tools | Combines many calls in one model step |
| **GUI as text** | UiAutomation or accessibility tree, `navigate`, `read_list` | Cheap and exact. Work with small, text-only models |
| **GUI as pixels** | Screenshots with lettered labels on the elements | Only for canvases, maps, unlabeled icons, image content. Needs a vision model |

### Fast paths (2026-11-12)

Some requests people expect to be instant. Three layers make them fast:

- **One-step tools** (`agent/QuickActions.kt`): `weather` (Open-Meteo, no app), `directions` (Google Maps `google.navigation:` or a Waze link, started on the main screen), `play_media` (the app's media session `playFromSearch` when it has one, else the `MEDIA_PLAY_FROM_SEARCH` intent, else a search deep link; playback is confirmed from the session). Which app: the one named; else the only one installed; else the one used last (usage stats, granted through Shizuku, or our own launches); with none used or none installed, ask.
- **Express lane** (`agent/Express.kt`): a short, plain message that passes a keyword gate goes to a small model (`gpt-6-luna`, strict schema) that returns one of weather / directions / play with its arguments, or none. Code runs the tool and answers from a template, without Pi or the main model. Anything unclear (none, the tool would ask, failed, playback unconfirmed) falls through to the normal turn, which hears what the quick try did. Express replies are spoken only when the message came by voice. Pi is prewarmed while the classifier runs and when the mic opens.
- **Replayable routes** (`agent/Routes.kt`): every screen action is recorded with the element it hit. When a turn ends well, the steps after the last launch of the app it ended in are kept, minus dead ends (no change, undone by back), stopping before anything with a receipt. Typed text from the request becomes a value slot. Matching routes are listed in the next request's message; `replay_route` repeats them in code and stops with the screen at the first mismatch. A route that breaks more often than it works is dropped.

Measured on the emulator (2026-11-12): "will it rain tomorrow" 1.8 s express against about 12 s through Pi; "take me to SFO airport" 2.2 s to navigation running; "battery usage in Settings" 46 s the first time, 13.9 s with the learned route. Phase timings are in the trace and logcat (`AgentOS/Time`).

Next: express replay of routes without the main model; app shortcuts (`LauncherApps`) and a hand-written deep link list for the top apps.

### Code and shell on the phone

- **A normal app** gets `/system/bin/sh` and toybox (grep, sed, ls). It can see only its own files, and there is no Python.
- **The privileged commands** (`am`, `pm`, `cmd`, `settings`, `content`, `input`, `dumpsys`) mostly fail from an app's uid. They work from the shell uid (Shizuku, adb) or from Core or root. That set is the phone's "Android script": `am start -d <deeplink>`, `settings put`, `content query --uri content://sms`, `cmd notification`, `svc wifi`.
- **A full Linux userland** is possible (Termux), but binaries must ship as native libraries, because apps can't execute files from their data directory.

**Decision:** no raw bash for the model. Code mode runs in an in-process sandbox (QuickJS or similar) whose only powers are our typed tools, so every call still passes the effect ledger and policy. An "Android script" tool, if we add one, is an allowlist of `cmd`, `settings` and `am` verbs that Core runs.

### Phone-only vs laptop-only

- **Only on a phone:**
  - telephony and SMS;
  - notification reply actions (reply without opening the app);
  - sensors, location and geofences;
  - camera, mic, NFC;
  - exact alarms;
  - biometric approval;
  - app widgets and the share sheet;
  - every app that has no API.
- **Only on a laptop or in the cloud:** compilers, package managers, git, Docker, long CPU jobs, large memory, real browser automation (CDP, Playwright), desktop apps.

**Where code runs is a provider**, as dsh treats its shell and file system: the same code tool can run on the phone or in a cloud sandbox. Generated code that needs laptop-class tools runs in the cloud, never on the phone, as Cyclon does with its cloud-sandboxed mini-apps.

## 6. Models

The app is cloud only: every model call goes over a model API (OpenAI's today; Pi's provider layer also speaks Anthropic, Google and OpenAI-compatible endpoints).

### The design

- **A strong model plans and talks.** It is never replaced mid-task by a weaker orchestrator.
- **A cheap, fast model takes constrained decisions:**
  - intent routing;
  - memory selection;
  - picking from a navigator menu (choosing from a list suits small models);
  - OTP and PII detection before anything is sent;
  - notification summaries.
- **Offline, the agent says so.** Direct tools that need no model still work; tasks that need a model wait as durable jobs until the phone is back online.
- **The router's inputs:** whether the step needs vision, how complex the task is, battery and network.

### Tool lists depend on the model's capabilities

As in dsh, each model route declares its input types:
- a text-only route gets placeholders where images would be, and the screenshot tool refuses on it;
- a text-only model that needs to see the screen uses on-device OCR (ML Kit), or calls a vision model as a tool to find an element.

Today `toolsFor` filters tools by engine; this generalizes it.

## 7. Conversation vs execution

A developer in Claude Code reviews diffs and commands, so showing the process is how they build trust. A consumer cannot review taps. Their trust has to come from three other places:

- **receipts:** the effect ledger and the completion check;
- **approvals** before irreversible actions;
- **an escape hatch:** Watch, Take over and Stop.

**Two layers:**
- **A conversation agent:** always responsive, with short, voice-friendly replies. It can read the execution trace to answer "what are you doing?" (Cyclon's `trace_search` and `trace_read`).
- **Execution workers:** background tasks on their own desks, forked from the conversation so they share its prompt cache. Today a running session blocks the chat and `lateInputs` only patches that.

**What the user sees:**
- a one-line status in an ongoing notification;
- interruptions only for missing details, approvals and handovers (login, payment);
- the result, checked against receipts;
- failures explained in plain terms.

Ambiguous intent is confirmed at the start, not halfway through. A plan preview appears only for long or risky tasks.

## 8. Energy, network and memory

Policy in the harness, not reasoning in the model. The model gets at most one line of context (for example "battery 12%, on cellular") so it does not start a 10-minute task at a bad moment.

| Signal (Android API) | What the harness does |
|---|---|
| Thermal headroom and status, battery level, charging, battery saver | Postpone deferrable work (digests) when hot, or low and unplugged |
| Network validated, metered, estimated bandwidth | **Flaky link:** stream, keep requests small, prefer the text tree to screenshots, and resume safely using op ids and the "unknown" state. **Metered:** no model downloads, no deferrable uploads |
| `onTrimMemory`, `MemoryInfo.lowMemory` | Fewer concurrent desks (each display plus app costs RAM). Stop the harness's Node when idle instead of keeping it resident |
| Doze, standby buckets, screen state | Schedule deferrable work (digests, memory review, indexing) with WorkManager constraints such as charging or unmetered. Use a foreground service only while a task runs |

**Energy per task** (from `dumpsys batterystats`) joins success rate and seconds in the eval.

## 9. The whole picture

```
Conversation agent (cloud, short replies, always responsive)
  ├─ Router: model per call ← online? data class? thermal/battery? needs vision?
  │    planner model │ cheap deciders (route, pick, redact) │ offline: direct tools only, jobs wait
  ├─ Execution workers (background desks, forks of the conversation for cache)
  │    tool ladder: direct API → code mode → GUI tree → screenshot
  │    where code runs: phone sandbox │ cloud sandbox
  └─ Guarded pipeline (Kotlin tool host + Core): policy → effect ledger → receipts → completion check
Resource governor: thermal / battery / network / memory → limits on routes, desks, jobs
```

## 10. Migration plan

1. **The tool host.** Expose `AgentTools` as MCP-shaped JSON-RPC over a local socket, with the effect ledger, secrets and completion check inside it. The current Kotlin loop becomes its first client. This step is useful whatever harness we use.
2. **Pi on the Mac, driving the phone** through an adb-forwarded socket. Cyclon keeps an `adb-host.ts` extension for this development loop. Iteration is fast, with no packaging yet.
3. **An A/B on `eval/` tasks:** the Kotlin loop against Pi, on success rate, seconds per task, cost and energy. Pi wins only if it matches or beats on success without being slower.
4. **Node on the phone:** Node as a `.so`, started by the app. The Kotlin loop stays behind a setting as a fallback until Pi has shipped for a while.
5. **Then the rest, in order of value:**
   - `tool_search` with per-app skills;
   - code mode;
   - the model router with cheap deciders;
   - the resource governor;
   - the conversation/execution split.

### Status (2026-10-27)

| Step | State |
|---|---|
| 1. Tool host | Done: `ToolHost` (MCP over HTTP, 127.0.0.1:8787, token, bound to the turn's `TaskTarget`) |
| 2. Pi driving the phone | Done: `harness/` (Pi 1.4.0), `scripts/pi-dev.sh`, Settings > Brain > Engine |
| 3. A/B | Done: the engines tie (below). Pi was chosen for what the A/B does not measure |
| 4. Node on the phone | Done: `PiRuntime`, `scripts/build-node-runtime.sh`. Pi is the default engine |
| 5. `tool_search`, code mode | Done in the harness (the phone's rarer tools are deferred; codemode in a QuickJS sandbox) |
| 5. Per-app skills | Mechanism done (`harness/apps/*.md`, attached the first time an app's screen shows). No guides yet: no A/B failure was app-specific |
| 5. Model router with cheap deciders | Not started: one model per turn |
| 5. Resource governor | Done: `DeviceConditions` (Section 8 policies) |
| 5. Conversation/execution split | Done: `start_task` / `task_status`, task cards, results reported back |
| Direct tools (Section 5 ladder) | `set_alarm`, `set_timer`, `open_link` |
| Bulk-delete guard | "Delete all ..." needs `ask_user` before any delete action, enforced in `Effects.track` |

**First A/B (2026-10-26, emulator, gpt-6.1-sol medium, 23 tasks, one run each):**

| Engine | Pass | Mean s | Median s |
|---|---|---|---|
| Built-in | 20/23 | 27.6 | 15.8 |
| Pi | 20/23 | 20.7 | 18.1 |

- **Same failures on both engines:**
  - **A false failure:** the eval's regex expects a straight apostrophe in "can't".
  - **A bulk delete without confirmation:** now guarded in code.
  - **A one-step notification lookup handed to `start_task`:** now fixed in the tool description.

  After the fixes, those tasks pass on both engines.
- **Speed:** Pi's mean is lower because the built-in loop had two long outliers (Wi-Fi 139 s, Chrome 88 s). Pi's median is slightly higher.
- **Clock tasks:** the direct tools made them pass in about 6 s on both engines; every model failed them before.
- **What the tie means:** both engines use the same model, tools, guards and navigator, so the A/B measured the model and the phone, not the loop. The gaps between single runs were model latency (one `set_alarm` call took 4.7 s on one engine and 48.8 s on the other).

### Why Pi became the default (2026-10-27)

The engines tie on these short tasks; Pi wins on what they do not exercise:
- **Any provider:** Pi speaks OpenAI, Anthropic, Google and OpenAI-compatible local servers through one interface. The built-in loop speaks only the OpenAI Responses API.
- **We own the transcript:** Pi keeps each conversation on the phone and compacts it. The built-in loop leaves the history on OpenAI's servers (`previous_response_id`), which ties it to one provider and to a chain that can expire.
- **Tool lists that scale:** rarer tools are found with `tool_search` instead of being sent on every turn. That matters once connectors and MCP servers add hundreds of tools (Cyclon has about 886).
- **Upstream:** code mode, extensions, skills and sub-agents come from a maintained project.

### Pi on the phone

- **Packaging** (`scripts/build-node-runtime.sh`, run by Gradle): Termux's Node 24 for arm64 and its libraries (OpenSSL, ICU, c-ares, SQLite, zlib, libc++), renamed to `libnode_runtime.so` and `libnr_*.so` with patchelf so the installer extracts them into `nativeLibraryDir`. The harness ships as `assets/node/harness.zip`: `harness/` with production `node_modules`, without source maps, types and docs.
- **Lifecycle** (`PiRuntime`): the first Pi turn unpacks the zip once per build and starts Node on 127.0.0.1:8789, with the tool host's token. It stops after 10 idle minutes, on low memory, or when the engine changes. No foreground service: a turn already keeps the app alive.
- **Routing:** Pi runs every turn while it is the engine; the built-in loop is the fallback.
- **Development:** `scripts/pi-dev.sh` still runs the harness on the Mac; Settings > Brain > Developer options holds its address.
- **Measured** (emulator, 2026-10-27):
  - **Tasks:** 22/23 on the same device tasks, mean 23.4 s, median 15.8 s. The one failure is the eval's apostrophe regex.
  - **Start:** the first unpack takes 0.7 s; Node is ready 0.8 s after it starts.
  - **Memory:** Node at 114 MB RSS (165 MB peak), only while Pi is in use.
  - **Size:** +51 MB to the APK. On disk, 109 MB of libraries plus about 100 MB of unpacked harness.

### Backlog: bring the built-in loop up to Pi

The built-in loop stays as the fallback. To keep it a real alternative:
1. **Own the transcript:** keep history on the phone with compaction, instead of `previous_response_id`.
2. **A provider layer:** Anthropic and OpenAI-compatible endpoints behind one interface.
3. **Deferred tools:** a `tool_search` tool, with only the common tools declared up front.
4. **Code mode:** batch tool calls in a sandbox (QuickJS), if evals show it helps.
5. **Per-app guides:** the same `harness/apps/*.md`, attached when an app's screen first shows.


## 11. Open questions

- **Size:** ICU data is 33 MB of the runtime. A Node build with small ICU, or esbuild bundling the harness, would shrink it.
- **Updates:** the harness ships inside the APK. Cyclon updates its JS over the air from a signed feed; we may want that once the harness changes faster than the app.
- **The ROM build:** the priv-app in the ROM needs its native libraries extracted too.
- **Code mode sandbox:** QuickJS in the app, or Node's own sandboxing once Node is on the phone anyway.
- **Cheap deciders:** whether a separate small-model call saves enough to be worth it while the user waits. A navigator pick has to come in around 0.2 s.
- **Where Core's typed tools are found:** whether they are reached through `tool_search` directly, or wrapped by the tool host.
