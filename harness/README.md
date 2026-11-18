# Ubiq Pi harness

[Pi](https://www.npmjs.com/package/@earendil-works/pi-coding-agent) runs the agent loop; the phone keeps the tools and the guards. See [docs/AGENT_DESIGN.md](../docs/AGENT_DESIGN.md).

```
phone (Session.runHarnessTurn) ── POST /turn ──► server.mjs: one Pi session per Ubiq conversation
server.mjs ── MCP over HTTP (127.0.0.1:8787) ──► phone ToolHost ─► AgentTools.execute (ledger, receipts, secrets, policy)
```

## On the phone

The app runs this server itself (`agent/PiRuntime.kt`, docs/AGENT_DESIGN.md step 4):
- `scripts/build-node-runtime.sh` (run by the Gradle build) packs Termux's Node 24 for arm64 into the APK as `libnode_runtime.so` plus `libnr_*.so`, and this directory, with production `node_modules` pruned, as `assets/node/harness.zip`.
- The first Pi turn unpacks the zip into `files/pi/app-<version>/` and starts Node on 127.0.0.1:8789, with the same token. Conversations live in `files/pi/state/`, the log in `files/pi/harness.log`.
- Node stops after 10 idle minutes, when memory runs low, or when the engine is switched back to Built-in. It starts again in about a second.
- Build without it: `./gradlew assembleDebug -PnoPi=true`.

## Run it on the Mac instead (debug build)

```sh
scripts/pi-dev.sh --serial <adb serial>            # points the phone at this Mac and starts the server
scripts/pi-dev.sh --serial <adb serial> --phone    # back to Pi on the phone
scripts/pi-dev.sh --serial <adb serial> --builtin  # back to the built-in loop
```

- **Ports:** `adb reverse tcp:8788` lets the phone reach this server; `adb forward tcp:8787` lets this server reach the phone's tool host. The phone uses the Mac while Settings > Brain > Developer options has an address.
- **Auth:** both directions need the phone's harness token, which the script reads with `run-as`.
- **Settings:** model, effort, speed tier, API key and system prompt come from the phone with every turn. This server keeps only the conversations, in `.state/sessions/<id>` (Pi's session files), so a restart continues them.
- **Tools:**
  - The screen and app tools are declared to the model directly, named `mcp__phone__<tool>`.
  - Connectors, notifications, jobs and Space are deferred: the model finds them with `tool_search`.
  - Pi's own coding tools (read, write, edit, bash) are off.

## Protocol

All requests carry `Authorization: Bearer <token>`.

| Request | Body | Response |
|---|---|---|
| `GET /health` | | `{ok, sessions}` |
| `POST /turn` | `{session, text, images[], instructions, model, effort, serviceTier, apiKey, toolHost: {url, token}}` | NDJSON: `{type: "tool", name}`, `{type: "text", text}` (said alongside tool calls), then `{type: "end", text, error?}` |
| `POST /steer` | `{session, text}` | A message for the running turn, read after the current step |
| `POST /cancel` | `{session}` | Aborts the running turn |

The phone runs the completion check on the final text, against that turn's receipts, and sends one correction turn when they disagree.
