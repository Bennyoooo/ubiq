#!/usr/bin/env python3
"""
A/B of the agent engines (docs/AGENT_DESIGN.md, migration step 3): the built-in Kotlin loop against the Pi harness,
on the same device tasks, each judged by the phone's real state (never the model's own claim).

  scripts/pi-dev.sh --serial emulator-5554 &        # the Pi harness must be running
  python3 scripts/ab-engines.py --serial emulator-5554 --tasks path/to/device_tasks.py [--only id,id] [--runs 1]

Tasks come from a device_tasks.py (TASKS: id, prompt, setup, check, baseline, teardown, apps). Results go to
--out (default: a scratch dir) as results.jsonl plus a summary table on stdout.
"""
import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import time

PKG = "ai.ubiq.app"
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")), "platform-tools", "adb")


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.emulator = serial.startswith("emulator-")

    def adb(self, *args, timeout=120):
        r = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr

    def sh(self, cmd, timeout=120):
        return self.adb("shell", cmd, timeout=timeout)

    def run(self, cmd):
        if cmd.startswith("@emu "):
            return self.adb("emu", *cmd[5:].split(" ")) if self.emulator else ""
        return self.sh(cmd)

    def broadcast(self, action, extras=""):
        self.sh(f"am broadcast -a com.agentos.{action} {extras} {PKG}")

    def read(self, name):
        return self.sh(f"run-as {PKG} cat files/{name}")

    def dump(self):
        """The focused session from DEBUG_DUMP's debug_chat.txt: status, stats and (role, text) items."""
        self.broadcast("DEBUG_DUMP")
        time.sleep(0.4)
        txt = self.read("debug_chat.txt").split("\n=== sessions")[0]
        status = re.search(r"^status=(.*)$", txt, re.M)
        stats = re.search(r"^stats=(.*)$", txt, re.M)
        items, cur = [], None
        for line in txt.splitlines()[2:]:
            m = re.match(r"^(USER|ASSISTANT|ACTION|SYSTEM): ?(.*)$", line)
            if m:
                cur = [m.group(1), m.group(2)]; items.append(cur)
            elif cur:
                cur[1] += "\n" + line
        return {"status": status.group(1) if status else "", "stats": stats.group(1) if stats else "", "items": items}

    def screen(self):
        self.sh(f"run-as {PKG} rm -f files/debug_last.txt")
        self.broadcast("DEBUG_TOOL", "--es name get_screen")
        for _ in range(20):
            time.sleep(0.5)
            out = self.read("debug_last.txt")
            if "No such file" not in out and out.strip():
                return out
        return ""


def check(dev, c, answer, base=0, front=()):
    if "all" in c:
        return all(check(dev, x, answer, base, front) for x in c["all"])
    if "not" in c:
        return not check(dev, c["not"], answer, base, front)
    if "front" in c:
        return any(re.search(c["front"], p) for p in front)
    if "screen_of" in c:
        dev.sh(f"monkey -p {c['screen_of']} -c android.intent.category.LAUNCHER 1")
        time.sleep(2.5)
        return re.search(c["match"], dev.screen()) is not None
    if "answer" in c:
        return re.search(c["answer"], answer) is not None
    if "screen" in c:
        return re.search(c["screen"], dev.screen()) is not None
    return re.search(c["match"], dev.sh(c["shell"].replace("{base}", str(base))), re.M) is not None


SVC = "ai.ubiq.app/com.agentos.control.ControlService"


def ensure_control(dev):
    """A reinstall can leave the accessibility service half-bound (the screen reads nearly empty): bind it again."""
    dev.sh(f"cmd package set-home-activity {PKG}/{PKG}.ui.HomeActivity")
    if len(dev.screen().splitlines()) > 3:
        return True
    dev.sh("settings put secure enabled_accessibility_services null")
    time.sleep(1)
    dev.sh(f"settings put secure enabled_accessibility_services {SVC}; settings put secure accessibility_enabled 1")
    time.sleep(3)
    return len(dev.screen().splitlines()) > 3


def prepare(dev, task):
    dev.broadcast("DEBUG_STOP")
    dev.sh("input keyevent HOME"); time.sleep(1)
    if not ensure_control(dev):
        print("  warning: phone control is not working", flush=True)
    for _ in range(3):
        if "ResolverActivity" not in dev.sh("dumpsys activity activities | grep -m1 topResumedActivity"):
            break
        dev.sh("input keyevent BACK"); time.sleep(0.5)
    dev.sh("input keyevent HOME")
    for p in task.get("apps", []):
        dev.sh(f"am force-stop {p}")
    for cmd in task.get("setup", []):
        dev.run(cmd)
    time.sleep(1.0)
    return int(re.sub(r"\D", "", dev.sh(task["baseline"])) or 0) if task.get("baseline") else 0


def run_task(dev, task, max_secs):
    base = prepare(dev, task)
    prompt = task["prompt"].replace("'", "'\\''")
    t0 = time.time()
    dev.sh(f"am broadcast -a com.agentos.DEBUG_SAY --ez new true --es text '{prompt}' {PKG}")
    front, d, state = set(), None, "running"
    polls = 0
    while time.time() - t0 < max_secs:
        top = re.search(r"u0 ([\w.]+)/", dev.sh("dumpsys activity activities | grep -m1 topResumedActivity"))
        if top:
            front.add(top.group(1))
        polls += 1
        if polls % 3 == 0:
            d = dev.dump()
            if d["items"] and d["items"][0][1].startswith(task["prompt"][:40]):
                if d["status"].startswith("Idle"):
                    state = "idle"; break
                if d["status"].startswith("WaitingForUser"):
                    state = "waiting"; break
        time.sleep(0.6)
    wall = time.time() - t0
    if state == "running":
        dev.broadcast("DEBUG_STOP"); time.sleep(2); d = dev.dump()
    items = (d or {}).get("items", [])
    replies = [t for r, t in items if r == "ASSISTANT"]
    answer = replies[-1] if replies else ""
    ok = check(dev, task["check"], answer, base, front)
    for cmd in task.get("teardown", []):
        dev.run(cmd)
    return {"id": task["id"], "ok": ok, "state": state, "secs": round(wall, 1), "answer": answer[:300],
            "actions": [t for r, t in items if r == "ACTION"], "stats": (d or {}).get("stats", "")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--tasks", required=True, help="a device_tasks.py with TASKS")
    ap.add_argument("--engines", default="builtin,pi")
    ap.add_argument("--only", default="")
    ap.add_argument("--skip", default="")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--max-secs", type=int, default=240)
    ap.add_argument("--effort", default="", help="set the phone's effort first (low/medium/high)")
    ap.add_argument("--out", default=os.path.join(os.environ.get("TMPDIR", "/tmp"), f"ab-{int(time.time())}"))
    a = ap.parse_args()

    spec = importlib.util.spec_from_file_location("device_tasks", a.tasks)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    tasks = [t for t in mod.TASKS if (not a.only or t["id"] in a.only.split(",")) and t["id"] not in a.skip.split(",")]
    dev = Device(a.serial)
    os.makedirs(a.out, exist_ok=True)
    out = open(os.path.join(a.out, "results.jsonl"), "a")
    if a.effort:
        dev.broadcast("DEBUG_PREF", f"--es key effort --es value {a.effort}")
    rows = {}
    for run in range(a.runs):
        for task in tasks:
            for engine in a.engines.split(","):   # alternate engines per task, so drift hits both alike
                dev.broadcast("DEBUG_PREF", f"--es key engine --es value {engine}")
                time.sleep(0.5)
                r = run_task(dev, task, a.max_secs)
                r.update(engine=engine, run=run)
                out.write(json.dumps(r) + "\n"); out.flush()
                rows.setdefault(engine, []).append(r)
                print(f"{engine:8} {task['id']:28} {'PASS' if r['ok'] else 'fail'} {r['secs']:6.1f}s {r['state']:8} {r['answer'][:70]!r}", flush=True)
    dev.broadcast("DEBUG_PREF", "--es key engine --es value pi")   # the default engine
    print("\nengine    pass        mean s   median s")
    for engine, rs in rows.items():
        secs = sorted(r["secs"] for r in rs)
        print(f"{engine:8} {sum(r['ok'] for r in rs):3}/{len(rs):<3}  {sum(secs) / len(secs):8.1f} {secs[len(secs) // 2]:9.1f}")
    print(f"\nresults: {a.out}/results.jsonl")


if __name__ == "__main__":
    sys.exit(main())
