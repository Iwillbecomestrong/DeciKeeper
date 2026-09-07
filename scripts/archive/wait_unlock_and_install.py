import subprocess
import time
import sys

dev = "adb-10CF290X6R001BU-hpdOFw (2)._adb-tls-connect._tcp"
print("Waiting for phone unlock (checking every 2s, max 180s)...", flush=True)

for i in range(90):
    res = subprocess.run(
        ["adb", "-s", dev, "shell", "dumpsys", "trust"],
        capture_output=True,
        text=True,
        errors="ignore"
    )
    # Check if id=0 (机主) has deviceLocked=0
    for line in res.stdout.splitlines():
        if "id=0" in line and "deviceLocked=0" in line:
            print(f"Device unlocked detected at turn {i+1}! Triggering pm install...", flush=True)
            install_res = subprocess.run(
                ["adb", "-s", dev, "shell", "pm", "install", "-r", "-t", "-d", "-g", "--user", "0", "/data/local/tmp/app-release.apk"],
                capture_output=True,
                text=True,
                errors="ignore"
            )
            print("PM INSTALL STDOUT:\n", install_res.stdout, flush=True)
            print("PM INSTALL STDERR:\n", install_res.stderr, flush=True)
            if "Success" in install_res.stdout:
                print("INSTALLATION SUCCESSFUL!", flush=True)
                sys.exit(0)
            else:
                sys.exit(1)
    time.sleep(2)

print("Timed out waiting for phone unlock.", flush=True)
sys.exit(2)
