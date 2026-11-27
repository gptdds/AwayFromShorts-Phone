"""Pull captured device previews as binary PNGs without shell encoding conversions."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', type=Path, default=Path(os.environ.get('AFS_SDK', Path.home() / '.afs-android/sdk')) / 'platform-tools/adb.exe')
arguments = parser.parse_args()
sdk = arguments.adb
output = Path(__file__).resolve().parents[2] / 'build/compose-phase2'
output.mkdir(parents=True, exist_ok=True)
for style in ('paper', 'liquidglass', 'spatial3d', 'fluidgradient', 'style-options'):
    result = subprocess.run([str(sdk), '-s', arguments.serial, 'exec-out', 'run-as',
        'com.afs.guard', 'cat', f'files/preview-{style}.png'], capture_output=True, check=True)
    data = result.stdout
    if not data.startswith(b'\x89PNG\r\n\x1a\n') or data[-12:] != b'\x00\x00\x00\x00IEND\xaeB\x60\x82':
        raise RuntimeError(f'Incomplete preview: {style}')
    (output / f'preview-{style}.png').write_bytes(data)
    print(style, len(data), 'bytes')
