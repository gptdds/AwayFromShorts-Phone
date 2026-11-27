import argparse
import io
from pathlib import Path
import re
import subprocess
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--ffmpeg', required=True)
parser.add_argument('--output', type=Path, required=True)
arguments = parser.parse_args()
arguments.output.mkdir(parents=True, exist_ok=True)
for style in ('liquidglass', 'fluidgradient'):
    folder = arguments.output / style
    folder.mkdir(exist_ok=True)
    archive = subprocess.check_output([arguments.adb, '-s', arguments.serial, 'exec-out', 'run-as',
                                      'com.afs.guard.universe', 'tar', '-cf', '-', '-C', 'files', f'capture-{style}'])
    with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
        for entry in bundle.getmembers():
            name = Path(entry.name).name
            if entry.isfile() and re.fullmatch(r'(frame-\d{3}\.jpg|timestamps\.csv)', name):
                with bundle.extractfile(entry) as source:
                    (folder / name).write_bytes(source.read())
    samples = [(name, int(timestamp)) for name, timestamp in
               (line.split(',') for line in (folder / 'timestamps.csv').read_text().splitlines())]
    lines = []
    for index, (name, timestamp) in enumerate(samples):
        if not re.fullmatch(r'frame-\d{3}\.jpg', name):
            raise ValueError('Unexpected frame name')
        duration = (samples[index + 1][1] - timestamp) / 1000 if index + 1 < len(samples) else .15
        lines.extend([f"file '{name}'", f'duration {max(duration, .001):.6f}'])
    lines.append(f"file '{samples[-1][0]}'")
    manifest = folder / 'frames.ffconcat'
    manifest.write_text('\n'.join(lines), encoding='utf-8')
    video = arguments.output / f'{style}-window-recording.mp4'
    subprocess.run([arguments.ffmpeg, '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0',
                    '-i', str(manifest), '-vf', 'scale=720:-2', '-fps_mode', 'vfr', '-c:v', 'libx264',
                    '-crf', '19', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-y', str(video)], check=True)
    print(f'{style}: {len(samples)} actual window frames, {(samples[-1][1] - samples[0][1]) / 1000:.2f}s, {video}')
