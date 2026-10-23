#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
task_out="$task_root/work/v2/hyperos-test"
mkdir -p "$task_out"
javac -d "$task_out" "$task_root/module/app/src/main/java/com/focuspen/enhance/v2/HyperOsCompatibility.java" "$task_root/tools/v2/HyperOsCompatibilityTest.java"
java -cp "$task_out" com.focuspen.enhance.v2.HyperOsCompatibilityTest
