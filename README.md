# SPW 遥控插件（Salt Player for Windows）

用手机遥控电脑上的 Salt Player for Windows。插件在电脑侧起一个 **WebSocket** 服务，
手机连上后就能播放/暂停/切歌/拖进度，并实时收到当前播放状态。

- **局域网**：UDP 自动发现，手机不用手输 IP
- **公网直连**：有动态公网 IPv4 或全局 IPv6 时，手机不在同一网段也能连
- **Tailscale**：走同一套协议，APP 侧不需要区分
- **配对码鉴权**：连接必须先用配对码配对，配对前拿不到播放状态

> 本插件只做**播放控制**与状态回推，不做听歌统计。

---

## 一、下载与安装

**发行版**：<https://github.com/motian16/salt-remote-plugin/releases/latest>

每个 Release 提供三个附件：

| 附件 | 说明 |
| --- | --- |
| `plugin-io.github.motian16.spwremote-<版本>.spmod` | 插件包，推荐用这个 |
| `spw-remote-<版本>.zip` | **与 `.spmod` 内容完全相同**的副本，只是扩展名不同 |
| `SHA256SUMS.txt` | 上面两个文件的 SHA-256 |

安装步骤：

1. 下载 `.spmod`（或 `.zip`）
2. Salt Player → **创意工坊** → **本地导入** → 选择该文件
3. **在创意工坊里启用它**

> **导入 ≠ 启用。** 插件只有被启用后 `start()` 才会执行，端口才会开始监听。
> 启用成功会弹一条 toast，里面是监听地址和局域网地址：
> `SPW 遥控已监听 ws://0.0.0.0:52100（局域网：192.168.0.106）`

校验下载到的包：

```powershell
# 和 Release 里的 SHA256SUMS.txt 对照
Get-FileHash .\plugin-io.github.motian16.spwremote-1.2.spmod -Algorithm SHA256
```

> **⚠️ 构建不可逐字节复现。** 同一份源码在 Windows 和 GitHub Actions 上分别构建，
> 产出的 `.spmod` **内容相同但 SHA-256 不同** —— zip 会记录每个条目的
> 修改时间，两次构建的时间戳不一样。
> 所以 `SHA256SUMS.txt` 只对应**那一次 Release 里上传的那个文件**，
> 你自己重新构建得到的包校验值必然不同，这是正常的，不代表文件有问题。
> 想确认包没被动过，就用 Release 附带的 `SHA256SUMS.txt`；
> 想知道包是谁构建的，各条 Release 的说明里都写明了对应的 tag 与提交。

`.spmod` 就是标准 zip，可以自己解开看：

```powershell
# 包内顶层只有 classes/，插件元数据在 classes/META-INF/MANIFEST.MF
tar -tf plugin-io.github.motian16.spwremote-1.2.spmod
```

包内结构（`lib/` 为空，归档里没有这一层目录）：

```
plugin-io.github.motian16.spwremote-1.2.spmod
└── classes/
    ├── META-INF/MANIFEST.MF      ← 插件元数据，见下
    ├── META-INF/extensions.idx   ← PF4J 扩展点索引
    ├── preference_config.json    ← 配置界面声明
    └── io/github/motian16/spwremote/*.class
```

包内 MANIFEST 全部字段：

```
Plugin-Class:            io.github.motian16.spwremote.RemotePlugin
Plugin-Id:               io.github.motian16.spwremote
Plugin-Version:          1.5
Plugin-Provider:         motian16
Plugin-Name:             SPW 远程遥控
Plugin-Description:      手机遥控电脑播放（局域网/公网）
Plugin-Open-Source-Url:  https://github.com/motian16/salt-remote-plugin
Plugin-Has-Config:       true
```

`Plugin-Open-Source-Url` 由 `build.gradle.kts` 里 `spmod { PluginOpenSourceUrl = ... }`
声明，宿主会用它展示插件来源（开源地址），所以**每个版本都必须带上**。
`PluginDescription` 注释里那条「单行 72 字节」的限制只针对描述这一类中文字段。

---

## 二、为什么自带 WebSocket 实现

宿主运行时是 jpackage 裁剪过的自定义 JRE，`runtime/legal/` 下**只有 18 个模块**：

```
java.base  java.compiler  java.datatransfer  java.desktop  java.instrument
java.logging  java.management  java.naming  java.prefs
java.security.jgss  java.security.sasl  java.sql  java.transaction.xa  java.xml
jdk.accessibility  jdk.crypto.ec  jdk.security.auth  jdk.unsupported
```

**`jdk.httpserver` 与 `java.net.http` 都不在里面** —— 宿主既没有内置 HTTP 服务端，
也没有内置 HTTP 客户端。所以 `WsServer.kt` 只用 `java.base` 的
`ServerSocket` / `Socket` / `MessageDigest` / `Base64` 实现了 RFC6455 的服务端子集
（文本帧、掩码解码、分片重组、ping/pong、close、心跳与读空闲超时）。

好处是 **`.spmod` 的 `lib/` 保持为空**，插件包很小，也不会往宿主那个只有 `-Xmx512M`
的堆里塞额外依赖。`jdk.crypto.ec` 在，所以将来要上 `wss://`（TLS）是可用的；
出站 TLS 已经用在公网 IP 探测上（见 `Discovery.kt` 的 `PublicIpResolver`）。

---

## 三、环境要求与构建

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| JDK | **25 或更高** | `jvmToolchain(25)` + `JVM_25`，API 的字节码版本也是 25 |
| Gradle | 无需预装 | 用仓库自带 wrapper（Gradle 9.8.0） |
| Kotlin | 2.4.20 | Gradle 插件自动拉取 |

```powershell
# 自带脚本（会自己找 JDK 25，找不到才报错）
.\build.ps1

# 或者手动
$env:JAVA_HOME = "<你的 JDK 25 路径>"
.\gradlew.bat plugin
```

> 宿主的 `runtime/bin` 里只有 DLL，没有 `javac`/`jar`，**不能拿宿主的 JRE 来编译**。

产物：

```
build/libs/plugin-io.github.motian16.spwremote-1.2.spmod   ← 约 132 KB
build/libs/spw-remote-1.2.zip                             ← 同一文件，改名副本
build/libs/SHA256SUMS.txt                                 ← 两者的 SHA-256
```

> **Release 里两个附件都要传。** 宿主发布器（Steam 创意工坊）只接受 `.spmod`，
> 但一部分导入方式和第三方工具只认 `.zip`，所以每个 Release 同时提供
> `.spmod` 与内容完全相同的 `.zip`，两者 SHA-256 一致。

### 发布新版本

版本号的唯一来源是 `build.gradle.kts` 里 `spmod { PluginVersion = "..." }`。
改完跑：

```powershell
$env:GITHUB_TOKEN = '<你的 token>'
.\publish.ps1
```

它会依次：解析版本号 → 检查工作区是否干净 → 构建 → 校验包内 MANIFEST 与
`build.gradle.kts` 一致 → 确认 tag 未占用 → 提交并打 tag `v<版本>` → 推送 →
建 GitHub Release 并上传三个附件（上传后会核对 GitHub 回传的 sha256）。
加 `-SkipPush` 可以只构建提交不推送。

脚本需要 `GITHUB_TOKEN` 环境变量，令牌只以一次性 Authorization 头传给 git，
不写进 `.git/config`。如果本机上网走系统代理，脚本会从注册表读出代理并以
`HTTP(S)_PROXY` 注入 git —— git 默认**不**使用系统代理，否则推送会报
`Connection was reset`。

### CI

`.github/workflows/build.yml` 会在每次 push / PR 时用 JDK 25 构建 `.spmod` 并
把它作为 workflow artifact 上传；推送 `v*` tag 时额外自动创建 Release。

> 首次提交时 `gradlew` 在 Windows 上被检出了 `100644`（无执行位），
> 导致 Linux runner 上 `Permission denied`。已在 git index 里改成 `100755`：
> ```powershell
> git update-index --chmod=+x gradlew
> ```
> 在 Windows 上重新克隆后如果 CI 又报 `Permission denied`，先查 `git ls-files -s gradlew`
> 是不是 `100755`。

---

## 四、配置

插件配置页由 `src/main/resources/preference_config.json` 声明，配置键如下。

**修改后需要「停用再启用插件」才生效的**（只在 `start()` 时读一次）：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `remote.port` | `52100` | WebSocket 监听端口，取值范围 1024–65535 |
| `remote.udpPort` | `52101` | 自动发现 UDP 端口 |
| `remote.token` | 首次运行随机生成 | 配对码。留空或少于 4 字符会重新随机生成 |
| `remote.discovery` | 开 | 关闭后手机必须手动填 IP 和端口 |
| `remote.publicIpProbe` | 开 | 探测公网 IPv4。纯内网环境可以关掉 |

> 上面两组对应**两个不同的配置文件**：`config.json`（远程遥控）与
> `discovery.json`（发现与公网）。官方示例里每个配置组用各自独立的文件，
> **两组共用同一个文件会让设置页渲染不出任何控件**（实测踩过，见下方"已知坑"）。

**改完立即生效的**：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `remote.trustLan` | 开 | 「局域网免配对」：广播里附带配对码，手机扫到即连 |

配置页还有一个「查看本机网络地址」按钮，会把局域网 / IPv6 / 公网地址打到日志里。

### 配对码

格式 `SPW-` + 8 位大写字母数字（字母表已去掉容易看错的 `I/O/0/1`），
例如 `SPW-7K2M9QX4`。首次运行用 `SecureRandom` 生成并**写回配置**，
之后一直沿用，也可以在配置页自己改。

### 「局域网免配对」的取舍

开着的时候，手机发现设备后能自动拿到配对码，用户一个字符都不用打。
代价是**同网段的人抓一个 UDP 包就能拿到配对码**。

- 家里自己的 Wi-Fi：开着，便利换安全，可以接受
- 宿舍、公司、咖啡厅这类共享网络：**建议关掉**，关掉后手机必须手填配对码

---

## 五、怎么连

插件绑定的是 `0.0.0.0`，所以下面几种地址**同时**可用，不需要改配置：

| 场景 | 手机填什么 |
| --- | --- |
| 同一局域网 | `ws://192.168.x.x:52100`（配置页看局域网地址） |
| Tailscale | `ws://100.x.y.z:52100` |
| 有动态公网 IPv4 | `ws://<公网 IP>:52100`（记得在路由器上做端口转发） |
| 有全局 IPv6 | `ws://[240e:...]:52100`（需要在路由器放行入站） |

要点：

- 端口转发只做 TCP `52100`；UDP `52101` 只在局域网内做发现用，不必转发。
- Tailscale 两端登录**同一账号**即可，会自动打洞，失败回落到 DERP 中继。
- **不要**把 Tailscale 的 MagicDNS 主机名当唯一方案，APP 里最好能直接填 IP。
- Windows 防火墙若拦截入站，局域网会失败但 Tailscale 通常仍可用（走自己的网卡）。
- 对外网暴露端口前请把配对码换强一点 —— 协议本身是**明文 `ws://`，没有传输加密**。

---

## 六、协议

WebSocket 文本帧承载 JSON，所有消息都有 `t` 字段。**完整字段目录见
[`docs/协议参考.md`](docs/协议参考.md)**，这里只给最短路径。

```
手机 → 插件                                  插件 → 手机
{"t":"auth","token":"SPW-XXXXXXXX"}    →     authOk
                                       →     hello   {"ok":true,"protocol":2,...}
                                       →     state   {"playing":...,"title":...}
{"t":"ping","ts":123}                  →     pong    {"ts":123}
{"t":"netinfo"}                        →     netinfo {"lan":[...],"ipv6":[...],...}
{"t":"cmd","id":1,"action":"play"}     →     ack     {"id":1,"ok":true}
```

**配对是第一步。** 连上后插件先发 `authRequired`，在收到正确的 `auth` 之前，
`cmd` / `hello` / `netinfo` 一律回 `authRequired`（只有 `ping` 放行）。
配对成功后插件会主动对一次账，所以「本来就在播」的情况下也能立刻拿到曲目。

`state` 的 11 个字段：`playing` `title` `artist` `album` `albumArtist` `path`
`pos` `id` `lyric` `ts`（`pos` 单位毫秒；`id`/`lyric` 拿不到时是空串）。

### 宿主当前版本的接口限制

当前宿主公开接口（`PlaybackExtensionPoint.MediaItem`）只给 **5 个字段**：
`title`、`artist`、`album`、`albumArtist`、`path`。

- **封面**：`library.getCoverById(id)`（需 LIBRARY_READ）能拿到内嵌封面原始字节，插件 1.5 起会压成 640px JPEG 随 `trackExtra` 下发。
  APP 仍优先用自己的曲库封面；本机没有时才用插件这一份。
- **没有曲目总时长**：所以 `state` 里没有 `duration`，进度条百分比得靠
  APP 侧匹配到的本地曲目时长来算。
- **`id` 与播放/暂停、进度靠反射**读宿主内部字段（`TrackReflector.kt`），
  属于**尽力而为**：读不到就保留事件推来的值，不会报错。
  `netinfo` 里的 `trackReflector` 字段会告诉你反射是否可用、命中了哪个字段。

详见 [`docs/数据传输能力.md`](docs/数据传输能力.md)。

---

## 七、测试（不动 APP）

见 [`test-client/README.md`](test-client/README.md)。最关键的一条是配对流程验证：

```powershell
cd test-client
npm install
node verify-auth.js <IP> 52100 <配对码> wrong-token   # 11 项断言
```

---

## 八、安全模型（请按实际行为理解）

| 机制 | 现状 |
| --- | --- |
| 配对码 | 首次运行 `SecureRandom` 生成，写回配置，显示在配置页 |
| 未配对时的指令 | 拒绝，回 `authRequired` |
| 未配对时的地址列表 | 不给（`netinfo` 被鉴权门挡住） |
| UDP 广播内容 | 只有身份和端口；「局域网免配对」**开启时额外带配对码** |
| 未配对时的状态广播 | **拿不到**：`WsServer.broadcast` 只发给 `Client.authed = true` 的连接 |
| 配对失败限流 | 同一 IP 60 秒内失败 5 次即断开连接（close 1008） |
| 指令限流 | 每连接 1 秒最多 15 条，超出回 `ack(ok=false,"指令过于频繁，已忽略")` 且不执行 |
| 传输加密 | 无。局域网明文 `ws://` |

**已修复（1.4）**：`state` 的广播出口以前会发给**所有已建立的连接**，于是"连上但没配对"的设备
在下一次状态广播时也能收到 `playing/title/artist/path/pos`。现在出口按配对状态过滤
（`WsServer.Client.authed`），`MediaState` 也用**已配对连接数**做"没人听就不构造 JSON"的短路。
`docs/协议参考.md`、`docs/数据传输能力.md` 的对应描述已同步。

其他已知限制：

- 连接数上限 `maxClients = 8`，超出直接断开，不做排队。
- 广域网无中转时的方案（Cloudflare Tunnel 等）见
  `../网络遥控与听歌统计插件-架构设计.md` §2.5，尚未实现。

---

## 九、代码结构

```
src/main/kotlin/io/github/motian16/spwremote/
├── RemotePlugin.kt              # 主类：生命周期、配置、消息分发、配对鉴权
├── RemotePlaybackExtension.kt   # @Extension：接收宿主播放事件
├── MediaState.kt                # 状态跟踪 + 广播节流 + 指令执行（PlaybackController）
├── WsServer.kt                  # 纯 java.base 的 RFC6455 WebSocket 服务端
├── Discovery.kt                 # UDP 广播自动发现 + NetworkProbe + PublicIpResolver
├── TrackReflector.kt            # 反射读宿主内部字段拿曲目/播放态/进度
├── Protocol.kt                  # 协议常量、消息构造、MediaSnapshot
├── Json.kt                      # 极简 JSON（避免引入第三方库）
├── PluginSelfTest.kt            # 自测：假宿主 + 真插件
├── WsServerSelfTest.kt          # 自测：只用 java.base 的 WS 服务端
└── DiscoverySelfTest.kt         # 自测：UDP 广播与地址探测
src/main/resources/
└── preference_config.json       # 配置界面声明
```

### 两个必须遵守的宿主约束

1. **不要在构造函数里访问 `WorkshopApi`。**
   宿主创建插件实例的时机早于 `WorkshopApi.instance` 注入。
   所有 API 访问都要推迟到 `start()` 之后 —— 本插件的构造函数只做字段初始化。
2. **不要碰 `changeExclusive`。**
   它必须在主线程调用，且**禁止在 `onIsPlayingChanged` 回调里调用**。本插件完全不用它。

### 线程模型

| 来源 | 线程 |
| --- | --- |
| WebSocket 读循环 | 每连接一个 `spw-remote-client` 线程 |
| 状态广播 | 单线程 `spw-remote-broadcast`（串行，天然避免并发写） |
| 周期对账（每 4 秒） | 单线程 `spw-remote-poll` |
| 指令执行 | 单线程 `spw-remote-command`，带 3 秒超时 |
| 启动期探测 / 公网 IP | 单线程 `spw-remote-bg` |
| UDP 发现 | `spw-disc-broadcast` + `spw-disc-listen` |
| WebSocket 心跳（每 10 秒） | `spw-remote-heartbeat` |

`MediaState` 内部用锁保护快照，广播统一丢到单线程调度器，既保证线程安全，
也把"每秒一个位置事件"节流成最多每秒一条 `state`（最小间隔 900ms）。
所有线程都是 daemon。

---

## 十、已知坑

| 现象 | 原因 |
| --- | --- |
| 插件装上了、能启用，但**设置页一项都不显示**（空白） | `preference_config.json` 里**多个配置组共用了同一个 `config` 文件**。官方示例中每组用各自的文件，共用会让设置页渲染不出来。本插件 v1.2 及以前就有这个问题，v1.3 拆成 `config.json` + `discovery.json` 后正常 |
| 改了端口/开关不生效 | 除 `remote.trustLan` 外都在 `start()` 里只读一次，需停用再启用 |
| CI 报 `./gradlew: Permission denied` | `gradlew` 在 Windows 上被检出为无执行位，见「发布新版本 → CI」 |

---

## 十一、许可

见 [LICENSE](LICENSE)。
