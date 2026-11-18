# Ubiq

An Android agent that operates your phone for you: it reads the screen, taps through apps, and
can work on a hidden second screen while you keep using the phone.

The agent thinks with a cloud model over its API: bring your own OpenAI API key (Settings >
Brain). Nothing runs a model on the phone.

One APK runs everywhere. On a stock phone it uses the visible screen, or Shizuku for the hidden
background workspace. On the Ubiq ROM it also binds to Ubiq Core, the privileged system
service that provides the workspace and enforces the owner's rules from its own process.

## Build and install

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
cd android && ./gradlew -PnoPi=true :app:assembleDebug   # without the on-phone Pi runtime
scripts/install.sh --api-key sk-...                      # build, install and wire it into a connected phone
```

`scripts/build-node-runtime.sh` (run by the build unless `-PnoPi=true`) packs Node and the Pi
harness (`harness/`) into the APK. See [docs/PIXEL_SETUP.md](docs/PIXEL_SETUP.md) for the phone setup,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works and [docs/AGENT_DESIGN.md](docs/AGENT_DESIGN.md)
for where the agent is going.

## Layout

```
android/
  app/        the agent app: loop, tools, UI, jobs, voice, workspace (Shizuku or Core)
  coreapi/    the AIDL contract with Ubiq Core (ICore, IWorkspaceShell)
  policy/     the owner's rules in plain Kotlin: hard floors, Stop, per-app full/ask/read/off
harness/      the Pi agent loop in Node, packed into the APK
scripts/      install, emulator, Node runtime packing, Pi dev loop
docs/
```

The Kotlin code lives under the `com.agentos` package, the project's original name; the app itself
is `ai.ubiq.app`, and everything you see in it says Ubiq.

## Owner rules

Before each phone action the agent's request passes the same rules everywhere (`policy/`): hard
floors it can never cross (factory reset, emergency calls, disabling Ubiq), the owner's Stop
switch, then a per-app rule. On a stock phone the app enforces them itself (Settings > Agent rules);
on the ROM, Ubiq Core does, so the app cannot change them. The agent can only make a rule stricter.

## Contributing

Issues and pull requests are welcome. CI builds the app and runs the unit tests and lint on every
pull request; please keep them passing. This repository is published from the project's main repo
once per release, so each release shows up here as one commit, and merged pull requests are
carried back into the main repo.

## License

Apache License 2.0, see [LICENSE](LICENSE). Third-party components and their licences are listed
in [NOTICE](NOTICE).
