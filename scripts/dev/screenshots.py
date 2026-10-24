#!/usr/bin/env python3
"""Capture the store screenshots from the dev build on an attached phone, straight into fastlane's
phoneScreenshots/. Run via `just screenshots`, which first installs a dev build that shows the
release version string in the list footer.

Clears the dev app's data, so every run starts from the default settings (Latin as the second
species name, no learned search ranking), then seeds a small showcase set of notes. The list header
and the editor's distance come from live GPS, so run it somewhere you're happy to show.

Captures at 1080x1920 (`wm size`) so one set serves both stores: Play rejects the phone's native
9:20 ratio. `screencap` gives lossless PNG; the phone's screenshot button saves JPEG with ringing
around text. Status bar demo mode fixes the clock and battery and hides notifications. The script
finds buttons by their text in a `uiautomator dump`, so it survives layout changes.
"""

import json
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from make_sample_notes import DAY, MINUTE, build, note

# Never the release app: this clears its data.
APP = "io.github.mortenfyhn.feltbok.debug"
ACTIVITY = f"{APP}/io.github.mortenfyhn.feltbok.MainActivity"
OUT = (
    Path(__file__).resolve().parents[2]
    / "fastlane/metadata/android/en-US/images/phoneScreenshots"
)

TJELD = ("tjeld", "Haematopus ostralegus", "NT")
RINGDUE = ("ringdue", "Columba palumbus", "")
FISKEMAAKE = ("fiskemåke", "Larus canus", "VU")
GRAASPURV = ("gråspurv", "Passer domesticus", "NT")

# What a real day in the field looks like: a few species over three days, a near-duplicate pair.
SHOWCASE = [
    note("", 20, TJELD, 1, "", "", "Rastende", "Sorgenfri"),
    note("", 150, RINGDUE, 2, "Adult", "", "", "Rosenborg skole"),
    note(
        "", DAY // MINUTE + 300, FISKEMAAKE, 1, "Adult", "", "Rastende", "Gamle Bybro"
    ),
    note("", DAY // MINUTE + 301, FISKEMAAKE, 3, "Pulli", "", "", "Gamle Bybro"),
    note(
        "",
        3 * DAY // MINUTE,
        GRAASPURV,
        1,
        "1K",
        "",
        "",
        "Sorgenfri",
        pub="Mat til unger",
    ),
    note("", 3 * DAY // MINUTE + 1, GRAASPURV, 1, "Adult", "Hann", "", "Sorgenfri"),
]


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True).stdout


def shell(cmd):
    return adb("shell", cmd).decode()


def demo(command, **extras):
    args = "".join(f" -e {k} {v}" for k, v in extras.items())
    shell(f"am broadcast -a com.android.systemui.demo -e command {command}{args}")


def find(text, timeout=10):
    """The first on-screen node whose text or content description is `text`, or contains it as a
    whole word (a list row reads "1 tjeld NT")."""
    word = re.compile(rf"(?<!\w){re.escape(text)}(?!\w)")
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        # The dump fails while the UI keeps animating; just retry.
        if "dumped" in shell("uiautomator dump /sdcard/feltbok-ui.xml 2>&1"):
            root = ET.fromstring(adb("exec-out", "cat", "/sdcard/feltbok-ui.xml"))
            for n in root.iter("node"):
                if any(word.search(n.get(a, "")) for a in ("text", "content-desc")):
                    return n
        time.sleep(0.5)
    sys.exit(f"Timed out waiting for {text!r} on screen")


def tap(text):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", find(text).get("bounds")))
    shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


def back():
    shell("input keyevent KEYCODE_BACK")


def capture(name):
    """Save the screen once two captures a second apart match, so map tiles and transitions finish."""
    prev = None
    for _ in range(15):
        png = adb("exec-out", "screencap", "-p")
        if png == prev:
            break
        prev = png
        time.sleep(1)
    (OUT / f"{name}.png").write_bytes(png)
    print(f"wrote {name}.png")


def seed():
    shell(f"pm clear {APP}")
    for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        shell(f"pm grant {APP} android.permission.{perm}")
    # The clear also deletes the external files dir; one launch lets the app recreate it as its own.
    shell(f"am start -n {ACTIVITY}")
    find("Arkiv")  # an empty list has no Eksporter button
    shell(f"am force-stop {APP}")
    with tempfile.NamedTemporaryFile("w", suffix=".json") as f:
        json.dump(build(int(time.time() * 1000), SHOWCASE), f, ensure_ascii=False)
        f.flush()
        adb("push", f.name, f"/sdcard/Android/data/{APP}/files/seed-notes.json")


def shoot():
    shell(f"am start -n {ACTIVITY}")
    find("Eksporter")
    capture("1")

    tap("+")
    shell("input text f")
    find("fiskemåke")
    if "mInputShown=true" in shell("dumpsys input_method"):
        back()  # hide the keyboard
    capture("3")
    back()

    tap("tjeld")
    find("Lagre")
    capture("4")

    tap("Lokalitet")
    find("Velg lokalitet")
    capture("2")
    back()
    back()

    tap("Eksporter")
    find("Kopier observasjoner")
    capture("5")
    back()


def main():
    seed()
    demo_allowed = shell("settings get global sysui_demo_allowed").strip()
    shell("wm size 1080x1920")
    try:
        shell("settings put global sysui_demo_allowed 1")
        demo("enter")
        demo("clock", hhmm="1200")
        demo("battery", level="100", plugged="false")
        demo("network", wifi="show", level="4", fully="true")
        demo("network", mobile="show", datatype="none", level="4", fully="true")
        demo("notifications", visible="false")
        shoot()
    finally:
        demo("exit")
        if demo_allowed == "null":
            shell("settings delete global sysui_demo_allowed")
        else:
            shell(f"settings put global sysui_demo_allowed {demo_allowed}")
        shell("wm size reset")


if __name__ == "__main__":
    main()
