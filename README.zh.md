# 流年 (LiuNian) - 短视频时间管理工具

一款基于 Android 无障碍服务 (AccessibilityService) 开发的短视频限制工具。通过自动识别主流短视频应用（如 B 站、抖音）的竖屏视频页面，在达到设定时长后自动退出目标应用，从而帮助用户控制使用时间。

## 功能特性

- 精准识别：通过 Activity 类名与 UI 节点特征识别短视频页面，不误判普通横屏视频。
- 时间控制：支持自定义单次限时、每日总限额以及拦截冷却时间。
- 多应用支持：内置主流 App 规则库，可通过 UI 界面勾选需要限制的应用。
- 规则自定义：支持在应用内修改类名关键字与 ViewId 关键字，以应对第三方 App 的版本更新。
- 界面与外观：遵循 Material Design 3 设计规范，提供环形进度条，支持深色模式。
- 隐私安全：完全离线运行，未申请网络权限，所有数据仅保存在本地。

## 技术栈

- 语言：Kotlin
- UI 框架：AndroidX, Material Design 3
- 核心组件：AccessibilityService, NotificationChannel, Handler
- 数据持久化：SharedPreferences

## 下载与安装

你可以前往本仓库的 [Releases](https://github.com/C-H-KEI/流年/releases) 页面下载最新版本的 apk 文件进行安装。

## 使用注意事项

1. 必须授予无障碍权限：请在系统设置中开启本应用的无障碍服务，否则无法自动检测和拦截。
2. 后台保活设置：针对国内定制系统（如荣耀 MagicOS、小米 MIUI 等），需在系统设置中关闭电池优化，并允许应用自启动和后台活动。否则系统可能会在后台杀掉服务，导致限制失效。

## 开源协议

本项目基于 MIT License 开源，详见 [LICENSE](LICENSE) 文件。