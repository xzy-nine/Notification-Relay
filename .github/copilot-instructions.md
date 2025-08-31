# Copilot Instructions
## ai的agent要求
- 要求修改时直接修改不二次征求同意
## UI与交互约定
- 所有 Compose 组件优先使用 Miuix 主题库（如 `MiuixTheme`、`MiuixIcons`、`Button`、`Card` 等），详见[官方组件文档](https://miuix-kotlin-multiplatform.github.io/miuix/zh_CN/components/)。

## 构建与依赖
- 构建仅限 Android Studio IDE，不支持命令行（如 gradlew）。
- 依赖管理与版本锁定见 `app/build.gradle.kts`，所有 kotlin-stdlib 强制使用 1.9.23。
- Miuix 主题库通过 `implementation(project(":miuix-main:miuix"))` 集成，相关依赖版本需与 Compose 1.8.x 兼容。
### 应用 API 版本
不兼容api 26以下版本,请勿使用相应的兼容性代码
- 代码风格遵循 Kotlin 官方规范，使用 Ktlint 进行格式化。
- 如需扩展功能或集成新依赖，优先查阅 Miuix 官方文档与本项目现有实现。
本应用不会上架 Google Play等应用商店，仅限私有分发和自用,且没有对公网提供服务的计划。
---