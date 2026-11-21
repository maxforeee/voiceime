# TASK M1 — Verify Doubao Streaming ASR Accuracy and Latency (VoiceIME)

## STATUS: KEY PROVIDED — EXECUTE NOW (2026-11-21)
The Doubao API key IS provided via config as `DOUBAO_API_KEY_FROM_CONFIG` (read from /home/user/.config/doubao_voice_api_key). The Qwen model is qwen4-max-preview via the Alibaba Cloud Token Plan endpoint https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1 (verified HTTP 200 on 2026-11-21). Execute M1 now. Do NOT commit or push anything.

## GOAL (the only success criterion)
Prove, with measured numbers, that Volcano Engine Doubao streaming speech recognition is accurate enough and fast enough for a voice input method: record Mandarin speech, stream it to Doubao ASR, and report recognition accuracy and latency in `m1_report.md`.

## OUTPUT-FIRST RULE
Make your first code edit within ~15 tool calls. Reading without writing is not progress. Long silent thinking with no visible action is failure. Write the verification harness first, then iterate.

## Scope — M1 ONLY
- Verify Doubao streaming ASR (accuracy + latency). Nothing else.
- Do NOT build the input method, keyboard, UI, or IME shell (that is M2).
- Do NOT touch RoutePass, LyricSync, or any other project on this Box.
- Do NOT commit or push anything to GitHub.

## Verified API facts (from the project spec, 2026-11-21)
- Product: Volcano Engine "Doubao Voice" (豆包语音), same Volcano account as the user's Mika realtime voice.
- Streaming recognition (use this): WebSocket `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` — recommended bidirectional streaming optimized version. Alternatives: `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel`, `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream`.
- Model resource ID — use 2.0: `volc.seedasr.sauc.duration` (hourly version). Do NOT use 1.0 (`volc.bigasr.sauc.*`).
- Auth: new console needs only one `X-Api-Key` request header.
- Official Android SDK exists: SpeechEngine (ASR engine) — prefer it over hand-rolling the WebSocket binary protocol.
- Audio: 16 kHz, 16-bit, mono PCM, streamed.
- File-recognition endpoint (`POST .../api/v3/auc/bigmodel/submit`, resource `volc.seedasr.auc`) is NOT for this task — it is submit-then-query, not streaming.
- Privacy: recognition runs on ByteDance servers, not on-device. Speech leaves the device.

## Key handling (STRICT)
- NEVER write a real API key into any file, log, or chat output.
- Read the key ONLY from config / environment as `DOUBAO_API_KEY_FROM_CONFIG`.
- If the key is missing/empty, STOP and report "key not provided" — do not guess, do not reuse any other key on this Box, do not proceed with a fake key.
- The key must later also be configured into Qwen Code per the user's standing rule; that is not part of M1.

## What to build (minimal verification harness)
1. A small standalone harness (keep it tiny — one directory, e.g. `m1_verify/`):
   - Record or reuse a short Mandarin test recording (normal speed, everyday sentences; include the exact test sentences in the report).
   - Stream the 16 kHz 16-bit mono PCM to Doubao streaming ASR (via SpeechEngine SDK if it integrates cleanly, otherwise the recommended WebSocket endpoint).
   - Capture interim results AND final results with timestamps.
2. Measure and report:
   - Recognition accuracy: word/character error rate on the known test sentences, plus any misrecognized words listed verbatim.
   - Latency: time from end of speech to final result, and delay of interim results while speaking (does the interim text keep up with speech?).
   - Pass/fail vs. the bar: everyday sentences basically correct, streaming output without obvious lag.
3. Write `m1_report.md` in the repo root with: test sentences, accuracy numbers, latency numbers, raw interim/final transcripts, and a clear PASS / FAIL verdict for M1.
4. If accuracy or latency fails the bar, STOP after the report — do not start M2 work. Report the numbers and the likely cause.

## Constraints
- One goal, one chunk: M1 only. Do not expand scope.
- Keep the harness small and deletable; it is a verification tool, not the product.
- No ads, no extra features, no keyboard code in M1.
- Fast, simple, responsive is the product bar — the harness must measure latency, not hide it.

## Done means
- `m1_verify/` harness exists and runs against the real API with the provided key.
- `m1_report.md` exists with measured accuracy + latency and a PASS/FAIL verdict.
- No key anywhere in files/logs. Nothing committed or pushed.
