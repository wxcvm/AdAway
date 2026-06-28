```markdown
# AdAway (Root-Only Fork)

AdAway 是一款基于 Android 的开源广告拦截器，通过修改系统 hosts 文件来实现广告屏蔽。

本仓库是 [AdAway 原版项目](https://github.com/AdAway/AdAway) 的一个分支（Fork）。**本分支已移除所有 VPN 模式相关的代码，仅支持 Root 模式，专注于通过直接修改系统 hosts 文件实现更底层的拦截效果。**

## ⚠️ 重要说明

- **本分支仅支持 Root 模式**，已移除 VPN 相关功能。如需 VPN 模式，请使用 [原版项目](https://github.com/AdAway/AdAway)。
- **下载**：请前往本仓库的 [**Releases 页面**](https://github.com/wxcvm/AdAway/releases) 下载 APK。**不要**从原项目官网或 F-Droid 下载，那些版本不包含本分支的修改。
- **问题反馈**：请在本仓库的 [**Issues**](https://github.com/wxcvm/AdAway/issues) 中反馈。XDA 帖子或原项目 Issue 与本分支无关。

## 目录

- [核心特性](#核心特性)
- [截图](#截图)
- [使用前提](#使用前提)
- [安装](#安装)
- [编译说明](#编译说明)
- [Hosts 源列表](#hosts-源列表)
- [获取帮助](#获取帮助)
- [贡献](#贡献)
- [许可证](#许可证)

## 核心特性

- **Root 专属：** 仅利用 Root 权限直接修改 Android 系统 hosts 文件，拦截效果更彻底。
- **资源占用极低：** 无需后台运行 VPN 服务，不占用系统网络接口，对续航无额外影响。
- **开源安全：** 代码透明，尊重隐私，遵循 GPLv3 协议。
- **本分支优化：**
  - 移除了 VPN 模块，精简了应用体积。
  - 专注于 Root 模式下的稳定性与兼容性。

## 截图

| 主页 | 偏好设置 | Root 拦截 |
| :---: | :---: | :---: |
| [<img src="metadata/en-US/phoneScreenshots/screenshot1.png" alt="Home screen" height="256">](metadata/en-US/phoneScreenshots/screenshot1.png) | [<img src="metadata/en-US/phoneScreenshots/screenshot2.png" alt="Preferences screen" height="256">](metadata/en-US/phoneScreenshots/screenshot2.png) | [<img src="metadata/en-US/phoneScreenshots/screenshot3.png" alt="Root based ad blocker screen" height="256">](metadata/en-US/phoneScreenshots/screenshot3.png) |
| 备份与恢复 | 帮助 | |
| [<img src="metadata/en-US/phoneScreenshots/screenshot4.png" alt="Backup and restore screen" height="256">](metadata/en-US/phoneScreenshots/screenshot4.png) | [<img src="metadata/en-US/phoneScreenshots/screenshot5.png" alt="Help screen" height="256">](metadata/en-US/phoneScreenshots/screenshot5.png) | |

## 使用前提

1. **Root 权限：** 您的 Android 设备必须已获取 Root 权限。
2. **系统挂载：** 确保您的系统分区可以被修改。
3. **Android 版本：** 需要 Android 8（Oreo）或更高版本。

## 安装

前往本仓库的 [Releases 页面](https://github.com/wxcvm/AdAway/releases) 下载最新的 APK 进行安装。

有两种版本可供选择：
- **预览版：** 包含最新开发中的功能和修复，适合测试者或喜欢尝鲜的用户。
- **稳定版：** 经过测试，适合日常使用。

## 编译说明

本项目使用 Gradle 构建。如需自行构建，请确保环境已配置好 Android SDK。

在终端执行：

```bash
./gradlew assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/` 目录下。

如需构建 Release 版本，请参考 [RELEASING.md](RELEASING.md)。

## Hosts 源列表

可参考本仓库的 [Wiki](https://github.com/wxcvm/AdAway/wiki) 和 [HostsSources 页面](https://github.com/wxcvm/AdAway/wiki/HostsSources) 获取可用的 Hosts 源列表。

在 AdAway 的「Hosts 源」设置中添加您需要的源即可。

## 获取帮助

- 提交 [Issue](https://github.com/wxcvm/AdAway/issues) 反馈问题。
- 查阅 [Wiki](https://github.com/wxcvm/AdAway/wiki) 获取更多文档。

## 贡献

欢迎参与本项目的开发与维护！请阅读以下指南：

- [贡献指南](CONTRIBUTING.md) — 了解如何报告 Bug、提出功能建议和提交代码。
- [翻译指南](TRANSLATING.md) — 了解如何将应用翻译为您的语言。

## 许可证

本项目遵循 [GPLv3+](LICENSE) 许可证，详情请参阅 [许可证说明](LICENSE.md)。

---

本分支由 **[wxcvm](https://github.com/wxcvm)** 基于 [AdAway 原版项目](https://github.com/AdAway/AdAway) 维护，专注于 Root 模式的优化与修复。感谢原项目所有开发者的辛勤工作。
```

 
