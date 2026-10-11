SPW 遥控插件

Salt Player for Windows Remote Control Plugin

通过手机远程控制电脑上的 Salt Player for Windows，支持播放、暂停、切歌、进度调整和播放状态同步。

功能特性

- 局域网自动发现：通过 UDP 发现电脑，无需手动输入 IP 地址。
- 跨网络连接：支持公网 IPv4、全局 IPv6 和 Tailscale。
- 实时状态同步：获取当前曲目、艺术家、专辑、播放状态和播放进度。
- 配对码鉴权：连接后需要进行配对验证。
- 轻量化实现：不依赖额外的 WebSocket 库。
- 不收集听歌统计：仅负责播放控制与状态传输。

下载与安装

下载地址： https://github.com/motian16/salt-remote-plugin/releases/latest

每个 Release 提供以下文件：

文件| 说明
"plugin-io.github.motian16.spwremote-<版本>.spmod"| 插件安装包，推荐使用
"spw-remote-<版本>.zip"| 与 ".spmod" 内容相同的 ZIP 副本
"SHA256SUMS.txt"| 下载文件的 SHA-256 校验值

安装步骤

1. 下载 ".spmod" 文件，也可以使用 ".zip" 副本。
2. 打开 Salt Player，进入「创意工坊」→「本地导入」。
3. 选择下载的插件文件并完成导入。
4. 在创意工坊中启用插件。

注意：导入插件不等于启用插件。 只有启用后，插件才会启动并监听连接。

启用成功后，可在提示信息中查看监听端口和局域网地址，例如：

SPW 遥控已监听 ws://0.0.0.0:52100（局域网：192.168.0.106）

配置说明

插件的配置入口位于 Salt Player 的插件配置页面。

配置项| 默认值| 说明
"remote.port"| "52100"| WebSocket 监听端口，范围为 1024–65535
"remote.udpPort"| "52101"| UDP 自动发现端口
"remote.token"| 首次运行随机生成| 连接配对码
"remote.discovery"| 开启| 是否启用局域网自动发现
"remote.publicIpProbe"| 开启| 是否探测公网 IPv4
"remote.trustLan"| 开启| 是否允许局域网发现时直接获取配对码

配对码

首次运行时，插件会生成随机配对码并保存到配置中，后续启动继续使用。

配对码格式为 "SPW-" 加 8 位大写字母或数字，例如：

SPW-7K2M9QX4

可以在配置页面修改配对码；留空或少于 4 个字符时，会重新生成随机配对码。

局域网免配对

开启后，同一局域网内的手机可以通过自动发现获取配对码，减少手动输入步骤。

关闭后，手机需要手动输入配对码。

- 家庭私人网络：可根据便利性决定是否开启。
- 宿舍、公司、公共 Wi-Fi：建议关闭。

安全提示： 开启此功能会将配对码附加到局域网 UDP 发现广播中，同一网络内的其他设备可能获取该配对码。

配置何时生效

除 "remote.trustLan" 外，其他配置项均在插件启动时读取。修改后请先停用插件，再重新启用。

配置页面还提供「查看本机网络地址」功能，可在日志中查看局域网、IPv6 和公网地址等信息。

连接方式

插件默认监听 "0.0.0.0"，可通过以下地址连接。

使用场景| 连接地址示例| 额外要求
同一局域网| "ws://192.168.0.106:52100"| 手机与电脑网络互通
Tailscale| "ws://100.x.y.z:52100"| 两端 Tailscale 网络互通
公网 IPv4| "ws://公网IP:52100"| 路由器配置 TCP 端口转发
全局 IPv6| "ws://[240e:xxxx::xxxx]:52100"| 网络支持 IPv6，且防火墙允许入站连接

以上地址仅为示例，请替换为实际地址。

网络配置注意事项

- WebSocket 默认使用 TCP "52100"，公网 IPv4 连接需要在路由器中配置对应的 TCP 端口转发。
- UDP "52101" 仅用于局域网自动发现，不需要进行公网端口转发。
- Tailscale 连接需要两端网络互通；必要时可通过 DERP 中继通信。
- Windows 防火墙可能阻止入站连接，请检查相关规则。
- 公网 IPv4、IPv6 和 Tailscale 连接均不会自动启用传输加密。

不建议直接将当前版本的 WebSocket 端口暴露给不可信网络。 当前协议使用明文 "ws://"，且存在未配对连接可能收到播放状态的问题，详见下方「安全注意事项」。

协议与数据能力

通信采用 WebSocket 文本帧，消息内容为 JSON，并通过 "t" 字段区分消息类型。

典型消息包括：

消息| 作用
"auth"| 提交配对码
"hello"| 获取协议与连接信息
"state"| 接收播放状态
"cmd"| 执行播放控制指令
"ping" / "pong"| 心跳检测
"netinfo"| 获取网络地址及诊断信息

完整协议字段、消息格式和指令定义请参阅：

- "协议参考" (docs/协议参考.md)
- "数据传输能力" (docs/数据传输能力.md)

当前接口限制

- 不提供封面数据：宿主公开播放接口不提供原始封面图片数据，手机端需要自行匹配曲库封面。
- 不提供曲目总时长：宿主公开接口不提供曲目时长，手机端需要通过本地曲库获取时长，才能准确计算进度百分比。
- 部分状态依赖反射：曲目 ID、播放状态和进度等信息可能因宿主内部实现变化而无法读取，具体可用情况以实际运行结果为准。

安全注意事项

当前版本存在以下安全限制：

项目| 当前行为
配对码| 首次运行随机生成并保存
未配对指令| 拒绝执行
未配对地址查询| 受到鉴权限制
UDP 发现广播| 默认不包含配对码；开启局域网免配对后会附带配对码
播放状态广播| 未按配对状态过滤，未配对连接也可能收到播放状态
传输加密| 未实现，使用明文 "ws://"

未配对连接可能获取播放信息

当前实现的播放状态广播会发送给所有已建立的 WebSocket 连接，不会检查连接是否已完成配对。

因此，即使设备尚未通过配对验证，也可能在后续状态广播中收到曲目标题、艺术家、专辑、文件路径和播放进度等信息。

配对码目前不能完全保护播放状态的隐私。 在该问题修复之前，请避免将服务暴露给不可信网络。

其他限制：

- 同时最多允许 8 个客户端连接，超出上限的连接会被断开。
- 未实现额外的指令限流机制。
- 尚未实现 "permessage-deflate" 压缩。
- Cloudflare Tunnel 等广域网中转方案尚未实现。

开发与构建

环境要求

组件| 要求
JDK| 25 或更高版本
Gradle| 使用仓库自带的 Gradle Wrapper
Kotlin| 2.4.20，由 Gradle 插件管理

注意：Salt Player 自带的运行时不包含完整的 Java 编译工具链，不能直接用于插件编译。

构建

Windows 下执行：

.\build.ps1

也可以手动指定 JDK：

$env:JAVA_HOME = "<你的 JDK 25 路径>"
.\gradlew.bat plugin

构建产物位于 "build/libs/"：

- "plugin-io.github.motian16.spwremote-<版本>.spmod"
- "spw-remote-<版本>.zip"
- "SHA256SUMS.txt"

".zip" 是 ".spmod" 的副本，两个文件应具有相同的 SHA-256。

发布新版本

版本号统一维护在 "build.gradle.kts" 的 "spmod" 配置中。

设置 "GITHUB_TOKEN" 后执行：

.\publish.ps1

脚本负责构建、检查版本与工作区状态、创建 Git 标签、推送代码并发布 GitHub Release。

如需仅执行本地构建和提交、不推送远端，可使用：

.\publish.ps1 -SkipPush

持续集成

GitHub Actions 会在 push 和 pull request 时执行构建，并上传构建产物；推送符合 "v*" 格式的 Git 标签时，还会自动创建 Release。

测试

仓库提供独立测试客户端，可在不修改手机 APP 的情况下验证配对流程。

详细说明见 "test-client/README.md" (test-client/README.md)。

示例：

cd test-client
npm install
node verify-auth.js <IP> 52100 <配对码> wrong-token

项目结构

src/main/kotlin/io/github/motian16/spwremote/
├── RemotePlugin.kt
├── RemotePlaybackExtension.kt
├── MediaState.kt
├── WsServer.kt
├── Discovery.kt
├── TrackReflector.kt
├── Protocol.kt
├── Json.kt
├── PluginSelfTest.kt
├── WsServerSelfTest.kt
└── DiscoverySelfTest.kt

src/main/resources/
└── preference_config.json

主要模块：

- "RemotePlugin.kt"：插件生命周期、配置与消息分发。
- "MediaState.kt"：播放状态管理、指令执行与广播。
- "WsServer.kt"：WebSocket 服务端。
- "Discovery.kt"：局域网发现与网络地址探测。
- "TrackReflector.kt"：读取宿主内部播放信息。
- "Protocol.kt"：协议定义与消息构造。

开发时需注意：插件构造函数中不要访问 "WorkshopApi"；不要调用 "changeExclusive"，以免违反宿主线程约束。

许可证

详见 "LICENSE" (LICENSE)。