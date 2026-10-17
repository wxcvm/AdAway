<div align="center">
  <img src="资源/应用程序图标/矢量/应用程序图标.svg" width="96" alt="AdAway icon"/>
  <h1>AdAway</h1>
  <p>Android 系统级广告拦截 · Root 模式 · ARM64</p>

  [![Release](https://img.shields.io/github/v/release/wxcvm/AdAway?style=flat-square&color=c62828)](https://github.com/wxcvm/AdAway/releases/latest)
  [![License](https://img.shields.io/github/license/wxcvm/AdAway?style=flat-square&color=546e7a)](LICENSE)
  [![Fork of](https://img.shields.io/badge/fork%20of-AdAway%2FAdAway-555?style=flat-square)](https://github.com/AdAway/AdAway)
</div>

---

本仓库是 [AdAway/AdAway](https://github.com/AdAway/AdAway) 的个人 Fork，专注于 Root 模式的精简与优化。

## 与上游的主要差异

| 特性 | 本 Fork | 上游 |
|------|---------|------|
| 支持架构 | ARM64-v8a only | all |
| VPN 模式 | ✗ 已移除 | ✓ |
| 捐赠页面 | ✗ 已移除 | ✓ |
| HTTPS 拦截 | ✓ SNI 动态签发 | 仅 localhost |
| NDK 版本 | r28b | r27 |
| Target SDK | 35 | 33 |

## 安装

从 [Releases](https://github.com/wxcvm/AdAway/releases/latest) 下载最新 APK 直接安装。

> **要求**：Android 11+（minSdk 30，仅 arm64-v8a 架构），Magisk / KernelSU / APatch 等 Root 方案。
> 无 Root 时只能覆盖 DNS/hosts 层，无法接管全部流量；HTTPS 内容过滤还依赖本地 CA 被系统信任。

## 证书安装（HTTPS 拦截必须）

应用内：设置 → Root 拦截器 → 安装 CA 证书

## 自定义拦截占位图

- **用户在设备上自己换**：设置里"关于"→ 连续点击版本号 7 次解锁高级选项，然后去"基于 Root 的广告拦截器"页面选图。运行时生效，不需要重新编译。
- **仓库维护者/打包前统一换默认图**：直接把图片文件放进 `app/src/main/assets/`，命名成 `img_00`到 `img_06`（比如 `img_00.png`、`img_03.jpg`，PNG/JPG/WEBP 都可以），App 首次运行时会自动检测并转换成 webp，**不需要额外跑脚本、不需要装任何依赖**。这几张图不要求统一尺寸/比例（原图本身就是 512x768、512x512、512x410 混着的）。
  - 如果想在提交前就看到转换后的效果（而不是等 App 首次运行时才转换），也可以用 `scripts/set_default_block_images.py` 提前手动转换好再放进仓库，这个脚本不是必须的，纯粹是可选的预览/校验手段。

## 构建

```bash
git clone https://github.com/wxcvm/AdAway.git
cd AdAway
./gradlew assembleRelease
```

## 许可证

[GPL-3.0](LICENSE) · 原作者 [AdAway Team](https://github.com/AdAway/AdAway)

## Windows 11 端（独立仓库）

Windows 端的 Web 服务端、Win32 控制面板与安装器已迁到独立仓库维护：
**https://github.com/wxcvm/AdAway-Windows** （独立 CI / 版本号 / Release）

- 它**不接管全流量**：只用 DNS / hosts / 系统代理这一层，不安装 VPN 或 TUN 适配器（与 Android 端一样，
  本项目的任何一端都不使用 VPN 接管方式）；
- 端上能读的配置与统计口径与 Android 端一致，契约见本仓库 `docs/windows-core-contract.md`
  （`block_config.json` 键名、`/internal-stats` 字段、规则/策略文件、规则语义子集）；
- 该仓库按**固定提交**取用上游的 `webserver/jni` 与 webroot 资源，并在自己的 CI 里断言取到的就是被 pin 的那一个提交；
  但请注意：**两端核心并非同一份文件** —— Windows 端在 `webserver.c` 上有若干**独有功能**
  （日志镜像、面板可选的按类型处置、监听器注册表等），Android 端近期的一些修复也尚未包含在 Windows 端。
  共同的**规则语义、配置键名与 `/internal-stats` 字段**以 `docs/windows-core-contract.md` 为准（那部分是严格对齐的），
  差异与将来合一的工作清单见该文档 §6.1/§6.2。
