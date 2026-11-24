#!/usr/bin/env python3
"""Upgrade test (#201): does a new build keep what the previous release saved?

Builds the previous release's releaseTest APK, enters observations by tapping through its UI,
deletes one, picks co-observers and changes a setting, and records what the app shows. Then it
installs this checkout's build over it with `install -r`, as an app store update does, records the
same screens and fails if any row that showed the test data is gone or changed.

    upgrade_test.py run [<tag>]    the whole test; <tag> defaults to the newest v* tag
    upgrade_test.py seed           enter the test data into the installed app
    upgrade_test.py snapshot       print what the installed app shows, as JSON

The old app is driven by the script as it was at <tag>, so the taps always match the UI they drive.
A tag older than this script gets today's copy. The script finds buttons by their text in a
`uiautomator dump`, like screenshots.py. When the UI text changes, update it here.

Only touches the .releasetest app, never the release app with the real field notes.
"""

import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "io.github.mortenfyhn.feltbok.releasetest"
ACTIVITY = f"{APP}/io.github.mortenfyhn.feltbok.MainActivity"
ROOT = Path(__file__).resolve().parents[2]
VERSION = re.compile(r"^v\d+\.\d+")
# The list's co-observer row: the names picked for the next note, here the one seed() added.
MEDOBS = re.compile(r"^(Ingen medobservatører valgt|Ola)$")
# Only the notes list has this footer link.
LIST = "Gi tilbakemelding"
APK = "app/build/outputs/apk/releaseTest/app-releaseTest.apk"
# What seed() enters. Only rows that show one of these are compared, so a renamed label or button
# ("Slå av alle" -> "Ingen medobservatører") doesn't fail the test. A note's locality, time and
# count are on its list row, next to the species.
SEEDED = re.compile(
    r"(?<!\w)(Ola|kjøttmeis|gråmåke|skjære|Oppgraderingstest|Privat notat|Adult|Hann|Svensk)(?!\w)"
)


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True).stdout


def shell(cmd):
    return adb("shell", cmd).decode()


def dump():
    """The current screen's nodes. The dump fails while the UI animates; retry."""
    for _ in range(40):
        # Delete the last dump first, so a failed dump isn't read as the current screen.
        shell(
            "rm -f /sdcard/feltbok-ui.xml; uiautomator dump /sdcard/feltbok-ui.xml >/dev/null 2>&1 || true"
        )
        try:
            return list(
                ET.fromstring(adb("exec-out", "cat", "/sdcard/feltbok-ui.xml")).iter(
                    "node"
                )
            )
        except ET.ParseError:
            time.sleep(0.5)
    sys.exit("uiautomator dump keeps failing")


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def find(text, timeout=10):
    """The node whose text or content description is `text`, else the first that contains it as a
    whole word (a list row reads "3 kjøttmeis"). A compiled pattern is used as it is."""
    word = (
        text
        if isinstance(text, re.Pattern)
        else re.compile(rf"(?<!\w){re.escape(text)}(?!\w)")
    )
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        nodes = dump()
        exact = [n for n in nodes if text in (n.get("text"), n.get("content-desc"))]
        found = exact or [
            n
            for n in nodes
            if any(word.search(n.get(a, "")) for a in ("text", "content-desc"))
        ]
        if found:
            return found[0]
        time.sleep(0.5)
    sys.exit(f"Timed out waiting for {text!r} on screen")


def tap(text):
    x1, y1, x2, y2 = bounds(find(text))
    shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(0.7)


def type_text(text):
    """ASCII only: `input text` can't type æøå."""
    shell(f"input text {text.replace(' ', '%s')}")
    time.sleep(0.7)


def back():
    shell("input keyevent KEYCODE_BACK")
    time.sleep(0.7)


def to_list():
    """Back to the notes list. The first Back may only close the keyboard."""
    for _ in range(4):
        if any(n.get("text") == LIST for n in dump()):
            return
        back()
    sys.exit("Couldn't get back to the list")


def rows():
    """The screen as "label: value" lines: text nodes grouped by row, left to right, with a
    switch's state. Distances ("22 m") are dropped, since GPS jitter changes them."""
    texts, switches = [], []
    for n in dump():
        x1, y1, _, y2 = bounds(n)
        if n.get("checkable") == "true":
            switches.append(
                ((y1 + y2) // 2, "på" if n.get("checked") == "true" else "av")
            )
        t = n.get("text", "").replace("›", "").strip()
        if t:
            texts.append(((y1 + y2) // 2, x1, t))
    lines = {}
    for y, x, t in sorted(texts):
        key = next((k for k in lines if abs(k - y) < 20), y)
        lines.setdefault(key, []).append(t)
    for y, state in switches:
        key = next((k for k in lines if abs(k - y) < 40), None)
        if key is not None:
            lines[key].append(state)
    out = []
    for parts in lines.values():
        parts = [p for p in parts if not re.fullmatch(r"[\d,.]+ k?m", p)]
        if parts:
            out.append(parts[0] + (": " + " ".join(parts[1:]) if parts[1:] else ""))
    return out


def launch():
    shell(f"am start -n {ACTIVITY}")
    time.sleep(2)
    to_list()  # a running app comes back on the screen it was left on


def add_note(search, species, *steps):
    tap("+")
    type_text(search)
    tap(species)
    for step in steps:
        step()
    tap("Lagre")
    find(LIST)


def pick(field, option):
    return lambda: (tap(field), tap(option))


def comment(field, text):
    return lambda: (tap(field), type_text(text), tap("Ferdig"))


def seed():
    if "Success" not in shell(f"pm clear {APP}"):
        sys.exit(f"Couldn't clear {APP}'s data")
    shell(f"pm grant {APP} android.permission.ACCESS_FINE_LOCATION")
    launch()
    find("du er her", timeout=60)  # so the notes get a locality

    def coobs():
        tap("Medobs.")
        tap("Søk eller skriv navn…")
        type_text("Ola Testesen")
        tap("Legg til «Ola Testesen»")
        tap("Ferdig")

    add_note(
        "kjottmeis",
        "kjøttmeis",
        lambda: (tap("+"), tap("+")),
        pick("Alder", "Adult"),
        comment("Åpen kommentar", "Oppgraderingstest"),
        coobs,
    )
    add_note(
        "gramake",
        "gråmåke",
        pick("Kjønn", "Hann"),
        comment("Privat kommentar", "Privat notat"),
    )
    add_note("skjaere", "skjære")
    # Into the papirkurv.
    tap("1 skjære")
    tap("Slett")
    find(LIST)

    tap(VERSION)  # the version in the footer opens Om Feltbok
    tap("Innstillinger")
    tap("Andrespråk for artsnavn")
    tap("Svensk")
    to_list()


def snapshot():
    launch()
    find("du er her", timeout=60)
    shot = {}
    # The GPS line and the version change between runs and builds.
    shot["list"] = [r for r in rows() if "GPS" not in r and not VERSION.match(r)]
    for row in ("3 kjøttmeis", "1 gråmåke"):
        tap(row)
        shot[row] = rows()
        to_list()
    tap("Papirkurv")
    shot["papirkurv"] = rows()
    to_list()
    # Recent species come first in the search, from uses.json.
    tap("+")
    find("Søk art…")
    shot["søk"] = [r for r in rows() if "Tilbake" not in r and "Søk art" not in r][:3]
    to_list()
    tap(MEDOBS)
    find("Medobservatører")
    shot["medobservatører"] = rows()
    to_list()
    tap(VERSION)
    tap("Innstillinger")
    shot["innstillinger"] = rows()
    to_list()
    return shot


def build(tree):
    subprocess.run(
        ["./gradlew", "assembleReleaseTest", "--console=plain", "-q"],
        cwd=tree,
        check=True,
    )
    return tree / APK


def run(tag):
    with tempfile.TemporaryDirectory() as tmp:
        old = Path(tmp) / "old"
        subprocess.run(
            ["git", "worktree", "add", "--detach", old, tag], cwd=ROOT, check=True
        )
        try:
            shutil.copy(ROOT / "local.properties", old)
            print(f"Building {tag}…")
            old_apk = build(old)
            script = old / "scripts/dev/upgrade_test.py"
            if not script.exists():
                script = Path(__file__)
            subprocess.run(["adb", "uninstall", APP], capture_output=True, check=False)
            adb("install", old_apk)
            subprocess.run([sys.executable, script, "seed"], check=True)
            before = json.loads(
                subprocess.run(
                    [sys.executable, script, "snapshot"],
                    check=True,
                    capture_output=True,
                ).stdout
            )
        finally:
            subprocess.run(
                ["git", "worktree", "remove", "--force", old], cwd=ROOT, check=True
            )
    print("Building this checkout…")
    adb("install", "-r", build(ROOT))
    after = snapshot()
    lost = {
        k: [r for r in v if SEEDED.search(r) and r not in after.get(k, [])]
        for k, v in before.items()
    }
    lost = {k: v for k, v in lost.items() if v}
    if lost:
        print(f"Upgrading from {tag} lost data:")
        print(json.dumps(lost, ensure_ascii=False, indent=2))
        print("Before:", json.dumps(before, ensure_ascii=False, indent=2))
        print("After:", json.dumps(after, ensure_ascii=False, indent=2))
        sys.exit(1)
    print(f"Upgrade from {tag} kept everything.")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "seed":
        seed()
    elif cmd == "snapshot":
        print(json.dumps(snapshot(), ensure_ascii=False, indent=2))
    elif cmd == "run":
        tag = (
            sys.argv[2]
            if len(sys.argv) > 2
            else subprocess.run(
                [
                    "git",
                    "describe",
                    "--tags",
                    "--abbrev=0",
                    "--match",
                    "v*",
                    "--exclude",
                    "*-rc*",
                ],
                cwd=ROOT,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
        )
        run(tag)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
