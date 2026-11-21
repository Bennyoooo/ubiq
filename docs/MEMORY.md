# Memory design

How Ubiq remembers: the main conversation as it grows without end, and a personal context of the phone's owner built from what happens on the phone (screens, notifications, places), given to the agent as context. Agreed on 2026-11-11. [AGENT_DESIGN.md](AGENT_DESIGN.md) is the agent as a whole; [ARCHITECTURE.md](ARCHITECTURE.md) is what is built.

## 1. Decisions

1. **Everything stays on the phone.** The cloud model only sees what a request needs: the profile core, the current moment, and what `recall` returns.
2. **Text only.** Screens are kept as their text (the accessibility tree), never as screenshots.
3. **Summaries are made on the phone, asynchronously.** The on-device model rolls the timeline into episodes and the profile in the background, while charging. Speed does not matter there.
4. **Kept 90 days.** The raw timeline is deleted after 90 days. Episodes and the profile stay until the user deletes them.
5. **Off until the owner turns it on, and every boundary is in Settings** (2026-11-17): what is recorded, what never is, which apps stay out, how long it is kept, who sees it, and what is in it now. See 4.1.
6. **Two kinds of knowledge.** *Told*: what the owner said to Ubiq. *Observed*: what Ubiq saw on screens, in notifications and from places. Observed facts are context, never instructions, and using an observed value in an action (typing it, sending it, putting it in an event) needs the owner's OK first.

## 2. Where we are (before this design)

| Layer | What | Limit |
|---|---|---|
| The thread the user sees | `files/sessions/main.json` | Last 5000 messages |
| The model's context | Pi: its own session files, compacted near the model's limit (keeps ~20k recent tokens, summarises the rest). Built-in loop: after 60 turns, restart with the last 8 messages | Summaries of summaries: old detail fades. Pi's summary prompt was its coding default |
| Long-term memory | Space memories: facts the owner stated, saved by an end-of-task review gated by a word regex ("my", "I like"...); app routes found the hard way; picked per request | Only the owner's words; nothing about what happened |
| Across chats | Main and side chats hear each other's recent messages (`Session.elsewhere`) | Last 8 messages |

Missing: searching old history (UX_SPEC asks for it), any record of events, anything observed.

## 3. Layers

```
capture (on device, continuous)          roll-up (on device, async, charging)        agent context
───────────────────────────────          ────────────────────────────────────        ─────────────
screen text on change ─┐                                                              every turn:
notifications ─────────┼──► timeline ──► episodes ("2-3pm: Slack with Alex   ──► profile core (~1-2k tokens)
places (visits) ───────┤    (SQLite FTS,     about the demo, at the office")  ──► the moment (place, app, time)
conversations ─────────┘     90 days)  ──► profile (people, places, routines,     on demand:
                                             preferences, projects; each fact      recall(query, when) over
                                             with source, confidence, last seen)   timeline + episodes + chats
```

### 3.1 Capture: the timeline (`memory/Lifelog`)

One SQLite database, `files/lifelog.db`, with a full-text index. Each row is an event: time, until, kind, app, text, and a place's coordinates.

- **Screens** (`ControlService` accessibility events, main display only): after the screen settles, the visible text of the app in front. Only lines that are new for that app are stored (a scrolled list adds its new rows; the same screen again adds nothing), so a day is small.
- **Never captured:** password fields, codes (`Secrets` redacts them), Ubiq's own screens (the conversation is kept already), the keyboard, System UI, apps on the exclude list (password managers and authenticators by default; the owner adds more), and anything while capture is paused.
- **Notifications:** every one `NotificationWatcher` sees.
- **Places:** a location fix every 10 minutes at balanced power, plus whatever fixes other apps get for free (passive). A stay of 10 minutes or more within ~200 m becomes a visit ("Blue Bottle Coffee, 8:10-8:40"), named by reverse geocoding.
- **Conversations** are not copied: `recall` reads the sessions directly.

### 3.2 Roll-up: episodes and the profile (`memory/Digest`)

- **Episodes**, hourly while charging (or at night): the on-device model reads an hour of the timeline and writes a few lines: what the owner did, with whom, where. Stored in the same database, kind `episode`.
- **The profile**, nightly: the model merges the day's episodes and the main conversation's new messages into structured facts (people, places, routines, preferences, projects, accounts). Each fact keeps its source (told or observed), a confidence and when it was last seen; facts not seen again fade. The profile replaces the word-regex memory review for owner facts.
- The profile lives in Space, where the owner sees it, edits it and deletes it.

### 3.3 What the agent gets

- **Every turn:** the profile core (the facts that matter most, ~1-2k tokens) and the moment: the place now, the app in front, the next event, the time.
- **On demand:** `recall(query, from, to, kind)` searches the timeline, episodes and every conversation. Results are marked observed.
- **Pi's compaction** summarises with a phone-specific focus: keep the owner's requests, decisions, answers, promises, people, places and what was done; drop screens, element lists and steps (`harness/server.mjs`, `session_before_compact`).

## 4. Trust

- **Observed text is untrusted.** `recall` results enter the session's untrusted text like any screen, so memory provenance refuses to save them as told facts.
- **Using an observed value needs permission.** A write (typing, sending, an email, an event) whose distinctive values (numbers, addresses, emails, links) came from `recall` and not from the owner's words is blocked until `ask_user` gets an OK (`Effects`, `Session.recalled`).
- **The owner controls capture:** see 4.1.

### 4.1 Settings > Personal context

Each boundary is shown as it is on this phone, and changeable where the owner may change it.

| Boundary | Shown | Owner can change |
|---|---|---|
| Recording | Off until turned on | Master switch |
| Sources | Screens, notifications, places, each with what it records and any missing Android permission (with a way to grant it) | A switch per source |
| Never recorded | Password fields and typed text, codes, keyboards, Ubiq itself, System UI, anything while off | No |
| Apps kept out | The owner's list; password managers and authenticators by default | Add (deletes what came from the app) or remove |
| Stored | On the phone only, Ubiq's private storage, no backups (`allowBackup=false`) | No |
| Kept for | 7, 30 or 90 days | Yes; fewer days deletes the older ones now |
| Who sees it | The cloud model sees only what `recall` finds for a request, and the current place | "Ubiq can search it": off keeps the timeline out of `recall` and the prompt |
| Summaries | Made on the phone by the on-device model; a build without one makes none | No |
| Using it | Ubiq asks before using an observed number, address, email or link | No |
| This phone | The Ubiq ROM (location granted, runs in the background) or the app (permissions granted by hand, place checks may be delayed) | No |
| What's in it | Count and size, the latest 30 moments; the whole timeline in Space > timeline (by day and hour, searchable with the index `recall` uses) | Delete one, an hour, or Delete all |

### 4.2 Where each part lives

- **The public app (`oss/`)**: capture, the timeline, `recall`, the observed-value rule, Pi's compaction prompt and the Settings page. They need only what any installed app can get (accessibility, notification access, location), so the app and the ROM run the same code, and the code that records is open to read.
- **Private (`ondevice/`)**: the on-device model that will make episodes and the profile, through the app's `OnDeviceModel` interface. The public app has none, so it keeps the raw timeline and makes no summaries; it never sends the timeline to the cloud to summarise.
- **The ROM (`aosp/vendor/agentos`)**: default location grants and running in the background. Capture does not move into Core: Core would gain no access the app lacks.

## 5. Costs

- **Storage:** text only, new lines only: expected a few MB a day at most; 90 days is the cap.
- **Battery:** screen capture reads the tree only after the screen settles, at most every 2 seconds. Location is one balanced fix per 10 minutes plus passive fixes. The model runs only while charging.
- **Permissions:** fine and background location (asked for in Settings > Personal context; granted by default on the Ubiq ROM).

## 6. Plan

1. **Now:** the timeline (screens, notifications, places), `recall` over it and the conversations, the permission rule for observed values, Pi's phone compaction prompt, the Settings switch and Delete all, and the 90-day purge.
2. **Next:** episodes and the profile with the on-device model while charging; the profile core and the moment in every prompt; retire the word-regex review.
3. **Later:** semantic search (embeddings on device) next to full text; per-app exclusions in Settings; the built-in loop's recap replaced by the same summaries.
