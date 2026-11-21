# VoiceIME 词库 / lexicons

VoiceIME 三合一九键键盘的词库。跟 App 代码分开放在 `lexicons/` 里：App
联网时从云端（本仓库的 GitHub Raw）按清单加载，**没网时用 App 内置的
核心词库**（内置哪些由 App 侧定，见 App 内 `assets/`）。

词库是九键的命脉——九键本来就是模糊边界，靠联想、靠 AI，所以这里的
目标是：**市面上能接的公开词库都接上，能搬用的都搬进来**；再加我们
自己整理的专有名词（公司、品牌、地名、人名）。

## 分类结构

先按语言分（中文 `zh` / 英文 `en`），再按类型分。不平铺。

```
lexicons/
├── README.md                  本文件
├── manifest.json              清单：每个词库的名字、语言、分类、路径、大小、来源、许可证
├── LICENSES/                  各许可证全文与说明
│   ├── LICENSES.md
│   ├── gpl-3.0.txt
│   └── lgpl-3.0.txt
├── zh/                        中文
│   ├── common/                常用词（带拼音、带词频）
│   │   ├── luna_pinyin.tsv    明月拼音：70,717 条
│   │   └── rime_ice.tsv       雾凇拼音词库（6 个 dict 合并去重）：1,910,826 条
│   ├── frequency/             词频表
│   │   ├── jieba_zh.tsv       中文词频实测计数：38,811 条
│   │   └── wordfreq_zh.tsv    wordfreq 中文大词表：334,609 条
│   └── proper/                专有名词
│       ├── companies/
│       │   ├── ai_companies_zh.tsv   AI 公司中英文名（OpenAI、谷歌、Anthropic…）：53 条
│       │   └── companies_zh.tsv      中国常见公司名：66 条
│       ├── places/
│       │   └── places_zh.tsv         省 / 市 / 区县：3,387 条
│       └── people/
│           └── surnames_zh.tsv       中国常见姓氏 100：99 条
└── en/                        英文
    ├── common/                常用词
    │   ├── wordfreq_en_top10000.tsv  最常用一万词：10,000 条
    │   └── english_words_alpha.tsv   英文单词全表：370,105 条
    ├── frequency/             词频表
    │   └── wordfreq_en.tsv    wordfreq 英文大词表：321,180 条
    └── proper/                专有名词
        ├── companies/
        │   ├── ai_companies_en.tsv   AI 公司英文名（OpenAI、Google、Anthropic…）：52 条
        │   └── companies_en.tsv      Fortune 500 + NASDAQ + DJIA：628 条
        ├── places/
        │   └── places_en.tsv         国家 + 美国城市 + 英国城镇：2,183 条
        └── people/
            ├── first_names_en.tsv    美国常用名：397 条
            └── last_names_en.tsv     美国常用姓：200 条
```

总计 16 个文件，3,063,313 条，约 62.3 MB。单个文件最大的是
`zh/common/rime_ice.tsv`（47,966,773 字节，约 45.8 MiB），低于 GitHub
单文件 100 MB 上限，无需拆分。

## 文件格式

全部是 UTF-8 文本，一行一条，Tab 分隔，按类别列不同：

| 类别 | 格式 | 说明 |
|---|---|---|
| `zh/common` | `词<TAB>拼音<TAB>词频` | 拼音是 Rime 原格式（音节间有空格，如 `ni hao`）；词频降序，常用词在前；无词频的为 0 |
| `zh/frequency`、`en/frequency` | `词<TAB>频率` | jieba 是实测计数；wordfreq 是 Zipf 频率估计值，见下 |
| `en/common` | `词<TAB>zipf_est`（top10000）或 `词`（words_alpha） | top10000 按频率降序；words_alpha 按字母顺序，无频率 |
| `proper/*` | `名字`（一行一个） | 专有名词，按字母顺序（中文地名按行政顺序） |

## wordfreq Zipf 估计值的说明

wordfreq 的 `large_*.msgpack.gz` 用的是 cB 桶格式：800 个桶，桶号越小
词越常用。我们没有把 wordfreq 库本身搬进来，只按桶号还原了一个
Zipf 频率估计值：`zipf_est = 8.5 - 桶号 / 100`（保留两位小数）。
这个值**只用于排序**（常用词在前），不是原始精确频率。实测验证：
`the` 7.22、`of` 6.89、`hello` 4.21、`good` 5.61，顺序正确。

## 来源与许可证

| 词库 | 来源 | 许可证 | 转载 |
|---|---|---|---|
| luna_pinyin | [rime/rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin) | LGPL-3.0 | 允许，附许可证全文（`LICENSES/lgpl-3.0.txt`） |
| rime_ice | [iDvel/rime-ice](https://github.com/iDvel/rime-ice) | GPL-3.0 | 允许，附许可证全文（`LICENSES/gpl-3.0.txt`） |
| jieba_zh、wordfreq_zh、wordfreq_en、wordfreq_en_top10000 | [rspeer/wordfreq](https://github.com/rspeer/wordfreq) | CC BY-SA 4.0（数据） | 允许，需署名、相同方式共享 |
| english_words_alpha | [dwyl/english-words](https://github.com/dwyl/english-words) | Unlicense（公有领域） | 允许 |
| companies_en、places_en、first_names_en、last_names_en | [dariusk/corpora](https://github.com/dariusk/corpora) | CC0（README 中声明） | 允许 |
| places_zh | [modood/Administrative-divisions-of-China](https://github.com/modood/Administrative-divisions-of-China) | WTFPL | 允许 |
| ai_companies_*、companies_zh、surnames_zh | VoiceIME 自己整理（事实名称） | CC0 | 我们自己的 |

每条的准确来源 URL、许可证链接、条数、大小都在 `manifest.json` 里，
App 按清单加载。注意：GPL/LGPL 词库（rime-ice、luna）如果随 App 分发，
App 须遵守其许可证要求（附许可证文本、开源对应源码）。

## 没有收进来的

- **first20hours/google-10000-english**：仓库没有声明许可证，不能转载，
  未收录。英文常用词用 wordfreq top 10,000 代替。
- **wordfreq jieba_zh_orig.txt / small_***：分别是未过滤原始表和 large 表
  的子集，已有对应版本，不再重复收。

## App 怎么用

1. 读 `manifest.json`，按 `category` / `language` 挑要加载的词库。
2. 联网时从 GitHub Raw 拉文件：
   `https://raw.githubusercontent.com/maxforeee/voiceime/main/lexicons/<path>`
   （`path` 字段就是相对路径）。
3. 没网时用 App 内置核心词库；联网后在后台下载、建索引，不要卡打字。
4. 加载顺序建议：`common` → `frequency` → `proper/*`；同词去重时
   取词频最高的；选过的词（本地 learned.db）排最前。
5. 以后加新词库：文件放进对应分类文件夹，`manifest.json` 里加一条，
   `README.md` 里补一行。

## 更新记录

- 2026-11-21：首版，16 个文件，3,063,313 条。
