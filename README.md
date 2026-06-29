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

> **要求**：Android 8.0+，Magisk 或其他 Root 方案

## 证书安装（HTTPS 拦截必须）

应用内：设置 → Root 拦截器 → 安装 CA 证书

## 构建

```bash
git clone https://github.com/wxcvm/AdAway.git
cd AdAway
./gradlew assembleRelease
```

## 许可证

[GPL-3.0](LICENSE) · 原作者 [AdAway Team](https://github.com/AdAway/AdAway)
