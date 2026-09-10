# Copilot Instructions

## ai的agent要求

- 要求修改时直接修改不二次征求同意
- 尽量最小化改动以避免无法预料的错误
- 回复时使用中文

## UI与交互约定

- 所有 Compose 组件优先使用 Miuix 主题库（如 `MiuixTheme`、`MiuixIcons`、`Button`、`Card` 等）。查阅 Miuix 用法一律通过 `miuix-mcp`，不要去抓取官方文档网页。
- 导航使用 Miuix Navigation3 + NavigationEvent。
- 页面根容器默认背景统一使用 `MiuixTheme.colorScheme.background`：内容区背景一律用 `background`，TopAppBar 可保留默认 `surface` 形成色差。
- 独立页面（含子页、开发者模式等）优先复用公共组件 `ScrollableTopAppBarPage`。

### 应用 API 版本

minSdk：`:app`（主应用）与 `:core`（核心库）minSdk = 31（Android 12），其余库模块（`:base`、`:data`、`:superislandui`、`:lsp`、`:nativecore` 等）minSdk = 29（Android 10）。请勿为任一模块声明的 minSdk 以下版本编写兼容性代码。

- 代码风格遵循 Kotlin 官方规范（`kotlin.code.style=official`）。
- 如需扩展功能或集成新依赖，优先查阅 `miuix-mcp` 与本项目现有实现。
  本应用不会上架 Google Play 等应用商店，仅限私有分发和自用，且没有对公网提供服务的计划。

### 模块结构与文件用途

模块划分、目录树、各模块类与方法的用途，统一以 **[`Docs/文件用途基础说明.md`](Docs/文件用途基础说明.md)** 为准，本文件不再单独列举。

在使用工具方法前，请先查阅该文档对应模块的说明，确认是否已有可用实现。

如果已有类似功能的方法，请优先使用现有方法，避免重复实现。如果没有合适的方法，可以根据项目的代码风格和规范自行实现新的工具方法。注意，新方法如果仅是对旧方法的拓展，请在旧方法的基础上进行修改，而不是新建一个类似的方法。

当模块结构、目录或文件用途发生变化时，同步更新 `Docs/文件用途基础说明.md`。

### Git 分支与合并策略

- 功能开发在独立分支进行，合并到 `main` 时推荐使用非快进合并 (`--no-ff`) 以保留分支提交记录。
- 当前长期 `dev` 分支 为开发主线，`main` 分支为发布来源。


