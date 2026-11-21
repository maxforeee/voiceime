# M2 报告 — VoiceIME v1 输入法壳子：6 块全做完、已推 GitHub，APK 待 workflow 文件落地后构建

- 日期：2026-11-21
- 状态：**代码全做完并已推送到 GitHub main；APK 还没出来——卡在 workflow 文件推不上去（见 §4），不是代码问题。**
- 仓库：https://github.com/maxforeee/voiceime ，分支 main

## 1. 每块做了什么、验过没有

| 块 | 内容 | 本地提交 | 验证情况 |
|---|---|---|---|
| 1 | 项目骨架 + IME 壳子：com.voiceime，minSdk 28 / targetSdk 34，InputMethodService 已注册，Actions workflow 已写 | 83b168e | Box 无 SDK，编译未在 Box 跑；待 Actions |
| 2 | 九宫格键盘 + 可滚动候选栏 + 中/英 + 数字/符号切换，Gboard 默认浅色 | d51327b | 无真机，未实测 |
| 3 | 词库引擎：构建时把 luna + ice 词典转成精简 SQLite（lexicon.db 6,148,096 字节，约 5.9MB，已提交），Kotlin 版全切分 DP + 全局词频排序 | 6e984e0 | **Box 实测 16/16 通过**（见下） |
| 4 | 在线联想 Google Input Tools：100ms 防抖、300ms 超时丢弃、本地先出、绝不让键盘等网络 | 5f0c91e | Box 实测接口 live：nihao→你好首位，nih→你好 |
| 5 | 大语音键盘 + 流式 ASR + 右上角切换：132dp 大麦克风，长按和点击都做；豆包官方 SDK 在 Maven 查不到，改用 M1 已验证的 WebSocket 协议 Kotlin 移植；预留“修改 · v2 即将推出”入口（未做修改功能） | a2777a8 | 协议是 M1 实测过的；端到端等真机 |
| 6 | 剪贴板最近 10 条：点一下粘贴、可清空；第 8 节验收清单逐项列在 m2b_report.md | 5515f13 | 无真机，未实测 |
| 报告 | m2b_report.md（分块明细、回归证据、许可证提醒） | 0d8ceee | 已提交 |
| 补充 | 0fc15cc：先撤下 workflow 文件（PAT 没 workflow 权限，见 §4），其余全部已推 | 0fc15cc | 已推 GitHub |

GitHub 上已确认（公开 API 查过）：main 最新是 0fc15cc，8 个提交全在。

## 2. 词库回归证据（Box 上真实跑过，不是千问自报）

`tools/test_lexicon.py` 对打好的 lexicon.db 跑：**16/16 PASS**，8 个必过项全是首位或在可滚动列表内：

- 64426→你好（第 1）、94664486→中国（第 1）、943943→谢谢（第 1）、9245426→再见（第 1）
- 743663→什么（第 1）、542694→练习（第 4）、96636→我们（第 1）、96→我（第 1）
- 另有：5426→练（第 22）、426→汉（第 13）——在可滚动候选栏内可翻到；单音节本身歧义大，这是预期行为。
- 每查询 0.3–3.7ms。64646 不是 nihao，没有拿它当测试用例。
- 完整结果在仓库 `tools/lexicon_test_results.json`（all_mandatory_pass: true）。

## 3. APK 在哪、怎么装（目前：还没有）

APK 由 GitHub Actions 构建，产物名 `voiceime-debug-apk`，文件 `app-debug.apk`。**现在还没有，因为 workflow 文件还没进仓库（§4）。** workflow 一落地，push 会自动触发构建，约几分钟出 APK。

拿到 APK 后的安装步骤（到时再走一遍）：
1. 在 GitHub 仓库页点 Actions → 最新一次成功的 run → Artifacts 栏下载 `voiceime-debug-apk`（zip，解压得 app-debug.apk）。
2. 传到手机安装（允许安装未知应用）。
3. 手机 设置 → 系统 → 语言和输入法 → 屏幕键盘 → 管理键盘，打开 VoiceIME（语音输入法）；再在任意输入框切换到它。
4. 首次用语音会弹录音权限，按提示允许。

## 4. 卡住的地方（必须先解决，否则没有 APK）

**workflow 文件 `.github/workflows/android.yml` 推不上去，两个已有的鉴权都不行：**

- Box 的 git（fine-grained PAT `muse-opencode-github-mcp`）：GitHub 拒绝——“refusing to allow a Personal Access Token to create or update workflow `.github/workflows/android.yml` without `workflow` scope”。
- Muse 的 GitHub App 连接器（create_or_update_file）：同样 403——“Resource not accessible by integration”。

所以代码先推了（0fc15cc），workflow 文件单独留在 Box `/tmp/android.yml.bak`（与 83b168e 里那个 sha256 一致）。

**解法（二选一，都只要做一次）：**

**A. 网页上直接建文件（最快，手机上就能做）：** 打开 https://github.com/maxforeee/voiceime/new/main/.github/workflows/android.yml ，把下面整段 workflow 粘贴进去，Commit。push 后 Actions 自动开始构建。

```yaml
name: Android CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build debug APK
        env:
          DOUBAO_API_KEY_FROM_CONFIG: ${{ secrets.DOUBAO_API_KEY_FROM_CONFIG }}
        run: ./gradlew assembleDebug --no-daemon --stacktrace

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: voiceime-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
```

**B. 给 PAT 加 workflow 权限：** 在 GitHub 的 PAT 设置里给 `muse-opencode-github-mcp` 加上 workflow 权限（或新开一个带 workflow scope 的 PAT），然后告诉 Muse，Muse 再从 Box 推一次 workflow 文件。

**注意：** workflow 落地后，Box 上要 `git pull` 同步一次（Box 本地 main 现在是 0fc15cc，与远端一致；网页提交后远端会领先一个提交）。

## 5. 语音 Key 的状态（重要）

- 豆包 Key **没有写进代码、没有写进 git**，只在构建时从环境变量 `DOUBAO_API_KEY_FROM_CONFIG` 读进 BuildConfig。
- 想给 Actions 的 secret 配 Key 也失败了：同一个 PAT 没有 Actions secrets 权限（403）。所以 **即使 workflow 落地、APK 构建成功，首版 APK 里的语音也是“未配置”状态**：打字、联想、剪贴板都正常，点语音会明确提示未配置，不会崩。
- 要让语音在 APK 里能用，需要在仓库 Settings → Secrets and variables → Actions 里新建 secret `DOUBAO_API_KEY_FROM_CONFIG`，值就是豆包 Key（手机网页上操作即可）。加好后重新跑一次 workflow（Actions 页点 Re-run），出的 APK 就带语音了。
- 另外：豆包官方 Android SDK（SpeechEngine）在 Maven Central 查不到、ByteDance 仓库路径返回为空，所以语音走的是 M1 已验证的 WebSocket 协议 Kotlin 移植，不是官方 SDK。M1 实测 CER 0.00%、尾延迟中位 0.37s。

## 6. 许可证提醒（打包进应用前需确认）

提交的 lexicon.db 是衍生数据：luna-pinyin（LGPLv3）+ rime-ice（GPLv3）+ 词频表 zh_full.txt（源自 OpenSubtitles，未写明确许可证）。词频表只在构建时用、没进 APK；但衍生词库进了 APK。**个人测试 APK 没问题，正式发版/分发前必须先确认许可证兼容性。** 原始 22MB 词典文件没有提交进 git（已在 .gitignore 里）。

## 7. 还没验证的事（要真机 + APK 才能验，列在 m2b_report.md 里）

- 项目能不能编译通过（等第一次 Actions 构建见分晓；失败会修到通过或如实汇报）。
- 九宫格打字、候选上屏、切换、语音长按/点击、中间结果跟不跟得上、最终结果只提交一次、剪贴板粘贴、Gboard 配色观感——都要装上 APK 在真机上按实施书 §8 逐项过。
- M4 语音修改是 v2，本次没做，只留了入口位置。
