#!/bin/bash
cd /workspace/voiceime
export OPENAI_API_KEY=$(cat /home/user/.config/qwen_tokenplan_api_key)
export OPENAI_BASE_URL=https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
setsid /home/user/.npm-global/bin/qwen -y --auth-type openai -m qwen4-max-preview -p "$(cat task_v11_hotfix.md)" > /workspace/voiceime/v11_hotfix_qwen.log 2>&1 < /dev/null &
echo "launched pid $!"
