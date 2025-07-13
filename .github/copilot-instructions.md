# Copilot Instructions

## 项目定位
- 安卓通知转发应用，支持多设备间通知内容的双向转发，含应用跳转能力。
- 具体功能、页面与权限要求详见 `README.md`。


## ai的agent要求
- 要求修改时直接修改不二次征求同意
### bug修改的要求
- 所有bug修改在单独的临时分支上进行，在人工确认bug修复成功后以合并提交git到主分支,并删除该临时分支
- 分析日志时结合代码详情给出修改建议并直接修改后直接提交git和对应git消息到bug修复的临时分支中
- 代码bug修改后直接提交git和对应git消息到bug修复的临时分支中

## 架构与主要组件
- 主模块为 `app`，核心源码位于 `app/src/main/java/com/xzyht/notifyrelay/`。
- UI采用 Jetpack Compose，全部界面风格统一使用 Miuix Compose 主题（`miuix-main/miuix` 子模块）。
- 入口页面为 `GuideActivity`（首次启动引导权限），主页面为 `MainActivity`（导航栏切换核心功能）。
- 主要页面：
  - 设备与转发设置（`DeviceForwardScreen`）：设备发现、连接、转发规则、黑名单管理。
  - 通知历史
  参见README.md 中的功能页面说明。

## UI与交互约定
- 所有 Compose 组件优先使用 Miuix 主题库（如 `MiuixTheme`、`MiuixIcons`、`Button`、`Card` 等），详见[官方组件文档](https://miuix-kotlin-multiplatform.github.io/miuix/zh_CN/components/)。

## 构建与依赖
- 构建仅限 Android Studio IDE，不支持命令行（如 gradlew）。
- 依赖管理与版本锁定见 `app/build.gradle.kts`，所有 kotlin-stdlib 强制使用 1.9.23。
- Miuix 主题库通过 `implementation(project(":miuix-main:miuix"))` 集成，相关依赖版本需与 Compose 1.8.x 兼容。

## 数据流与服务边界
- 通知内容通过系统权限获取，转发依赖设备间网络通信（需同一局域网）。
- 设备发现与连接、转发规则、黑名单等均在首页 Tab 实现，历史记录独立页面展示。
- 权限状态检测与跳转均在引导页完成，主流程为：引导页授权 → 主页面功能。


## 其他说明
- 仅记录已实现的模式，理想方案请勿补充。
- 代码风格遵循 Kotlin 官方规范，使用 Ktlint 进行格式化。
- 如需扩展功能或集成新依赖，优先查阅 Miuix 官方文档与本项目现有实现。
本应用不会上架 Google Play等应用商店，仅限私有分发和自用,且没有对公网提供服务的计划。
---