#!/usr/bin/env python3
"""Reuse the existing headless AVD for text-edit runtime verification."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import time

SDK = Path("/home/mikasa910x/.buildozer/android/platform/android-sdk")
AVD_HOME = Path("/tmp/pdf-editor-ez-emulator/avd")
AVD_NAME = "pdf_editor_ez_api36_16kb"
SERIAL = "emulator-5580"
EVIDENCE = Path(__file__).resolve().parent
ADB = str(SDK / "platform-tools/adb")


def adb(*arguments):
    result = subprocess.run([ADB, "-s", SERIAL, *arguments], capture_output=True, text=True, timeout=8)
    return result.stdout.strip() if result.returncode == 0 else ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe-only", action="store_true")
    options = parser.parse_args()
    if not options.probe_only and adb("get-state") != "device":
        if not (AVD_HOME / (AVD_NAME + ".avd")).is_dir():
            raise RuntimeError("The existing AVD is missing; refusing to create or wipe device data.")
        with socket.socket() as probe:
            probe.settimeout(1)
            if probe.connect_ex(("127.0.0.1", 5580)) == 0:
                raise RuntimeError("Port 5580 is occupied. Refusing to start another emulator.")
        command = [str(SDK / "emulator/emulator"), "-avd", AVD_NAME,
                   "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-no-metrics",
                   "-gpu", "software", "-accel", "on", "-memory", "2048", "-cores", "2", "-port", "5580"]
        environment = os.environ.copy()
        environment["ANDROID_AVD_HOME"] = str(AVD_HOME)
        environment["ANDROID_HOME"] = str(SDK)
        environment["ANDROID_SDK_ROOT"] = str(SDK)
        with (EVIDENCE / "text-edit-emulator.log").open("ab") as log:
            process = subprocess.Popen(command, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        record = {"pid": process.pid, "started_utc": datetime.now(timezone.utc).isoformat(),
                  "command": command, "avd_home": str(AVD_HOME), "serial": SERIAL,
                  "reused_existing_data": True, "host_privilege_reason": "KVM device access only"}
        (EVIDENCE / "launch-text-edit.json").write_text(json.dumps(record, indent=2) + "\n")
        print(json.dumps({"started_pid": process.pid, "serial": SERIAL}), flush=True)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if adb("shell", "getprop", "sys.boot_completed") == "1":
            record = {"serial": SERIAL, "checked_utc": datetime.now(timezone.utc).isoformat(),
                      "boot_completed": "1", "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
                      "android": adb("shell", "getprop", "ro.build.version.release"),
                      "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                      "page_size": adb("shell", "getconf", "PAGE_SIZE"),
                      "screen": adb("shell", "wm", "size"), "density": adb("shell", "wm", "density")}
            if record["sdk"] != "36" or record["page_size"] != "16384":
                raise RuntimeError("Unexpected emulator environment: " + json.dumps(record))
            (EVIDENCE / "environment-text-edit.json").write_text(json.dumps(record, indent=2) + "\n")
            print(json.dumps(record, indent=2), flush=True)
            return
        time.sleep(2)
    print("Emulator is still booting. Re-run with --probe-only to continue checking.", flush=True)


if __name__ == "__main__":
    main()
