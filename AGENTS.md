# Copilot Instructions

## ai的agent要求

- 要求修改时直接修改不二次征求同意
- 尽量最小化改动以避免无法预料的错误
- 回复时使用中文

## UI与交互约定

- 所有 Compose 组件优先使用 Miuix 主题库（如 `MiuixTheme`、`MiuixIcons`、`Button`、`Card` 等），详见[官方组件文档](https://compose-miuix-ui.github.io/miuix/zh_CN/components/)。
- 导航使用 Miuix Navigation3 + NavigationEvent。
- 页面根容器默认背景统一使用 `MiuixTheme.colorScheme.background`：内容区背景一律用 `background`，TopAppBar 可保留默认 `surface` 形成色差。
- 独立页面（含子页、开发者模式等）优先复用公共组件 `ScrollableTopAppBarPage`。

### 应用 API 版本

minSdk = 29 (Android 10)，不兼容 API 29 以下版本，请勿使用相应的兼容性代码。

- 代码风格遵循 Kotlin 官方规范（`kotlin.code.style=official`）。
- 如需扩展功能或集成新依赖，优先查阅 Miuix 官方文档与本项目现有实现。
  本应用不会上架 Google Play 等应用商店，仅限私有分发和自用，且没有对公网提供服务的计划。

### 模块结构 (7个模块)

- `:app` — 主应用模块，含 UI、同步、服务等
- `:base` — 基础工具库
- `:core` — 核心工具库
- `:data` — 数据层模块（Room 数据库等）
- `:superislandui` — 悬浮岛 UI 模块（独立维护）
- `:checkupdata` — 更新检查（Git 子模块）
- `:scrcpy` — Scrcpy 投屏（Git 子模块）

### 应用工具方法规范

在使用工具方法前，请先查看以下工具包中的实现：

**基础模块 (`:base`)**：

- `base/src/main/java/notifyrelay/base/util` (基础工具类：DeviceUtils（含isTablet平板判断）、HapticFeedbackUtils、IntentUtils、Logger、PermissionHelper、ThemeSettingsManager、ToastUtils)

**核心模块 (`:core`)**：

- `core/src/main/java/notifyrelay/core/util` (核心工具类：BatteryIconConverter、BatteryUtils、DataUrlUtils、EncryptionManager、MediaStoreHelper、ServiceManager)

**数据模块 (`:data`)**：

- `data/src/main/java/notifyrelay/data/database` (Room 数据库：DAO、Entity、Migration)
- `data/src/main/java/notifyrelay/data/database/repository` (数据库仓库：DatabaseRepository)
- `data/src/main/java/notifyrelay/data/config` (配置管理：AppConfig、DeviceInfoManager、Scrcpy 相关)
- `data/src/main/java/notifyrelay/data` (持久化：PersistenceManager、StorageManager)

**主模块 (`:app`)**：

- `app/src/main/java/com/xzyht/notifyrelay/util` (通用工具类，当前仅 `ApkArchMatcher.kt`)
- `app/src/main/java/com/xzyht/notifyrelay/ui/common` (UI 通用工具：Theme、SystemBarUtils、DoubleClickConfirm、NavigationEventProvider)
- `app/src/main/java/com/xzyht/notifyrelay/ui/navigation` (导航：Navigator、Routes)
- `app/src/main/java/com/xzyht/notifyrelay/servers` (服务层：通知监听、剪贴板、媒体控制等)
- `app/src/main/java/com/xzyht/notifyrelay/servers/appslist` (应用列表管理、图标缓存)
- `app/src/main/java/com/xzyht/notifyrelay/servers/clipboard` (剪贴板同步相关)
- `app/src/main/java/com/xzyht/notifyrelay/sync` (同步层：协议、发现、心跳、FTP、消息发送等)
- `app/src/main/java/com/xzyht/notifyrelay/sync/notification` (通知同步处理：NotificationProcessor、StatusProcessor、SuperIslandProcessor)
- `app/src/main/java/com/xzyht/notifyrelay/feature/device` (设备端：模型、接收器、仓库)
- `app/src/main/java/com/xzyht/notifyrelay/feature/device/service` (设备连接管理：DeviceConnectionManager)
- `app/src/main/java/com/xzyht/notifyrelay/feature/notification/backend` (通知后端过滤：BackendLocalFilter、BackendRemoteFilter)
- `app/src/main/java/com/xzyht/notifyrelay/feature/notification/data` (通知数据：ChatMemory)
- `app/src/main/java/com/xzyht/notifyrelay/feature/notification/superisland` (浮岛配置、格式化、生命周期、图片处理)

**悬浮岛模块 (`:superislandui`)**：

- `superislandui/src/main/java/github/xzynine/superislandui/common` (浮岛通用：SuperIslandManager、协议、预览、文本分割器等)
- `superislandui/src/main/java/github/xzynine/superislandui/floating/common` (浮岛渲染：CommonCompose、FocusIconResolver、SuperIslandImageUtil)
- `superislandui/src/main/java/github/xzynine/superislandui/model` (浮岛数据模型与解析器)
- `superislandui/src/main/java/github/xzynine/superislandui/floating` (浮岛各组件实现)

如果已有类似功能的方法，请优先使用现有方法，避免重复实现。如果没有合适的方法，可以根据项目的代码风格和规范自行实现新的工具方法。注意，新方法如果仅是对旧方法的拓展，请在旧方法的基础上进行修改，而不是新建一个类似的方法。

### Git 分支与合并策略

- 功能开发在独立分支进行，合并到 `main` 时推荐使用非快进合并 (`--no-ff`) 以保留分支提交记录。
- 当前无长期 `dev` 分支，`main` 为开发主线和发布来源。

### 构建与测试规范

- 在构建与测试时，第一次构建或测试禁止使用筛选输出，以避免隐藏错误信息。
- 禁止使用2>&1
- 禁止使用 `&&` 运算符
