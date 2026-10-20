#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
task_out="$task_root/work/v2/pro-test"
mkdir -p "$task_out"
task_jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
task_src="$task_root/module/app/src/main/java/com/focuspen/enhance/v2"
"$task_jdk/bin/javac" -d "$task_out" "$task_src/ProGestureGate.java" "$task_src/PenAction.java" "$task_src/KeyArbiter.java" "$task_root/tools/v2/ProGestureTest.java"
"$task_jdk/bin/java" -cp "$task_out" com.focuspen.enhance.v2.ProGestureTest
