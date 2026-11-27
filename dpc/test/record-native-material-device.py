"""Record actual device frames with wall-clock timing when screenrecord is unavailable."""
import argparse
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--ffmpeg", required=True)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
adb = [args.adb, "-s", args.serial]
subprocess.run(adb + ["shell", "am", "start", "-n", "com.afs.guard/.CheckinActivity"],
               check=True, capture_output=True, timeout=15)
test_command = adb + [
    "shell", "am", "instrument", "-w", "-r", "-e", "class",
    "com.afs.guard.NativeMaterialIntegrationTest,com.afs.guard.MemoStyleStateTest",
    "com.afs.guard.test/androidx.test.runner.AndroidJUnitRunner",
]
samples = []
started = time.monotonic()
with (args.output / "device-tests.log").open("wb") as log:
    test = subprocess.Popen(test_command, stdout=log, stderr=subprocess.STDOUT)
    try:
        while test.poll() is None:
            if time.monotonic() - started > 100:
                raise TimeoutError("Device tests exceeded the recording deadline")
            before = time.monotonic()
            frame = subprocess.run(adb + ["exec-out", "screencap", "-p"],
                                   capture_output=True, timeout=10, check=True)
            after = time.monotonic()
            if not frame.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("Device did not return a PNG frame")
            name = f"frame-{len(samples):04d}.png"
            (args.output / name).write_bytes(frame.stdout)
            samples.append({"file": name, "seconds": (before + after) / 2 - started,
                            "capture_seconds": after - before})
    finally:
        if test.poll() is None:
            test.terminate()
            test.wait(timeout=5)
        subprocess.run(adb + ["shell", "am", "start", "-n", "com.afs.guard/.CheckinActivity"],
                       capture_output=True, timeout=15)
elapsed = time.monotonic() - started
if len(samples) < 2:
    raise RuntimeError("Insufficient device frames")
(args.output / "timestamps.json").write_text(json.dumps(samples, indent=2), encoding="utf-8")
lines = ["ffconcat version 1.0"]
for index, sample in enumerate(samples):
    duration = (samples[index + 1]["seconds"] - sample["seconds"]
                if index + 1 < len(samples) else sample["capture_seconds"])
    lines.extend([f"file '{sample['file']}'", f"duration {max(duration, 0.001):.6f}"])
lines.append(f"file '{samples[-1]['file']}'")
manifest = args.output / "frames.ffconcat"
manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
video = args.output / "native-business-pages-live.mp4"
subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "concat",
                "-safe", "0", "-i", str(manifest), "-vf", "scale=720:-2",
                "-fps_mode", "vfr", "-c:v", "libx264", "-crf", "19",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", str(video)],
               check=True, timeout=90)
test_output = (args.output / "device-tests.log").read_text(encoding="utf-8", errors="replace")
report = {
    "method": "Continuous ADB screenshots, actual elapsed frame timing; no interpolated frames",
    "frames": len(samples),
    "capture_span_seconds": samples[-1]["seconds"] - samples[0]["seconds"],
    "average_capture_fps": (len(samples) - 1) / (samples[-1]["seconds"] - samples[0]["seconds"]),
    "tests_passed": "OK (2 tests)" in test_output,
    "video": str(video.resolve()),
}
(args.output / "recording.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
if not report["tests_passed"]:
    raise SystemExit("Device tests did not pass; inspect device-tests.log")
