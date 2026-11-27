#!/usr/bin/env bash
# 手工构建 AFS 守卫（无障碍版）—— 不依赖 Gradle，只用 SDK 里的 aapt2 / d8 / apksigner。
# 用法（Git Bash）：  bash build.sh
set -euo pipefail

cd "$(dirname "$0")"

# ---------- 环境（自动探测，别人的机器也能直接跑）----------
find_sdk() {
  local c
  for c in "${AFS_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" \
           "$HOME/Library/Android/sdk" "/usr/lib/android-sdk"; do
    [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return 0; }
  done
  return 0
}
SDK="$(find_sdk || true)"
if [ -z "$SDK" ]; then
  echo "找不到 Android SDK。装一个，或设 AFS_SDK=/你的/sdk（也可用 ANDROID_HOME）。" >&2
  echo "只需要 build-tools 34 和 platforms/android-34。" >&2
  exit 1
fi

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT="${BT%/}"
[ -n "$BT" ] || { echo "找不到 build-tools：$SDK/build-tools" >&2; exit 1; }

ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 android.jar：$ANDROID_JAR" >&2; exit 1; }

# Windows 上是 aapt2.exe / d8.bat / apksigner.bat，Unix 上无后缀
tool() {
  local e
  for e in .exe .bat ""; do
    [ -f "$BT/$1$e" ] && { echo "$BT/$1$e"; return 0; }
  done
  echo "$BT/$1"
}
AAPT2="$(tool aapt2)"
D8="$(tool d8)"
ZIPALIGN="$(tool zipalign)"
APKSIGNER="$(tool apksigner)"

# Windows 上 python3/python 常指向应用商店占位程序（...\WindowsApps\python.exe），
# 执行即以 49 退出、什么都不干，会让打包步骤静默失败。逐个候选挑真能跑的解释器。
pick_python() {
  for cand in ${AFS_PYTHON:-} "$(command -v python || true)" "$(command -v python3 || true)" \
              "$HOME"/.workbuddy/binaries/python/versions/*/python; do
    [ -n "$cand" ] || continue
    case "$cand" in *WindowsApps/python*) continue ;; esac
    [ -x "$cand" ] || continue
    printf '%s' "$cand"
    return 0
  done
  return 1
}

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
  export JAVA_HOME
fi
JAR="$JAVA_HOME/bin/jar.exe"
[ -f "$JAR" ] || JAR="$JAVA_HOME/bin/jar"

OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex"

# Keep the accessibility package aligned with the Device Owner build.
# Both values can be overridden by the release pipeline.
VERSION_CODE="${AFS_VERSION_CODE:-2417}"
VERSION_NAME="${AFS_VERSION_NAME:-1.91.92}"

# ---------- 1. 编译资源 ----------
echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/flat"

# ---------- 2. 链接资源 ----------
echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$OUT"/flat/*.flat

# ---------- 3. 编译 Java ----------
echo "[3/6] javac"
LIBS="$(ls libs/*.jar | tr '\n' ';' | sed 's/;$//')"
CP="$ANDROID_JAR;$LIBS"
find java ../core/rules/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -encoding UTF-8 -nowarn --release 8 \
  -classpath "$CP" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  grep -av '^warning:' "$OUT/javac.log" >&2 || true
  rm -rf "$OUT/classes"
  echo "javac 失败，停止构建。" >&2
  exit 1
fi
grep -av '^warning:' "$OUT/javac.log" || true

# ---------- 4. dex ----------
echo "[4/6] d8"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" \
  "$OUT/classes.jar" libs/*.jar

# ---------- 5. 打包 + 对齐 ----------
echo "[5/6] zip + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
if command -v zip >/dev/null 2>&1; then
  ( cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex )
else
  PY="$(pick_python || true)"
  if [ -z "$PY" ] || [ ! -x "$PY" ]; then
    echo "没有 zip 也没有可用的 python，无法把 classes.dex 塞进 APK。" >&2
    echo "装 python 或用 AFS_PYTHON=/path/to/python 指定。" >&2
    exit 1
  fi
  "$PY" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PYEOF
fi
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------- 6. 签名 ----------
echo "[6/6] apksigner"
# 与 DO 版共用同一把 key，方便统一管理
KS="../dpc/keystore/afs.keystore"
if [ ! -f "$KS" ]; then
  echo "找不到签名库 $KS" >&2
  exit 1
fi
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass pass:afsguard --key-pass pass:afsguard \
  --min-sdk-version 24 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/afs-blocker.apk" "$OUT/aligned.apk"

"$APKSIGNER" verify --min-sdk-version 24 "$OUT/afs-blocker.apk"
rm -f "$OUT/afs-blocker.apk.idsig"
cp -f "$OUT/afs-blocker.apk" ../afs-blocker.apk

echo
echo "完成："
ls -l ../afs-blocker.apk
