# Architecture

## The loop

```
user text/voice ──► AgentRuntime.send()
                      │  state lives server-side: OpenAI Responses API previous_response_id;
                      │  each call sends only the new input items (recap + fresh chain
                      │  after 60 turns)
                      ▼
            POST /v1/responses {model, instructions, tools, input, previous_response_id}
                      │
          ┌───────────┴───────────┐
       text only              function_call items
     (chat reply)                  │  AgentTools.execute()
       speak + show                │   ├─ Effects.track: op id + receipt, writes blocked while one is unknown
                                   │   ├─ control tools ─► ControlService (a11y)
                                   │   ├─ navigate ─► Navigator (gpt-6-luna picks from a menu) ─► control tools
                                   │   ├─ read_list ─► ListReader (scrolls in code)
                                   │   ├─ ask_user ─► pause: CompletableDeferred, next user message resumes
                                   │   └─ done ─► CompletionCheck vs. receipts (one correction) ─► summary, TTS
                                   ▼
            function_call_output (+ "Screen now:" text, secrets as tokens) and an input_image message for screenshots, loop
```

Every action tool returns the post-action screen in its result, so one API round-trip per action. `get_screen` exists for when the model wants a fresh read without acting.

The main model states intent; code and a cheap model do the mechanical work:

- **`navigate(app, goal, values)`** (`agent/Navigator`): code turns the current `ScreenSnapshot` into a menu of concrete actions (`t12` tap [12] "Display", `y5v0` type value 0 into [5], `d7` scroll [7] down, back, enter, wait, done, stuck). `gpt-6-luna` (low effort, `fast` tier, strict JSON schema whose `action` is an enum of the menu's ids) picks one per step and code runs it through `AgentTools.execute`, so every guard and the effect ledger apply. Text is typed only from `values`, verbatim. Up to 20 steps / 120 s. When it says stuck, or repeats an action that already did nothing twice on that screen, it asks the main model one short question (a consult: pick an action, give one line of guidance, or stop), at most twice. The main model gets the step log and the final screen and keeps all low-level tools as a fallback. Settings can turn it off (`Prefs.navigator`).
- **`read_list`** (`agent/ListReader`): code scrolls a list (`ACTION_SCROLL_FORWARD`) and collects its rows (each child of the innermost container, texts joined), deduplicated, until the end, N rows, or two scrolls with nothing new.

## Engines: built-in loop or Pi harness

Settings > Brain > Engine picks who runs the loop:
- **Built-in** (default): the Kotlin loop above.
- **Pi harness:** [Pi](../harness/README.md) runs the loop in Node, and the phone keeps the tools and guards (see [AGENT_DESIGN.md](AGENT_DESIGN.md)). Node runs on the phone as a child process of the app (`PiRuntime`): it is packed into the APK as native libraries, started with the first Pi turn and stopped when idle or low on memory.

How a Pi turn works:
- `Session.runHarnessTurn` posts the turn to the harness (`Harness.kt`).
- The harness calls the phone's tools back through `ToolHost`, an MCP server on 127.0.0.1:8787 that is bound to the turn's `TaskTarget`. Every call still passes through `AgentTools.execute`: effect ledger, payment and job guards, secret redaction.
- The final reply gets the same `CompletionCheck`, with one correction.
- `ask_user`, Take over, Stop and messages sent while a task runs behave as in the built-in loop. Stop also cancels a tool already running on the phone.
- Development setup: `scripts/pi-dev.sh` points the phone at a harness on the Mac instead (Settings > Brain > Developer options holds the address).

## Effects, receipts and the completion check

`agent/Effects` keeps a ledger per turn (on `TaskTarget`). Every effectful call gets an op id and an entry `{kind, app, where, payload, key = hash, state, evidence}` before it runs: a non-search `type_text` with submit; a tap on a Send / Post / Reply (with a draft in the composer), Pay / Buy / Place order, Delete / Remove, or Confirm-like control; and connector writes (`gmail_send`, calendar add/delete, jobs, Space). Its state comes from evidence, never from the model:

- **done**: the post-action screen shows it (composer cleared and the sent text visible outside it; the tapped control gone and the screen changed) or the connector answered;
- **failed**: the draft is still in the composer, the app shows an error ("Not sent", "Try again"), or the tap did not register;
- **unknown**: no evidence either way (looked at twice, 1.5 s apart). While any op is unknown, further writes are refused until the model looks at the screen and calls `reconcile_effect(op_id, done|failed, evidence)`; "done" is accepted only if the quoted evidence is on the current screen.

A send or pay whose key is already done is refused as a repeat unless the user answered an `ask_user` in between. Each result carries its receipt line.

When the model finishes (`done`, or a plain text reply), `CompletionCheck` compares the summary with the ledger, with no model involved: a claim ("sent", "deleted", "paid", "booked") needs a done op of that family, but only if the user's words this turn asked for that kind of thing and the sentence is not negated, hedged, future or a question; a claim of change ("I've set a timer") needs any write or phone action; a count ("here are your 10 emails") may not exceed what this turn's reads returned; and no op may be left unknown. On a mismatch the model gets the facts as the `done` call's error, once; its next summary goes through.

## Secrets

`agent/Secrets` (one per session, memory only) replaces one-time codes (4-8 digits near "code", "OTP", "verification", ...) and the contents of password fields (`UiNode.password`) with stable tokens such as `⟦otp:1⟧` in every tool result before it reaches the model; screenshots of such screens are withheld. `type_text` resolves tokens on the phone, so the agent can enter a code it was texted without seeing it. Memories may not contain tokens.

## Memory

- **Selection**: with more than 12 memories, `gpt-6-luna` picks the ones relevant to the request (strict enum of memory ids, 4 s timeout, all of them on failure); the prompt notes how many were left out.
- **Review**: when a turn ends well and either the user's words contain owner facts ("my", "I prefer", ...) or the route was hard (a consult, two actions with no effect, two backs, or 10+ phone actions), one main-model call forks the finished chain (`previous_response_id`, not stored) with only `remember` and, if hard, `save_route` (stored as a memory titled `Route: <app>`, e.g. "In Settings: Settings > Display > Brightness level").
- **Provenance**: every memory write (`space_save` kind=memory, `space_update` of a memory, the review's tools) is refused when its new distinctive tokens (4+ digit numbers, emails, web addresses) appear in this session's tool or screen text but not in the user's own messages.
- **Personal context** ([MEMORY.md](MEMORY.md)): `memory/Lifelog` is a timeline in `files/lifelog.db` (SQLite, FTS4), kept 90 days. `memory/Capture` records the main screen's text from the accessibility events (after it settles for 1.2 s, at most every 2 s, at the latest 4 s after a change), only the lines new for that app; never password fields, edited text, lines with codes, Ubiq itself, System UI or password-manager and authenticator apps. `NotificationWatcher` adds every notification; `memory/Places` turns location fixes (balanced, every 10 minutes, plus passive) into visits of 10 minutes or more, named by reverse geocoding. `recall` searches the timeline and every conversation's messages; its results are observed: they are noted on the session (`Session.observed`), and `Effects` blocks a write (typing, email, event, job, Space) whose distinctive values came from them and not from the owner until `ask_user` gets an OK. Settings > Personal context pauses capture (`Prefs.personalContext`) and deletes it all. Pi compacts a long conversation with a phone-specific focus (`session_before_compact` in `harness/server.mjs`).

## Running-task notifications

`agent/TaskNotifications` posts one ongoing notification per active session ("Working on: <title>", step, Stop; "Needs your answer" with Answer, which opens that chat), requested as a promoted ongoing notification on Android 16, removed when the session goes idle.

## Model settings

Main model, reasoning effort (default `medium`) and service tier (`Prefs.serviceTier`, default = account default; `fast` and `flex` are accepted) are settings; the navigator and memory selection always use `gpt-6-luna` at low effort on `fast`. Each turn logs its numbers (`AgentOS/Stats`: steps, tool calls, navigator steps, consults, seconds, model/effort/tier, receipts).

## Seeing the screen

`ScreenReader.capture()` walks all interactive windows (dialogs first), skips the keyboard window, keeps nodes that have text, a description, or are clickable/editable/scrollable/checkable, and numbers them. Output is one line per element:

```
[7] LinearLayout (clickable) @(540,893)
[8] TextView "Network & internet" id=title @(407,867)
```

Capped at 160 nodes. The model refers to `[n]`; coordinates are there for `tap_xy` fallbacks. Screenshots are downscaled to ≤1000px PNG and sent as image blocks only when the model calls `screenshot`.

## Acting

`ControlService` prefers semantic actions (`ACTION_CLICK` on the node or a clickable ancestor, `ACTION_SET_TEXT`, `ACTION_SCROLL_*`, `ACTION_IME_ENTER`) and falls back to gestures (`dispatchGesture` taps/swipes). Inside web content (mini apps, Chrome, hybrid apps) the order is reversed: Chromium acknowledges `ACTION_CLICK` without delivering the click to the page, so nodes under a `WebView` are tapped with a real gesture first. `type_text` on a non-editable "search bar" shell taps it first, waits for the UI to settle, then sets text on the newly focused field.

## Direct tools

The first rung of the tool ladder ([AGENT_DESIGN.md](AGENT_DESIGN.md), section 5): tools that change the phone without operating any screens.
- `set_alarm` and `set_timer` use the Clock app's public intents with `SKIP_UI`. An alarm is confirmed against `AlarmManager.nextAlarmClock` when it is the next one to ring.
- `phone_modes` switches Do Not Disturb modes through `NotificationManager` (the notification listener carries policy access). Ubiq creates its own `AutomaticZenRule`s, which show in quick settings and Settings > Modes as managed by Ubiq and can be started by hand. On the Ubiq ROM, Core's `modes.list` / `modes.set_state` / `modes.update` reach every other mode too (the user's Do Not Disturb, Bedtime, Driving, modes they made): Core holds the platform signature permissions `MANAGE_NOTIFICATIONS` (other packages' rules) and `STATUS_BAR_SERVICE` (calls count as the quick settings tile: overrides a schedule, turning Do Not Disturb off leaves other modes on, and `setInterruptionFilter` reaches the user's own DND instead of an app's implicit mode). `until` is Android's countdown for Do Not Disturb and an exact alarm for other modes. Wire format: `coreapi` `ZenModes`.
- `open_link` opens a URL or deep link in the app that handles it, on the task's desk, so a search, a map query or a link from a message lands directly on the right page.

These are receipted like connector writes.

## Background workspace

By default the agent does not touch the screen you are looking at. It works on a second, hidden display:

1. Something privileged creates the display and starts activities on it, which normal apps cannot. On the ROM that is **Ubiq Core** (below), in its own process. On a stock phone it is `WorkspaceShell`, run as the ADB shell user through [Shizuku](https://shizuku.rikka.app) (a Binder "user service"). Both implement the same `IWorkspaceShell` AIDL, and `Workspace.shell()` prefers Core when it is installed. Either may create a *trusted* virtual display (`ADD_TRUSTED_DISPLAY`). The display mirrors the real screen's size and density so coordinates match 1:1.
2. The display renders into an `ImageReader` owned by the Ubiq process. The latest frame is the agent's screenshot (no rate limit) and feeds the live **Watch** view in the chat.
3. Shell launches `WorkspaceHomeActivity` (the workspace "floor") once. After that Ubiq has a window on the display, so it may start any app there with `ActivityOptions.setLaunchDisplayId` - no shell round-trip per launch.
4. `ControlService` drives that display through the normal accessibility APIs: `windowsOnAllDisplays`, node actions, `GestureDescription.setDisplayId`. Back/Enter are injected by the shell helper with the display id set; "home" brings the floor to the front.
5. **Power and the lock screen.** The display is owned by a *virtual device* (`VirtualDeviceManager`, the API behind app streaming to Chromebooks and cars). The shell user may create one once it has a companion-device association, which the helper makes with `cmd companiondevice associate … --self-managed` (no UI). A virtual-device display gets its own display group and power group and is marked always-unlocked, so the lock screen never covers it, the power button does not turn it off (it only sleeps the phone's group), and apps on it keep running while the phone is dark and locked. The helper holds a wake lock bound to that display while a task runs or someone is watching; otherwise the group dozes after the screen timeout and the next task wakes it (`ACQUIRE_CAUSES_WAKEUP`). Tasks on the virtual device are hidden from Recents. If the virtual-device path is unavailable the helper falls back to a plain trusted virtual display with `OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED`; Android 17 on the Pixel then still puts it in the phone's group, so the app holds a dim wake lock during tasks and reports "phone is asleep" to the model if the screen goes off.
6. **Take over.** The Watch view is also a remote control: *Take over* forwards your touches (as real `MotionEvent`s with the workspace's display id, injected by the shell helper), Back/Home/Enter and typed text to the workspace, at a higher frame rate, while the agent pauses before its next model call or tool call. *Hand back* resumes it with a note that the screen may have changed.

Caveats that come from Android, not from Ubiq:
- Android keeps one task per `singleTask` app. The first `open_app` on the workspace asks for a fresh task; for apps that refuse, the user's existing task moves to the workspace. A plain launch (notification tap, system launcher) of an app whose task sits on the workspace would raise it *there*, invisibly, so: every launch Ubiq makes for the user (Apps tab, mini app tiles) names the real screen explicitly, which pulls the task back; the agent notices the app left the workspace and continues on the visible screen; and the workspace closes the agent's apps after 3 idle minutes, on a new conversation, or via Settings, so nothing stays parked there. If the user has the needed app in front when a task starts, the task runs on the visible screen from the start.
- Chat attachments (photos, files) are shown to the model inline (images, text, PDFs) and copied to Pictures/Ubiq or Download/Ubiq so the agent can pick them in other apps' upload flows.
- No notification shade or recents on the workspace; biometric prompts and permission dialogs appear on the real screen.
- Without Core or Shizuku (not installed, not running, or permission not granted) everything runs on the visible screen as before. The toggle lives in Ubiq Settings.

## Ubiq Core (the ROM's privileged layer)

The agent app (`ai.ubiq.app`) holds no special privileges of its own on the ROM. Everything privileged sits in **Ubiq Core** (`com.agentos.core`, module `android/core`): a small app signed with the platform key, a priv-app on `system_ext`. The app talks to it through one AIDL interface, `ICore` (module `android/coreapi`, shared by both):

```
ai.ubiq.app (agent, UI; debug/release key)            com.agentos.core (platform key, priv-app)
  CoreClient ──bind com.agentos.core.BIND──────────►  CoreService ─ caller check (uid = ai.ubiq.app
     │  call(json) → reply json + receipt                            AND holds CONTROL)
     │  getWorkspace() → IWorkspaceShell ─────────────► WorkspaceImpl (displays, input, activity starts)
     │                                                  Tools (assets/tools.json catalog) ─ Journal (opId)
     └─ AgentTools: gate() before every phone tool ───► Policy: floors > Stop > app rules
                                                        Provisioner (first boot) ─ UiEngine (UiAutomation)
```

- **Caller check.** Core defines `com.agentos.core.permission.CONTROL` (`signature|privileged|knownSigner`; the known signer is this repo's debug key, which covers an adb-updated Ubiq whose `/system` copy predates the permission). Every call checks that the calling uid is `ai.ubiq.app` and holds it; `IWorkspaceShell` checks it on every transaction.
- **Tools.** `call()` takes `{opId, tool, args, origin: owner|agent|job|trigger, task}` and returns `{ok, state: done|failed|unknown|refused|approval_required, result, receipt: {opId, tool, argsHash, at, evidence}}`. The catalog is a JSON asset where every tool declares its `effect` (read, write, destructive, external) and `dataClass`: `policy.get/check/set_app_rule/approval_status`, `owner.stop/resume`, `settings.get/put`, `roles.get`, `apps.list`, `provision.status/rerun`, `journal.recent`, `ui.observe/act`, `tools.list`. Calls with an effect are journaled by `opId` (on disk, newest 1000): a repeated opId returns the first reply, and one that never finished (Core died mid-effect) comes back `unknown`.
- **Workspace.** The Shizuku helper's logic, ported, with Core's own identity: no faked `com.android.shell` attribution and no shell commands. Activities start through `ActivityOptions.setLaunchDisplayId` (`INTERNAL_SYSTEM_WINDOW`, `START_ACTIVITIES_FROM_BACKGROUND`), keys and touches through `InputManager.injectInputEvent` (`INJECT_EVENTS`), clearing a display through `IActivityTaskManager` (`REMOVE_TASKS`). `exec()` serves only the app's `input -d N text '…'` (typed natively) and refuses anything else. The virtual-device display needs `CREATE_VIRTUAL_DEVICE`, which only the app-streaming companion role grants; Core gives itself that role (`MANAGE_ROLE_HOLDERS`; the role is system-only, so this works when Core is a system app, as on the ROM) and then makes a self-managed association, which needs no UI once the role is held. Without it Core falls back to a trusted `OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED` display, which on Android 15 also gets its own display and power group. Core releases a client's displays when its process dies (`linkClient`).
- **Owner policy.** Checked in this order: *hard floors* (the agent may never act on Core's own screens, factory-reset or erase the phone, disable/stop/uninstall Ubiq or Core, dial emergency numbers, or write security/provisioning/input-method/accessibility settings), the owner's *Stop switch* (ongoing "Ubiq is stopped" notification with Resume), then *per-app rules*: full > ask > read > off (default full). `read` lets the agent look but not act, `off` not even open the app, `ask` shows Core's `ApprovalActivity` (plus a notification, since it does not show over the lock screen): allow once, allow for this task, or deny; approvals expire after 10 minutes. Over binder a rule can only be made stricter and Stop can only be switched on, whatever origin the caller names; loosening and Resume happen only in Core's own UI (`RulesActivity`, reached from Ubiq Settings > Agent rules, and the notification). Those windows are `FLAG_SECURE`.
- **The hook.** `AgentTools.execute` calls `CoreClient.gate()` before every phone tool: it sends the app on screen (or being opened), the effect (`read` for get_screen/screenshot/open_app, else `write`, raised to `external`/`destructive` by the target's label: send/pay/call, delete/reset), the label, typed text and a screen excerpt; it waits for the owner on `approval_required` and turns a refusal into a tool error for the model. Without Core it returns at once.
- **First boot.** `ProvisionReceiver` (LOCKED_BOOT_COMPLETED: settings; BOOT_COMPLETED: everything) does once what `agentos-firstboot.sh` did as root: accessibility service and shortcut, HOME and ASSISTANT roles, power hold and corner swipe to the assistant, screen context, notification listener, mic and notification grants, gesture navigation overlay, staged apps from `/product/etc/agentos/apps/` installed silently through `PackageInstaller` with their runtime permissions, default dialer/SMS/browser, all-files access for Gallery and Files. The done flag lives in device-protected storage; `provision.rerun` and a button in the rules screen run it again.
- **UI without the accessibility service** (`ui.observe` / `ui.act`). Core builds its own `UiAutomation` in-process: `registerUiTestAutomationService` only enforces `RETRIEVE_WINDOW_CONTENT` (`signature|privileged`), and `UiAutomationConnection` trusts the uid that connected it, so a platform app may do what Cyclon does as root. It connects with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` so Ubiq's own service keeps running, reads any display (`getWindowsOnAllDisplays`) in the same text format as `ScreenSnapshot`, and acts on nodes or taps through input injection, after its own policy check. Only one UiAutomation can be registered at a time: `uiautomator dump` and instrumentation tests take it over. The agent loop does not use it yet.

## What a phone OS build adds

This app is the agent's own space: the conversation, the other chats and tasks, Space (library and jobs) and settings. It opens on the chat and is never the phone's home screen. Two plug-in points let a build for a whole phone OS add more without the app depending on it, both found through `ServiceLoader` like `OnDeviceModel`:

- `ui/HomeShell`: a home screen. With one registered, `HomeActivity` opens on it, Home returns to it, and leaving the main conversation goes back to it. The build's own manifest adds the `HOME` intent filter to `HomeActivity`.
- `agent/MiniAppPlugin`: mini apps, small screens the agent builds for the user. With one registered, the agent gets its tools and prompt section, jobs can report into a mini app, and the home screen can list them.

The Ubiq ROM's home screen and mini apps are not part of this repo.

## Sessions

`AgentRuntime` is a session manager; each `agent/Session` owns one conversation (its Responses API chain, chat items, status, pending question) and runs its own tool loop. There is one **main conversation** (`AgentRuntime.main`, id `main`, never reset or deleted), and every entry point goes to it: the home bar, the mic and dragging right, the assistant sheet (power hold, corner swipe, nav handle, each invocation showing only its own slice of the thread), share, notifications and the island (`AgentRuntime.toMain`). The only other conversations are **side chats** the user opens with + in the app; Back from one returns to the main conversation. The main conversation and side chats see each other: before each turn, `Session.elsewhere` puts what was said in the others since that model last looked in front of the message (main hears its side chats active since, a side chat hears main, from main's last 8 items on its first turn), with a per-conversation cursor (`seen`, saved with the session) so each exchange is heard once. The user's words there count as theirs for memory provenance. Background work runs as tasks of the main conversation and reports back into it: `start_task` (with a card), job runs (posted when they end) and mini-app requests (quiet: the result goes to the mini app, and only questions show). Sessions are saved to `files/sessions/<id>.json` (text only; attachments are listed by name; the main conversation keeps its last 5000 items) and reload at start; chats from before the main conversation stay in the list as side chats. The model's context stays bounded by compaction (Pi) or a chain reset with a recap (built-in loop) while the user sees the whole thread. A message sent to a session that is mid-task is queued and given to the agent at its next step.

What a turn holds lives in `TaskTarget`, taken on its first phone action and released when the turn ends:
- one of three run slots (`AgentRuntime.slots`); a fourth phone-using turn shows "Waiting: 3 tasks are already running";
- a desk (`workspace/Desk`, one hidden display each, all on one virtual device in the Shizuku helper; a session gets back the desk it used last when free), or the visible screen, leased under the key `screen`;
- leases on apps it opens (`AppLeases`), unless `AppCaps.multiInstance` says the launcher activity can run as several tasks (standard / singleTop, or declared multi-instance), in which case each desk launches its own task.

The user always wins: before each action a background turn checks whether the user has the app it was using on the real screen *and* the desk no longer shows it (the user's launch took the task). It then pauses with "Paused: you're using X", polls until the user has left the app, re-launches it on its desk (which pulls the task back) and tells the model to re-read the screen. Opening a single-window app the user is in waits the same way instead of taking it from them.

## Background tasks (conversation vs. execution)

`start_task(title, goal)` (both engines) hands longer work to a new session that runs on its own desk while the conversation stays free:
- **Creation:** `AgentRuntime.startTask` makes the child session and sets its `parentId`.
- **In the parent chat:** a `Role.TASK` item renders as a live card (`TaskCard` in `HomeActivity`): working, needs your answer, or the result. Tapping it opens the task's own chat.
- **When the task ends:** `Session.taskFinished` notifies the user and hands the result to the parent conversation's model (`Session.relay`), which tells the user in its own words. A finished task never brings its chat to the front.
- **Limits:**
  - a task cannot start tasks of its own;
  - a conversation runs at most `MAX_RUNNING` tasks at a time;
  - `task_status` reports their state, last steps and results.
  - The parent conversation's model is the user's one voice. A task's result, question, or `update_user` news (at most 3 per task) starts a short turn there, so the model passes it on in its own words, and a question's reply gets the question's buttons. If the conversation is mid-turn, the news waits for that turn to end and joins any message the user queued. If it is waiting on the user's answer to something else, or the turn says nothing, the task's own text is shown instead. Relay turns check their reply only against what the user asked, never against the task's text, and that text never counts as the user's request. A heads-up goes out unless the chat is on screen. Each user message tells the model which tasks are running. `pass_to_task` forwards the user's exact last message to a task, never model-written text, since an answer counts as approval.
- **Prompt guidance (`SystemPrompt`):** do quick single-app work directly; hand off multi-app, many-item or research work.

## Device conditions

`DeviceConditions` reads battery, power saver, thermal status, network (type, metered, validated, bandwidth) and memory. The policy lives in code:
- **Memory:** while the phone is low on memory, tasks take turns (`TaskTarget.resolve`), and `onTrimMemory` stops Pi's Node when no task is running.
- **Battery:** scheduled digests are postponed by 30 minutes when the battery is low or saver is on.

The model gets one line about these conditions, only when something is unusual, so the prompt stays cached. Settings > Brain shows the current conditions.

## The assistant sheet

Ubiq holds the ASSISTANT role through a real `VoiceInteractionService` (`assist/AgentVoiceService`), not only an `ACTION_ASSIST` activity. That is what makes the power-button hold and the corner swipe open `assist/AssistSession` over the current app, and what hands it the app's `AssistStructure` (screen text) and a screenshot. A request sent with that context runs on the visible screen even in background mode, because it is about the app the user is in: `AgentRuntime` asks the sheet to hide before the first phone action, re-shows it for `ask_user`, and re-shows it with the result instead of jumping to the home screen. A voice-interaction service must declare a recognition service, and Android makes it the default recognizer, so `assist/AgentRecognitionService` forwards to the recognizer that was there before (Google's). `scripts/install.sh` re-assigns the role (remove + add; re-adding a held role does nothing), sets the power-button hold to the assistant, turns on `assist_touch_gesture_enabled` (without it Pixel's gesture navigation never offers the corner swipe to any assistant), and enables screen context. Holding the navigation bar (or the Home button in three-button mode) cannot be given to Ubiq on a stock Pixel: the Pixel launcher and SystemUI send it to Circle to Search, and with Circle to Search turned off (`search_all_entrypoints_enabled=0`) they do nothing rather than call the assistant. In the AOSP ROM, Launcher3 does call the assistant there.

## Notifications

`notify/NotificationWatcher` is a `NotificationListenerService` (access granted by `scripts/install.sh`, or in Ubiq Settings). It records every non-ongoing notification into `NotificationStore` (last 400, persisted, content intents kept in memory for `open_notification`) and hands each one to `Jobs.onNotification` for trigger jobs. `NotifyRules` makes and keeps digests.

`jobs/Jobs` is the store for everything the user sets up to run later (`files/jobs.json`, shown in Space > Jobs, created with the `add_job` family of tools in `jobs/JobTools`): ONCE (at a time), RECURRING (hourly / daily / weekdays / weekly on days / monthly, every N, optional until and max runs) and TRIGGER (a notification from an app, optional keywords). The clock is one exact `RTC_WAKEUP` alarm (`USE_EXACT_ALARM`) for the soonest run; `JobAlarmReceiver` runs what is due and re-arms, and also fires on boot, package replace and clock or time zone changes. A run that is late (phone off) still starts within a grace window (6 h one-time, 3 h daily-style, up to 1 h hourly), otherwise it is skipped with a note. A task job becomes an automated session in `AgentRuntime.runAutomated` (a scheduled run is skipped if the previous one is still going; a trigger feeds later matches into it); a digest job calls `NotifyRules.digest`. The old `notify_rules.json` is migrated into jobs on first start. Job runs cannot add or change jobs. They start with a preamble saying notification text is data, not instructions, and run with `TaskTarget.guarded`: a tap on anything labelled send / reply / post / share / pay / delete / confirm (and a non-search submit) is refused until the user has answered an `ask_user`, one answer per action. When the agent needs an answer or an automated task finishes while Ubiq is not on screen, it posts a notification.

## Being the OS

- `HomeActivity` handles `ASSIST`, `VOICE_COMMAND`, and `SEND` (share sheet → "send to Ubiq"), and `HOME` only in a build with a `HomeShell`.
- On stock Android everything is granted by the user (or by `scripts/install.sh` over adb): accessibility service on, ASSISTANT role, mic. The phone keeps its own home screen.
- In the AOSP build (`aosp/`), Ubiq is a privileged system app, `preferred-apps` makes it HOME without a chooser, a framework overlay makes it the default assistant and notification listener, and Ubiq Core enables the control service and roles on first boot (see docs/ROM.md).

## What's deliberately not here yet

- Multi-user and work-profile handling.
- **Built-in workspace helper (planned).** Start `WorkspaceShell` as the shell user without Shizuku: Ubiq pairs with the phone's own adbd over wireless debugging (pairing code typed into a notification reply, the way Shizuku does it), then launches the helper with `app_process`. A third `WorkspaceBackend` after Core and Shizuku; Shizuku stays as a fallback. The user still enables Developer options and wireless debugging and pairs once, on Wi-Fi. After a reboot the helper needs Wi-Fi to start again (same as Shizuku): Ubiq restarts it on its own when the phone joins Wi-Fi, if granting itself `WRITE_SECURE_SETTINGS` to turn wireless debugging back on proves to work, and shows "Background tasks resume when you're on Wi-Fi" until then. Once running it keeps working without Wi-Fi. Candidate code: Shizuku's starter (Apache-2.0) or [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache-2.0 option).
