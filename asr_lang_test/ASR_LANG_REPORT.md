# ASR 语言/中英混说测试报告 — v1.3.0

**日期：** 2026-11-21
**结论（先说）：豆包流式识别接口不支持语言参数，也没有能接上的中英混合开关。** 三个候选字段都实测过，服务器静默接受但完全不生效；模型本身就是中英文自动识别的。App 的识别请求不用改，键盘上的中/英切换也不用当语言提示传过去（传了也没用，而且一旦锁定某一种语言，混说反而更差）。

## 1. 文档上有什么

官方“一句话识别”文档（bigmodel_nostream，同属 sauc bigmodel 家族）里有这三个字段：

- `audio.language`：指定识别语种，支持 zh-CN、en-US、ja-JP 等。**留空时模型支持识别中文（普通话/粤语/闽南语/四川话等）和英语**——也就是说默认就是中英自动识别。
- `request.enable_lid`：启用中英文及方言识别，默认 false。支持中文、英文、上海话、闽南话、四川话、陕西话、粤语。
- `request.enable_auto_lang`：自动识别语种，默认 false。开启后自动检测整段音频属于哪一种语种。

但这些是“一句话识别”（bigmodel_nostream）和录音文件接口的文档。我们的流式接口是 `bigmodel_async`，文档里没有明确说它认这些字段——所以必须实测。

## 2. 实测方法（跟 M1 一样）

- 音频：edge-tts 合成 → 16 kHz 16-bit 单声道 PCM，流式 100ms 一包发过去。
- 三组各 6 句：纯中文（复用 M1 的 6 句和音频）、纯英文（en-US-AriaNeural）、中英混说（zh-CN-XiaoxiaoNeural 读中文里夹英文词，跟用户平时说话方式一样）。
- 每句按 5 种请求各测一遍（同一段音频，只改请求参数）：
  - `base`：现在 App 发的请求（无 language、无 enable_lid）
  - `lid`：request 里加 `enable_lid: true`
  - `lang_zh`：audio 里加 `language: "zh-CN"`
  - `lang_en`：audio 里加 `language: "en-US"`
  - `auto_lang`：request 里加 `enable_auto_lang: true`
- 指标：CER（去标点后的字错误率，跟 M1 同口径）+ 混说组里英文词命中数（9 个英文词）。

## 3. 结果：三个字段全都不生效

### 3.1 语言锁定验收（决定性证据）

| 句子 | 请求 | 结果 |
|---|---|---|
| 中文句 zh1（今天天气怎么样……） | `language: "en-US"` | CER 0.00%，中文全对 |
| 英文句 en1 | `language: "zh-CN"` | CER 1.75%（只差一个字母大小写），英文全对 |

如果 language 字段真被执行，中文句锁英文、英文句锁中文，结果应该全错。结果一点没变——**服务器收了这个字段，但根本没用它**。

### 3.2 三组完整结果（18 句 × base/lid，共 36 次调用）

| 组 | base CER | lid CER | 说明 |
|---|---|---|---|
| 纯中文（6 句） | 0.00%（0/102） | 0.00%（0/102） | 与 M1 完全一致 |
| 纯英文（6 句） | 3.07%（11/358） | 3.07%（11/358） | 见 3.3 |
| 中英混说（6 句） | 1.60%（2/125），英文词 8/9 | 1.60%（2/125），英文词 8/9 | 见 3.3 |

`base` 和 `lid` 在 36 次调用里**逐句输出完全相同**；`auto_lang` 在混说探针里也与 base 完全相同。三个字段全部实测无效。

### 3.3 错在哪（混说的真实误差）

混说组只错了一处：

- mix1“今天天气很好，我们下午去 **park** 散步吧。”→ 识别成“去 **part** 散步吧。”（英文词按中文口音读出来，模型听成了近音词）

纯英文组 11 个字符错误里 8 个来自同一句：

- en5“Please translate that message into English and send it to **Xiao Wang**.”→“send it to. **Shall we?**”（中文人名放在英文句子里，模型听成了英文短语）

其余英文错误全是大小写/标点（Let's 写成 let's 一类），不算真错。

**用户“中英混说导致英语不准”的判断基本是对的**：错都出在中英交界处——中文口音的英文词、或英文句子里的中文人名，模型会听成近音的另一种语言的词。但整体误差很小（混说组 CER 1.60%，英文词 8/9），不是大面积听不懂。

## 4. 现有后端清洗能不能兜住

把上面两句错话发给现有后端 `/correct`（DeepSeek，normal 档）实测：

| 输入（识别错的结果） | /correct 返回 | 结论 |
|---|---|---|
| 今天天气很好，我们下午去 part 散步吧。 | 今天天气很好，我们下午去公园散步吧。 | 修好了（结合上下文把 part 还原成公园） |
| Please translate that message into english and send it to. Shall we? | Please translate that message into English and send it to [recipient]. Shall we? | 没修好（English 大小写修了，但 Shall we 没认出来是 Xiao Wang，还补了个 [recipient] 占位） |

所以 App 里已有的“识别→后端清洗”这一层，已经能兜住中文混说里 park→part 这类错；英文句子里中文人名听错（Xiao Wang→Shall we）兜不住。

## 5. 结论和下一步

**结论：**

1. 流式接口 `bigmodel_async`（seedasr 2.0）不支持语言参数：`audio.language`、`request.enable_lid`、`request.enable_auto_lang` 三个字段服务器都静默接受、但实测全部不生效（46 次调用验证）。
2. 模型默认就是中英文自动识别，不用、也不能通过请求指定语言；键盘中/英切换不传语言提示。
3. 混说的误差真实存在但很小（CER 1.60%，英文词 8/9），错法是近音词混淆（park→part、Xiao Wang→Shall we），不是语言锁定问题。
4. 现有后端清洗能修好中文混说那类错，英文里的人名错修不好。

**下一步（建议，不换识别服务）：**

- 测 `corpus`（热词）参数：官方文档提到识别支持热词、替换词、上下文能力。如果流式接口认这个参数，就把用户常说的英文词和人名（park、meeting、Xiao Wang 这类）做成热词表发过去——这是最直接修近音混淆的办法。需要先查清热词在流式请求里的具体格式再测。
- 用户侧：英文词和人名说的时候稍微放慢、咬字清楚一点，识别会准一些。

**测试文件：** `asr_lang_test/`（sentences.json、gen_audio.py、run_lang.py、results_*.json、本报告）
