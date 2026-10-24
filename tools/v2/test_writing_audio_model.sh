#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
task_out="$task_root/work/v2/writing-audio-model-test"
task_src="$task_root/module/app/src/main/java/com/focuspen/enhance/v2"
task_jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
mkdir -p "$task_out"
"$task_jdk/bin/javac" --release 17 -d "$task_out" \
    "$task_src/WritingAudioModel.java" \
    "$task_src/WritingAudioEventQueue.java" \
    "$task_src/WritingAudioSynthesizer.java" \
    "$task_root/tools/v2/WritingAudioModelTest.java" \
    "$task_root/tools/v2/WritingAudioSynthesizerTest.java"
"$task_jdk/bin/java" -cp "$task_out" com.focuspen.enhance.v2.WritingAudioModelTest
"$task_jdk/bin/java" -cp "$task_out" com.focuspen.enhance.v2.WritingAudioSynthesizerTest
