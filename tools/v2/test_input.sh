#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
task_out="$task_root/work/v2/input-test"
mkdir -p "$task_out"
task_jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
task_src="$task_root/module/app/src/main/java/com/focuspen/enhance/v2"
"$task_jdk/bin/javac" -d "$task_out" "$task_src/PenAction.java" "$task_src/KeyArbiter.java" "$task_src/KeyTarget.java" "$task_src/NativeShortcutGate.java" "$task_src/PenPointer.java" "$task_src/PenMotionQueue.java" "$task_src/RingLayout.java" "$task_src/RingSession.java" "$task_src/RingMotion.java" "$task_root/tools/v2/InputStateTest.java"
"$task_jdk/bin/java" -cp "$task_out" com.focuspen.enhance.v2.InputStateTest
