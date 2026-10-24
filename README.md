<div align="center">

# GTool · 吉他工具箱

**调音器 · 节拍器 · 和弦查找**

一个完全离线的 Android 吉他工具，为 vivo X300 Pro 优化，侧载 APK 使用。

[下载 APK](https://github.com/lj5409/GTool/releases/latest) · [开发计划](docs/开发计划.md) · [测试清单](docs/测试清单.md) · [进度台账](docs/进度.md)

</div>

---

## 功能

### 调音器

- 麦克风实时拾音，YIN 音高检测，2048/4096 点自适应窗口，低音 E2（82 Hz）也能稳定识别
- 大号音名 + 频率 + 音分表盘，±3 音分内变绿提示“准”
- 自动匹配当前弦，也可手动锁定某根弦
- 9 套内置调弦：标准、Drop D、降半音、降全音、Open G、Open D、Open Dm、DADGAD、Open C
- **自定义调弦**：命名、逐弦改音、保存、切换，重启不丢
- **参考音播放**：点弦号播放标准音，波形带 2/3 次谐波，手机扬声器也能听清低音弦
- A4 基准 430–450 Hz 可调，♯/♭ 显示切换

### 节拍器

- 30–300 BPM、2/4 · 3/4 · 4/4 · 6/8、细分 1–4、每拍重音等级
- Tap Tempo、按拍点时间戳驱动的音画同步、±100 ms 校准
- **静音模式**（四种提示可任意组合）：
  - 屏幕：全屏色块/脉冲，颜色与亮度可调
  - 闪光灯：每拍脉冲，强度可调（不支持时自动降级为开/关）
  - 振动：重音/弱拍不同强度
  - 声音可单独关闭
- 闪烁频率 >3 Hz 自动降级为柔和脉冲并提示（光敏安全）

### 和弦查找

- 12 根音 × 18 种和弦类型（maj / min / 7 / maj7 / m7 / m7♭5 / dim / dim7 / aug / sus2 / sus4 / 6 / m6 / add9 / 9 / maj9 / m9 / 5）
- 显示组成音、音级、频率
- 6 弦 15 品指板图：**根音红色高亮**、和弦音蓝色、非和弦音淡灰
- 自动生成 5 个可弹指法，含手指编号与横按，按可演奏性打分排序
- **Capo 0–7 品**，指法按实际发声音高重新求解
- **扫弦 / 分解播放**（下扫、上扫、53231323），指板逐弦拨动动画与音频同步

---

## 技术栈

| 项目 | 选择 |
|---|---|
| 语言 | Kotlin 2.2.21 |
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity + Navigation Compose + ViewModel/StateFlow |
| 存储 | DataStore Preferences + kotlinx.serialization |
| 音频 | AudioTrack（48 kHz、低延迟）+ 自研 DSP |
| 音高检测 | YIN（差分函数 + CMND + 抛物线插值） |
| 吉他音色 | Karplus-Strong 物理建模合成，无需采样素材 |
| 构建 | AGP 8.13.2 / Gradle 8.14.3 / JDK 21 |
| 兼容 | minSdk 26（Android 8.0），targetSdk 36（Android 16） |

## 架构

```
app/src/main/java/com/gtool/
├── core/
│   ├── music/     # 音高、调弦、和弦、指法生成（纯 Kotlin，可单测）
│   ├── audio/     # 音高检测、麦克风采集、参考音、节拍器、吉他合成、播放器
│   ├── device/    # 闪光灯、振动
│   └── data/      # 设置与自定义调弦的持久化
├── feature/
│   ├── tuner/     # 调音器 UI + ViewModel
│   ├── metronome/ # 节拍器 UI + ViewModel + 屏幕闪烁
│   └── chords/    # 和弦 UI + ViewModel + 指板/和弦图绘制
└── ui/            # 主题、底部导航
```

- `core/music` 与 `core/audio` 中的纯逻辑不依赖 Android，16 项 JVM 单元测试覆盖音高检测与指法生成。
- 三个功能各自持有播放器，互不抢占资源；音频事件按采样点调度，UI 通过时间戳对齐。

## 构建

需要 Windows + PowerShell 7（`pwsh`）。首次构建会自动下载 JDK 21、Android SDK 36、Gradle：

```powershell
# 1. 安装便携工具链（约 460 MB，装到 %USERPROFILE%\.gtool-tools）
& ".\scripts\setup-toolchain.ps1"

# 2. 构建 debug APK
& ".\scripts\build.ps1"

# 3. 构建签名 release APK（需要根目录 keystore.properties）
& ".\scripts\build.ps1" -Variant release

# 单元测试
& ".\scripts\build.ps1" -Tasks "testDebugUnitTest"
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

> 说明：release 关闭了 R8/资源压缩（开启时资源表异常，APK 只剩 1.7 MB），优先保证运行稳定。

## 安装

1. 从 [Releases](https://github.com/lj5409/GTool/releases) 下载 `GTool-0.1.1.apk`；
2. 传到手机后点击安装，按系统提示允许“安装未知应用”；
3. 覆盖安装会保留自定义调弦与设置。

首次进入调音器会申请麦克风权限；闪光灯模式可能申请相机权限。应用不申请网络权限，完全离线。

## 版本

### v0.1.1（2026-10-24）

- 修复节拍器拍点圆点缩放导致下方 UI 位移
- 修复和弦 Capo 切换时界面跳动
- 修复播放中切换 Capo 停止播放、按钮状态不同步；现在会自动用新指法续播
- 修复调音器无声时目标行消失导致弦位列表跳动
- 参考音音量 0.32 → 0.9，并加入 2/3 次谐波，低音弦明显更响

### v0.1.0（2026-10-24）

- 首个可用版本：调音器、节拍器、和弦查找 + 自定义调弦、参考音、Capo、振动节拍

## 已知限制

- 闪光灯亮度调节依赖厂商是否上报强度等级；不支持时降级为开/关
- 屏幕闪烁 >3 Hz 会自动降级为柔和脉冲
- 和弦首次播放有约 0.2 秒音色预热
- 尚未在真机上做长时间压力测试（详见 [测试清单](docs/测试清单.md)）

## 文档

| 文档 | 内容 |
|---|---|
| [开发计划](docs/开发计划.md) | 需求核对、技术方案、风险、验收标准 |
| [架构约定](docs/架构约定.md) | 模块边界与共享 API |
| [进度台账](docs/进度.md) | 当前状态、构建环境、暂停/恢复步骤 |
| [测试清单](docs/测试清单.md) | 真机验收项 |

## 隐私

- 无网络权限，不采集、不上传任何数据
- 麦克风仅用于调音器实时分析，不录音、不存储
- 所有设置与自定义调弦只保存在本机 DataStore

## 许可

个人项目，暂未声明开源许可证，保留所有权利。
