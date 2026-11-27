import argparse
import base64
from pathlib import Path
import re
import json
import subprocess

p = argparse.ArgumentParser()
p.add_argument('log', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--ffmpeg')
a = p.parse_args()
raw = a.log.read_bytes()
text = raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
frames = {}
fields = {}
for line in text.splitlines():
    marker = 'INSTRUMENTATION_STATUS: capture_'
    marker_at = line.find(marker)
    if marker_at >= 0:
        key, value = line[marker_at + len('INSTRUMENTATION_STATUS: '):].split('=', 1)
        fields[key] = value
    if 'INSTRUMENTATION_STATUS_CODE:' in line and 'capture_data' in fields:
        name = fields['capture_name']
        if not re.fullmatch(r'native-(liquidglass|fluidgradient)-(SettingsActivity|FreezeBoardActivity|CheckinActivity|MemoActivity).jpg', name) and not re.fullmatch(r'native-motion-(liquidglass|fluidgradient)-\d{4}.jpg', name):
            raise ValueError('Unexpected capture name')
        frame = frames.setdefault(name, {})
        frame[int(fields['capture_part'])] = fields['capture_data']
        frame['count'] = int(fields['capture_parts'])
        if 'capture_timestamp' in fields:
            frame['timestamp'] = int(fields['capture_timestamp'])
        fields = {}
a.output.mkdir(parents=True, exist_ok=True)
for name, frame in frames.items():
    payload = ''.join(frame[index] for index in range(frame['count']))
    (a.output / name).write_bytes(base64.b64decode(payload, validate=True))
print(f'Exported {len(frames)} native page captures')
for style in ('liquidglass', 'fluidgradient'):
    motion = sorted((name, frame) for name, frame in frames.items() if name.startswith(f'native-motion-{style}-'))
    if not motion:
        continue
    if len(motion) != 100:
        raise ValueError(f'Incomplete motion capture for {style}: {len(motion)} / 100')
    lines = ['ffconcat version 1.0']
    durations = []
    for index, (name, frame) in enumerate(motion):
        duration = ((motion[index + 1][1]['timestamp'] - frame['timestamp']) / 1000 if index + 1 < len(motion) else .08)
        if duration <= 0:
            raise ValueError('Non-monotonic capture timestamp')
        durations.append(duration)
        lines += [f"file '{name}'", f'duration {duration:.6f}']
    lines.append(f"file '{motion[-1][0]}'")
    manifest = a.output / f'{style}-motion.ffconcat'
    manifest.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    report = {'frames': len(motion), 'seconds': sum(durations), 'average_fps': (len(motion)-1)/sum(durations[:-1]),
              'method': 'PixelCopy actual phone window; original timestamps; no interpolated frames',
              'gestures': {'down_frame': 12, 'drag_frames': [28, 55], 'cancel_frame': 56}}
    (a.output / f'{style}-motion.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    if a.ffmpeg:
        video = a.output / f'{style}-native-motion.mp4'
        subprocess.run([a.ffmpeg, '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0',
                        '-i', str(manifest), '-fps_mode', 'vfr', '-c:v', 'libx264', '-crf', '19',
                        '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-y', str(video)], check=True)
        print(f'{video}: {report["average_fps"]:.1f} captured fps, {report["seconds"]:.2f}s')
