#!/usr/bin/env python3
"""Capture the App Store screenshots on the iOS simulator, the twin of screenshots.py. Run via
`just screenshots-ios`.

Uses the same showcase notes as Android, written straight into the simulator app's Documents
folder: unlike a phone, the simulator's app data is a plain folder on this Mac. The app is
reinstalled first, so every run starts from the default settings. iOS can't be driven from
outside, so the UI test ScreenshotTests taps through the screens and saves each one.

The simulator is an iPhone Pro Max, whose 1320x2868 screen is the 6.9" size App Store Connect
requires (it scales them down for smaller iPhones). The status bar and the location are fixed;
the location is in Trondheim, where the showcase localities are.
"""

import json
import os
import subprocess
import time
from datetime import datetime
from pathlib import Path

from make_sample_notes import build
from screenshots import SHOWCASE

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "iosApp"
DERIVED = ROOT / "build/ios-screenshots"
OUT = ROOT / "fastlane/screenshots/no"
BUNDLE = "io.github.mortenfyhn.feltbok"
DEVICE = "iPhone 17 Pro Max"
GAMLE_BYBRO = "63.4283,10.4013"


def run(*args, **kw):
    return subprocess.run(args, check=True, **kw)


def simctl(*args):
    return run("xcrun", "simctl", *args, capture_output=True, text=True).stdout


def device_id():
    devices = json.loads(simctl("list", "devices", "available", "-j"))["devices"]
    # The newest runtime that has the device.
    for runtime in sorted(devices, reverse=True):
        for d in devices[runtime]:
            if d["name"] == DEVICE:
                return d["udid"]
    raise SystemExit(f"No {DEVICE} simulator; add one in Xcode")


def xcodebuild(action, sim, env=None):
    run(
        "xcodebuild", "-project", "Feltbok.xcodeproj", "-scheme", "Feltbok",
        "-configuration", "Debug", "-destination", f"platform=iOS Simulator,id={sim}",
        "-derivedDataPath", str(DERIVED), "-only-testing:FeltbokUITests/ScreenshotTests",
        "-quiet", action,
        cwd=IOS, env={**os.environ, **(env or {})},
    )  # fmt: skip


def seed(sim):
    simctl("uninstall", sim, BUNDLE)
    app = DERIVED / "Build/Products/Debug-iphonesimulator/Feltbok.app"
    simctl("install", sim, str(app))
    simctl("privacy", sim, "grant", "location", BUNDLE)
    docs = Path(simctl("get_app_container", sim, BUNDLE, "data").strip()) / "Documents"
    docs.mkdir(exist_ok=True)
    notes = build(int(time.time() * 1000), SHOWCASE)
    (docs / "notes.json").write_text(json.dumps(notes, ensure_ascii=False))


def main():
    sim = device_id()
    # Fails harmlessly when it's already booted.
    subprocess.run(["xcrun", "simctl", "boot", sim], check=False, capture_output=True)
    simctl("bootstatus", sim, "-b")
    run("xcodegen", "--quiet", cwd=IOS)
    # The footer shows just the release tag, like the Android set (ui/build.gradle.kts).
    xcodebuild("build-for-testing", sim, {"ORG_GRADLE_PROJECT_screenshots": "1"})
    seed(sim)
    simctl("location", sim, "set", GAMLE_BYBRO)
    OUT.mkdir(parents=True, exist_ok=True)
    noon = datetime.now().replace(hour=12, minute=0, second=0).astimezone()
    try:
        simctl(
            "status_bar", sim, "override", "--time", noon.isoformat(), "--dataNetwork", "wifi",
            "--wifiMode", "active", "--wifiBars", "3", "--cellularMode", "active",
            "--cellularBars", "4", "--batteryState", "discharging", "--batteryLevel", "100",
        )  # fmt: skip
        # TEST_RUNNER_ variables reach the UI test without the prefix.
        xcodebuild(
            "test-without-building", sim, {"TEST_RUNNER_SCREENSHOT_DIR": str(OUT)}
        )
    finally:
        simctl("status_bar", sim, "clear")
    for p in sorted(OUT.glob("*.png")):
        print(f"wrote {p.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
