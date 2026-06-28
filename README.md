# AdAway (Root-Only Fork)

![Android CI](https://github.com/vxcvm/adaway/actions/workflows/android.yml/badge.svg)
![Security](https://img.shields.io/badge/security-A-brightgreen)
![Downloads](https://img.shields.io/github/downloads/vxcvm/adaway/total.svg)
![License](https://img.shields.io/badge/license-GPL--v3-blue.svg)

AdAway 是一款基于 Android 的开源广告拦截器，通过修改系统 hosts 文件来实现广告屏蔽。

本仓库是 [AdAway 原版项目](https://github.com/AdAway/AdAway) 的一个分支（Fork）。**本分支已移除所有 VPN 模式相关的代码，仅支持 Root 模式，专注于通过直接修改系统 hosts 文件实现更底层的拦截效果。**

## 目录
- [核心特性](#核心特性)
- [使用前提](#使用前提)
- [编译说明](#编译说明)
- [更新日志](#更新日志)
- [免责声明](#免责声明)
- [协议](#协议)

## 核心特性
*   **Root 专属：** 仅利用 Root 权限直接修改 Android 系统 hosts 文件，拦截效果更彻底。
*   **资源占用极低：** 无需后台运行 VPN 服务，不占用系统网络接口，对续航无额外影响。
*   **开源安全：** 代码透明，尊重隐私，遵循 GPLv3 协议。
*   **本分支优化：**
    *   移除了 VPN 模块，精简了应用体积。
    *   专注于 Root 模式下的稳定性与兼容性。

## 使用前提
1. **Root 权限：** 您的 Android 设备必须已获取 Root 权限。
2. **系统挂载：** 确保您的系统分区（System）可以被修改（Remount）。
3. **安装：** 前往 [Releases 页面](https://github.com/vxcvm/adaway/releases) 下载 APK 进行安装。

## 编译说明
本项目使用 Gradle 构建。如需自行构建，请确保环境已配置好 Android SDK。

在终端执行：
```bash
./gradlew assembleDebug[<img src="metadata/en-US/phoneScreenshots/screenshot1.png"
    alt="Home screen"
    height="256">](metadata/en-US/phoneScreenshots/screenshot1.png)
[<img src="metadata/en-US/phoneScreenshots/screenshot2.png"
    alt="Preferences screen"
    height="256">](metadata/en-US/phoneScreenshots/screenshot2.png)
[<img src="metadata/en-US/phoneScreenshots/screenshot3.png"
    alt="Root based ad blocker screen"
    height="256">](metadata/en-US/phoneScreenshots/screenshot3.png)
[<img src="metadata/en-US/phoneScreenshots/screenshot4.png"
    alt="Backup and restore screen"
    height="256">](metadata/en-US/phoneScreenshots/screenshot4.png)
[<img src="metadata/en-US/phoneScreenshots/screenshot5.png"
    alt="Help screen"
    height="256">](metadata/en-US/phoneScreenshots/screenshot5.png)

For more information visit https://adaway.org

## Installing

There are two kinds of release:
* The preview builds: on the bleeding edge of development - for testers or adventurous
* The stable builds: ready for every day usage - for end users

### Preview builds

**Requirements:** Android 8 _Oreo_ or above

For users with bugs, there may be preview builds available from the [XDA development thread](https://forum.xda-developers.com/showthread.php?t=2190753) and [AdAway official website](https://app.adaway.org/beta.apk).
It is recommended to try those builds to see if your issue is resolved before creating an issue.
The preview builds may contain bug fixes or new features for new android versions.

[<img src="Resources/get-it-on-adaway.png"
      alt="Get it on official AdAway website"
      height="80">](https://app.adaway.org/beta.apk)
[<img src="Resources/XDADevelopers.png"
      raw="true"
      alt="Get it on XDA forum"
      height="60">](https://forum.xda-developers.com/showthread.php?t=2190753)

### Stable builds

**Requirements:**
* Android Android 8 _Oreo_ or above

After preview builds have been tested by the more technical or responsive community within the forums, we will then post the stable build to F-Droid.

[<img src="Resources/get-it-on-adaway.png"
    alt="Get it on official AdAway website"
    height="80">](https://app.adaway.org/adaway.apk)
[<img src="Resources/get-it-on-fdroid.png"
      raw="true"
      alt="Get it on F-Droid"
      height="80">](https://f-droid.org/app/org.adaway)

For devices older than Android 8 _Oreo_, use the version 4 of AdAway.

## Get Host File Sources

See the [Wiki](https://github.com/AdAway/AdAway/wiki), in particular the page [HostsSources](https://github.com/AdAway/AdAway/wiki/HostsSources) for an assorted list of sources you can use in AdAway.
Add the ones you like to the AdAway "Hosts sources" section.

## Getting Help

You can post [Issues](https://github.com/AdAway/AdAway/issues) here or obtain more detailed community support via the [XDA developer thread](http://forum.xda-developers.com/showthread.php?t=2190753).

## Contributing

You want to be involved in the project? Welcome onboard!  
Check [the contributing guide](CONTRIBUTING.md) to learn how to report bugs, suggest features and make you first code contribution :+1:

If you are looking for translating the application in your language, [the translating guide](TRANSLATING.md) is for you.

## Project Status

AdAway is actively developed by:
* Bruce Bujon ([@PerfectSlayer](https://github.com/PerfectSlayer)) - Developer  
[PayPal](https://paypal.me/BruceBUJON) | [GitHub Sponsorship](https://github.com/sponsors/PerfectSlayer)
* Daniel Mönch ([@Vankog](https://github.com/Vankog)) - Translations
* Jawz101 ([@jawz101](https://github.com/jawz101)) - Hosts list
* Anxhelo Lushka ([@AnXh3L0](https://github.com/AnXh3L0)) - Web site

We do not forget the past maintainers:
* Dāvis Mošenkovs ([@DavisNT](https://github.com/DavisNT)) - Developer  
[Paypal](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=5GUHNXYE58RZS&lc=US&item_name=AdAway%20Donation&no_note=0&no_shipping=1)
* [@0-kaladin](https://github.com/0-kaladin) - Developer and XDA OP
* Sanjay Govind ([@sanjay900](https://github.com/sanjay900)) - Developer

And we thank a lot to the original author:
* Dominik Schürmann ([@dschuermann](https://github.com/dschuermann)) - Original developer  
[Paypal](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=android%40schuermann.eu&lc=US&item_name=AdAway%20Donation&no_note=0&no_shipping=1&currency_code=EUR) | [Flattr](https://flattr.com/thing/369138/AdAway-Ad-blocker-for-Android) | BTC: `173kZxbkKuvnF5fa5b7t21kqU5XfEvvwTs`

## Permissions

AdAway requires the following permissions:

* `INTERNET` to download hosts files and application updates. It can send bug reports and telemetry [if the user wants to (opt-in only)](https://github.com/AdAway/AdAway/wiki/Telemetry)
* `ACCESS_NETWORK_STATE` to restart VPN on network connection change
* `RECEIVE_BOOT_COMPLETED` to start the VPN on boot
* `FOREGROUND_SERVICE` to run the VPN service in foreground
* `POST_NOTIFICATIONS` to post notifications about hosts source update, application update and VPN controls. All notifications can be enabled or disabled independently.
* `REQUEST_INSTALL_PACKAGES` to update the application using the builtin updater
* `QUERY_ALL_PACKAGES` to let the user pick the applications to exclude from VPN

## Licenses

AdAway is licensed under the GPLv3+.  
The file LICENSE includes the full license text.
For more details, check [the license notes](LICENSE.md).
