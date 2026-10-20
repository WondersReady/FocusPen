#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
task_out="$task_root/work/v2/config-test"
mkdir -p "$task_out"
task_jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
"$task_jdk/bin/javac" -d "$task_out" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/ConfigSpec.java" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/ProBindings.java" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/AppProfiles.java" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/DirectionAdapter.java" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/RingContent.java" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/PenAction.java" "$task_root/tools/v2/ConfigSpecTest.java"
"$task_jdk/bin/java" -cp "$task_out" com.focuspen.enhance.v2.ConfigSpecTest
