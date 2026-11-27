#!/usr/bin/env bash
# 离线回归：不连手机、不需要 Android 运行时。
# 用法（Git Bash）：  bash test/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."

find_sdk() {
  local c
  for c in "${AFS_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "D:/afs-android/sdk" \
           "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" \
           "$HOME/Library/Android/sdk" "/usr/lib/android-sdk"; do
    [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return 0; }
  done
  return 0
}
SDK="$(find_sdk || true)"
if [ -z "$SDK" ]; then
  echo "找不到 Android SDK。设 AFS_SDK=/你的/sdk（也可用 ANDROID_HOME）。" >&2
  exit 1
fi
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
  export JAVA_HOME
fi
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
[ -f "$JAVAC" ] || JAVAC=javac
[ -f "$JAVA" ] || JAVA=java

SEP=";"
case "$(uname -s)" in
  Linux|Darwin) SEP=":" ;;
esac

OUT="build/test"
rm -rf "$OUT"
mkdir -p "$OUT"
# Guard 是纯 Java，可以离线跑；其余类只要编译得过（android.jar 当 classpath）
# ShizukuBridge / BlockerService 引用了 rikka.shizuku.*，少了 libs 里的 jar 就编不过；
# 以前这里漏了，javac 的报错又被下面 grep 吞掉，整套回归其实一直在空转。
LIBS="$(ls libs/*.jar | tr '\n' "$SEP" | sed "s/${SEP}\$//")"
CP="$ANDROID_JAR${SEP}$LIBS"
find java test/java ../core/rules/src/main/java ../core/rules/src/test/java -name '*.java' > "$OUT/sources.txt"
if ! "$JAVAC" -encoding UTF-8 -nowarn --release 8 \
  -classpath "$CP" \
  -d "$OUT" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log" >&2
  echo "javac 失败，停止回归。" >&2
  exit 1
fi
grep -avE '^(warning|注意|Note)' "$OUT/javac.log" || true

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$ANDROID_JAR${SEP}$LIBS${SEP}$OUT" com.afs.blocker.GuardTest

# 共享规则核心与 Guard 的对拍：共享核心换实现时，这一条必须仍然逐分钟一致。
"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$ANDROID_JAR${SEP}$LIBS${SEP}$OUT" com.afs.blocker.GuardParityTest

JSON_JAR="../dpc/test/libs/json-20240303.jar"
[ -f "$JSON_JAR" ] || { echo "Missing test JSON runtime: $JSON_JAR" >&2; exit 1; }
for TEST in RuleEngineTest RuleMigrationTest RuleBackupTest; do
  "$JAVA" -Dfile.encoding=UTF-8 -cp "$JSON_JAR${SEP}$ANDROID_JAR${SEP}$OUT" "com.afs.core.rules.$TEST"
done

"$JAVA" -Dfile.encoding=UTF-8 -cp "$JSON_JAR${SEP}$ANDROID_JAR${SEP}$OUT" com.afs.blocker.GuardScheduleTest
