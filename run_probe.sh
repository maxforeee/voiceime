#!/bin/bash
cd /workspace/voiceime
export OPENAI_API_KEY=$(cat /home/user/.config/qwen_tokenplan_api_key)
export OPENAI_BASE_URL=https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
export QWEN_CODE_SUPPRESS_YOLO_WARNING=1
/home/user/.npm-global/bin/qwen -y --auth-type openai -m qwen4-max-preview -p "Read app/src/main/java/com/voiceime/asr/SemanticCorrector.kt and app/build.gradle.kts. Report: (1) line count of SemanticCorrector.kt, (2) the CORRECT_URL value, (3) versionName and versionCode. Then run git log --oneline -2. Do NOT edit anything. Reply with the report and stop." > /tmp/qwen_probe.log 2>&1
echo "qwen exited: $?" >> /tmp/qwen_probe.log
