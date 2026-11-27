"""Export actual component-lab captures without PowerShell binary redirection."""
import argparse
import io
from pathlib import Path
import re
import subprocess
import tarfile

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.afs.guard.universe')
p.add_argument('--ffmpeg')
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
data = subprocess.check_output([a.adb, '-s', a.serial, 'exec-out', 'run-as', a.package, 'tar', '-cf', '-', '-C', 'files', '.'])
with tarfile.open(fileobj=io.BytesIO(data)) as tar:
    for entry in tar.getmembers():
        path = Path(entry.name)
        if not entry.isfile():
            continue
        if re.fullmatch(r'components-(glass|fluid)-(actions|input|list|dialog)-static.png', path.name) or re.fullmatch(r'native-(liquidglass|fluidgradient)-(SettingsActivity|FreezeBoardActivity|CheckinActivity|MemoActivity).png', path.name):
            destination = a.output / path.name
        elif re.fullmatch(r'capture-components-(liquidglass|fluidgradient)', path.parent.name) and re.fullmatch(r'(frame-\d{3}.jpg|timestamps.csv)', path.name):
            destination = a.output / path.parent.name / path.name
        else:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(tar.extractfile(entry).read())
if a.ffmpeg:
    for style in ('liquidglass', 'fluidgradient'):
        folder = a.output / f'capture-components-{style}'
        samples = [line.split(',') for line in (folder / 'timestamps.csv').read_text().splitlines()]
        lines = []
        for i, (name, stamp) in enumerate(samples):
            if not re.fullmatch(r'frame-\d{3}.jpg', name):
                raise ValueError(name)
            seconds = (int(samples[i + 1][1]) - int(stamp)) / 1000 if i + 1 < len(samples) else .15
            lines += [f"file '{name}'", f'duration {max(seconds, .001):.6f}']
        lines.append(f"file '{samples[-1][0]}'")
        manifest = folder / 'frames.ffconcat'
        manifest.write_text('\n'.join(lines), encoding='utf-8')
        video = a.output / f'{style}-components-phone.mp4'
        subprocess.run([a.ffmpeg, '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', str(manifest), '-vf', 'scale=720:-2', '-fps_mode', 'vfr', '-c:v', 'libx264', '-crf', '19', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-y', str(video)], check=True)
        print(video)
