#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
task_out="$task_root/work/v2/mouse-test"
task_src="$task_root/module/app/src/main/java/com/focuspen/enhance/v2"
mkdir -p "$task_out"
task_jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
"$task_jdk/bin/javac" -d "$task_out" "$task_src/MouseButtons.java" "$task_src/PinchSequence.java" "$task_src/ProBindings.java" "$task_src/RingContent.java" "$task_root/tools/v2/MousePinchTest.java"
"$task_jdk/bin/java" -cp "$task_out" com.focuspen.enhance.v2.MousePinchTest
