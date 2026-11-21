# M1 Report — Doubao Streaming ASR Accuracy & Latency (VoiceIME)

**Date:** 2026-11-21
**Verdict: PASS** ✅

Everyday Mandarin sentences were recognized with **0% character error rate (0/102 chars)**,
final results arrived **0.30–1.05 s after end of audio** (median 0.37 s), and interim text
kept up with speech (median lag 0.65 s behind the spoken audio, updates every ~0.3–0.45 s).
This meets the M1 bar: *everyday sentences basically correct, streaming output without obvious lag.*

---

## 1. Setup

| Item | Value |
|---|---|
| Endpoint | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` (bidirectional streaming, optimized) |
| Resource ID | `volc.seedasr.sauc.duration` (2.0 hourly, header `X-Api-Resource-Id`) |
| Auth | single `X-Api-Key` header, read from env `DOUBAO_API_KEY_FROM_CONFIG` (never written to any file/log) |
| Model | `bigmodel`, `enable_punc=true`, `enable_itn=false` (ITN off so output stays character-comparable with references), `show_utterances=true`, `result_type=full` |
| Audio | 16 kHz, 16-bit, mono PCM; streamed in 100 ms chunks at real-time pace |
| Protocol | Volcano v3 binary WebSocket protocol (full client request → audio-only packets, last packet negative-sequence); implemented directly in Python (`websockets` 17.4) — the Android SpeechEngine SDK does not apply to this Linux verification box |
| Network | all traffic via the box's local HTTP proxy (127.0.0.1:3128); latency numbers include proxy + network + server time as seen from this box |
| Harness | `m1_verify/` — `gen_audio.py` (TTS → WAV), `run_m1.py` (stream + measure), `sentences.json`, `results.json` (full raw events) |

**Test audio source (disclosure):** this box has no microphone. Test recordings were
synthesized with neural TTS (edge-tts, voice `zh-CN-XiaoxiaoNeural`, normal speed `+0%`),
converted to 16 kHz mono PCM, with leading/trailing silence energy-trimmed (0.1 s lead,
0.25 s tail kept). This is clean, standard-pronunciation audio — real microphone input
with noise/accent may score worse; see Caveats.

## 2. Test sentences and accuracy

CER = edit distance / reference length after stripping punctuation and whitespace.
Hypothesis = final cumulative transcript returned by the server.

| # | Reference (exact test sentence) | Final hypothesis | CER |
|---|---|---|---|
| 1 | 今天天气怎么样，我们下午去公园散步吧。 | 今天天气怎么样？我们下午去公园散步吧。 | 0.00% |
| 2 | 请帮我打开客厅的空调，温度调到二十六度。 | 请帮我打开客厅的空调，温度调到二十六度。 | 0.00% |
| 3 | 我明天早上九点要开会，记得提前提醒我。 | 我明天早上九点要开会，记得提前提醒我。 | 0.00% |
| 4 | 这家餐厅的红烧肉特别好吃，下次我们还来。 | 这家餐厅的红烧肉特别好吃，下次我们还来。 | 0.00% |
| 5 | 麻烦把刚才那段话翻译成英文发给小王。 | 麻烦把刚才那段话翻译成英文发给小王。 | 0.00% |
| 6 | 周末我想去超市买一些水果和牛奶。 | 周末我想去超市买一些水果和牛奶。 | 0.00% |

**Overall CER: 0.00% (0 edits / 102 reference chars, 6/6 sentences).**
**Misrecognized words: none.** (Only punctuation differs on sentence 1: `，` → `？`, which is
excluded from CER and is a reasonable segmentation of the question.)

## 3. Latency

Definitions:
- **Tail latency** = wall time from sending the last audio byte to receiving the final
  server response. The WAVs keep a 0.25 s silent tail, so *true end-of-speech → final*
  ≈ tail latency + 0.25 s (worst case ≈ 1.3 s).
- **Interim lag** = receive time of an interim result minus the wall-clock moment the
  audio it covers was streamed (stream start + utterance `end_time`). Positive = behind speech.

| # | Audio dur (s) | Final at (s) | Tail latency (s) | Interim updates | Interim lag median (s) | Interim lag max (s) |
|---|---|---|---|---|---|---|
| 1 | 3.58 | 5.02 | 0.345 | 8 | 0.603 | 0.726 |
| 2 | 3.95 | 5.45 | 0.535 | 9 | 0.547 | 0.773 |
| 3 | 3.74 | 5.28 | 0.303 | 10 | 0.652 | 0.763 |
| 4 | 3.98 | 6.06 | 1.051 | 9 | 1.154 | 1.562 |
| 5 | 3.80 | 5.26 | 0.380 | 11 | 0.684 | 1.133 |
| 6 | 3.27 | 4.79 | 0.352 | 9 | 0.549 | 0.853 |

- Tail latency: **mean 0.494 s, median 0.366 s, max 1.051 s** (sentence 4; ≈1.3 s from true speech end).
- Interim lag over all 56 interim results: **mean 0.714 s, median 0.653 s, max 1.562 s**.
- Interim text arrives every ~0.3–0.45 s while speaking and grows monotonically with speech —
  **the interim output keeps up; no obvious lag.** Worst case (sentence 4) ran ~1.1–1.6 s
  behind the audio mid-sentence, still acceptable for an IME candidate bar.

## 4. Raw transcripts (interim + final)

Full timestamped event log (every server response, incl. utterance start/end times and
definite flags): `m1_verify/results.json` → `raw_events`.

Sentence 1 interim progression (t = seconds since connect; lag vs. spoken audio):

```
t=1.95s lag=+0.55s | 今天
t=2.40s lag=+0.60s | 今天天气
t=2.65s lag=+0.61s | 今天天气怎么样
t=3.55s lag=+0.71s | 今天天气怎么样？我们
t=3.81s lag=+0.73s | 今天天气怎么样？我们下午
t=4.23s lag=+0.67s | 今天天气怎么样？我们下午去
t=4.65s lag=+0.52s | 今天天气怎么样？我们下午去公园散步
t=4.93s lag=+0.56s | 今天天气怎么样？我们下午去公园散步吧
FINAL t=5.02s      | 今天天气怎么样？我们下午去公园散步吧。
```

Sentence 4 (worst-case lag) interim progression:

```
t=2.79s lag=+0.97s | 这家餐厅
t=3.20s lag=+1.23s | 这家餐厅的
t=3.60s lag=+1.14s | 这家餐厅的红烧肉
t=3.93s lag=+1.16s | 这家餐厅的红烧肉特别
t=4.37s lag=+1.27s | 这家餐厅的红烧肉特别好吃
t=4.66s lag=+1.56s | 这家餐厅的红烧肉特别好吃
t=5.13s lag=+1.15s | 这家餐厅的红烧肉特别好吃，下次
t=5.40s lag=+1.11s | 这家餐厅的红烧肉特别好吃，下次我们
t=5.75s lag=+1.05s | 这家餐厅的红烧肉特别好吃，下次我们还来
FINAL t=6.06s      | 这家餐厅的红烧肉特别好吃，下次我们还来。
```

Final transcripts for all six sentences are in the table in §2 (verbatim server output).

## 5. Pass/fail vs. the bar

| Criterion | Bar | Measured | Result |
|---|---|---|---|
| Everyday sentences basically correct | low error rate | CER 0.00% (0/102), no misrecognized words | PASS |
| Streaming output without obvious lag | interim keeps up, fast final | interim every ~0.3–0.45 s, median lag 0.65 s; final median 0.37 s after audio end (≈0.62 s after true speech end) | PASS |

**M1 verdict: PASS.** Doubao streaming ASR (`bigmodel_async`, seedasr 2.0) is accurate
enough and fast enough for a voice input method on this test set.

## 6. Caveats and notes

1. **TTS, not live mic.** Audio is clean neural-TTS Mandarin; no background noise, accent,
   or mic-channel effects. Real-world CER will likely be higher; re-verify with human
   recordings when a mic is available (M2 device testing).
2. **Small sample.** 6 sentences / 102 chars, single run each. 0% CER here is strong
   evidence but not a statistical guarantee.
3. **ITN disabled** for measurement fairness (numbers stay as 汉字, e.g. 二十六度).
   With `enable_itn=true` the product would emit "26度"; accuracy of the underlying
   recognition is unaffected.
4. **Latency includes this box's proxy/network path.** On-device (Android, direct network)
   numbers may differ; the harness measures honestly, nothing is hidden or pre-buffered.
5. **Harness bug found & fixed during verification (transparency):** the first run used
   ffmpeg `silenceremove`, which truncated audio at the first internal pause and produced
   artificially high CER (30.4%). The truncation was in the harness, not the ASR — the
   server correctly transcribed exactly the audio it received. Fixed with energy-based
   trimming; the numbers above are from the corrected full-length audio.
6. Privacy: recognition runs on ByteDance servers; speech leaves the device (per spec).

## 7. Reproduce

```bash
python3 m1_verify/gen_audio.py   # TTS -> 16 kHz mono WAV (needs edge-tts, ffmpeg, proxy env)
python3 m1_verify/run_m1.py      # streams to Doubao, writes m1_verify/results.json
```

Requires env `DOUBAO_API_KEY_FROM_CONFIG` (key never printed or stored). Dependencies:
`websockets>=14` (proxy support), `edge-tts`, `ffmpeg`. Nothing was committed or pushed;
`m1_verify/` is a small, deletable verification harness, not product code.
