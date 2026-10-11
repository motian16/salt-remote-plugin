# 测试客户端

不动 APP，先用几行命令验证「插件是否在跑、配对是否生效、协议是否通、指令是否真的执行」。

需要 Node.js（本机实测 v24）。

---

## 脚本一览

| 脚本 | 用途 | 依赖 | 实测状态 |
| --- | --- | --- | --- |
| `verify-auth.js` | **配对流程端到端验证**（11 项断言）—— 推荐先跑这个 | `ws` | ✅ **11/11 通过** |
| `check-connectivity.js` | TCP → WebSocket 握手 → 收帧的连通性自检 | 无 | ⚠️ 见下方说明 |
| `spw-remote-cli.js` | 交互式遥控 | `ws` | ⚠️ 只能连无鉴权的自测服务端 |
| `verify-ws.js` | WebSocket 线协议一致性（7 组 17 项断言） | `ws` | 需配合 `WsServerSelfTest` |
| `verify-discovery.js` | UDP 广播发现 | 无 | ⚠️ 断言的是旧 payload，见下方说明 |

零依赖的只有 `check-connectivity.js` 和 `verify-discovery.js`；
另外三个都 `require('ws')`，要先 `npm install`。

```powershell
cd test-client
npm install
```

---

## 一、装依赖并启动一个可测的目标

真实插件需要 Salt Player 里**启用**插件才能连。手边没有宿主时，
用自带的 `PluginSelfTest` 起一个「假宿主 + 真插件」，配对码固定是 `test-token-42`：

```powershell
# 1. 编译
.\gradlew.bat classes

# 2. 起自测宿主（ws=52300 udp=52301，运行 40 秒）
$cp = "build\classes\kotlin\main;build\resources\main;" +
      "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.4.20\*\kotlin-stdlib-2.4.20.jar;" +
      "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.pf4j\pf4j\3.12.0\*\pf4j-3.12.0.jar;" +
      "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.github.Moriafly.spw-workshop-api\spw-workshop-api\0.1.0-dev22\*\spw-workshop-api-0.1.0-dev22.jar;" +
      "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.slf4j\slf4j-api\2.0.6\*\slf4j-api-2.0.6.jar"
& "$env:JAVA_HOME\bin\java.exe" -cp $cp io.github.motian16.spwremote.PluginSelfTest 52300 52301 120
```

> `pf4j` 需要 `slf4j-api`，否则会 `NoClassDefFoundError: org/slf4j/LoggerFactory`。
> 提示 `SLF4J: No SLF4J providers were found` 是无害的。

启动成功的标志：

```
[selftest] 启动 RemotePlugin（ws=52300 udp=52301，配对码=test-token-42）
[spw-remote] 发现服务已启动，UDP 端口 52301
[selftest] toast[Success] SPW 遥控已监听 ws://0.0.0.0:52300（局域网：192.168.x.x）
```

**连真实插件时**把下面的 `127.0.0.1 52300` 换成插件 toast 里的地址和端口，
把 `test-token-42` 换成插件配置页里的配对码。

---

## 二、配对流程验证（推荐先跑）

```powershell
node verify-auth.js <IP> <端口> <配对码> wrong-token
```

例：

```powershell
node verify-auth.js 127.0.0.1 52300 test-token-42 wrong-token
```

实测输出（11 项全部通过）：

```
第 1 步：连接后插件是否立刻索要配对码
  ✅ 收到 authRequired
  ✅ 未配对时不推播放状态
第 2 步：配对码错误
  ✅ 收到 authFail
  ✅ 配对失败后仍不推状态
第 3 步：未配对时发播放指令
  ✅ 指令未被当作合法命令执行（没有 ack ok=true）
  ✅ 回复的是 authRequired（要求先配对）
第 4 步：配对码正确
  ✅ 收到 authOk
  ✅ 收到 hello
  ✅ 收到 state（配对后才有）
第 5 步：配对后发一条无害指令
  ✅ 收到 ack 回执
  ✅ 指令执行成功

通过 11 项，失败 0 项
```

> 不带参数直接跑会用默认配对码 `spw-1234`，和插件实际随机生成的 `SPW-XXXXXXXX`
> 对不上，第 4、5 步必然失败。**一定要把配对码传进去。**

---

## 三、⚠️ `check-connectivity.js` 的已知问题

```powershell
node check-connectivity.js 127.0.0.1 52300
```

它只打印**两个**步骤标题（不是三个），实测：

```
第 1 步：TCP 连通性
  ✅ TCP 端口可达
第 2 步：WebSocket 握手
  ❌ 阶段「connect」超时
```

**这是脚本自身的缺陷，不代表插件有问题。** 同一个端口上：

- 用原生 socket 手工发同样的握手请求，能拿到
  `HTTP/1.1 101 Switching Protocols` + 正确的 `Sec-WebSocket-Accept`；
- `verify-auth.js` 走完整的 WebSocket 客户端，11 项全过。

也就是说第 2 步的失败发生在脚本自己的连接建立阶段，与本插件的服务端无关。
**排查连通性请改用 `verify-auth.js`** —— 它覆盖面更广，而且断言更硬。

（脚本的失败分支文案本身是对的：`ECONNREFUSED` = 端口没监听，
`timeout` = 被防火墙丢弃，`EHOSTUNREACH` = 路由问题。）

---

## 四、⚠️ `spw-remote-cli.js` 与鉴权

```powershell
node spw-remote-cli.js <IP> [端口]
node spw-remote-cli.js --scan [端口]
node spw-remote-cli.js --url ws://1.2.3.4:52100
```

交互命令：`play` `pause` `next` `prev` `seek <毫秒>` `state` `hello`
`raw <json>` `help` `quit`。

**它没有 `auth` 命令。** 连上真实插件后，插件只会回 `authRequired`，
所有指令都会被拒。要手工验证可以：

```
spw> raw {"t":"auth","token":"SPW-你的配对码"}
spw> state
```

或者干脆用它连 `WsServerSelfTest` 那个**无鉴权**的自测服务端：

```powershell
java -cp <classes> io.github.motian16.spwremote.WsServerSelfTest 52199   # 固定跑 60 秒
node spw-remote-cli.js 127.0.0.1 52199
```

`ack ok=true` 只代表指令已被下发到宿主 API，
**是否真的换了曲要看随后的 `state` 推送** —— 这一条能区分「协议通」和「宿主真的动了」。

---

## 五、⚠️ `verify-discovery.js` 与当前 payload 不符

它断言广播包里有一个 `eps` 数组，但**真实插件的广播里没有 `eps`**：

```json
{"t":"spw-remote","name":"Salt Player for Windows",
 "plugin":"io.github.motian16.spwremote","version":"1.0",
 "wsPort":52100,"udpPort":52101,"auth":true,"token":"SPW-XXXXXXXX"}
```

`eps` 只出现在 `DiscoverySelfTest` 的测试 payload 里。
所以这个脚本对真实插件会在 `eps` 相关的两项上报失败，**脚本待更新**。
当前广播包的准确格式见 [`../docs/协议参考.md`](../docs/协议参考.md) 第五节。

---

## 六、`verify-ws.js` —— 插件的 WebSocket 实现可信吗？

宿主运行时**没有 `jdk.httpserver`**，插件的 WebSocket 服务端是手写的（只用 `java.base`），
所以它必须被单独验证过。`verify-ws.js` 覆盖 7 组共 17 项断言：

| 组 | 验证内容 |
| --- | --- |
| 1 | 握手：HTTP 101、`Upgrade: websocket`、`Sec-WebSocket-Accept` 存在 |
| 2 | 服务端主动推送：`hello` 与 `state`；**中文**与**反斜杠路径**能正确解码 |
| 3 | 上行掩码帧解码 + UTF-8 内容原样往返 |
| 4 | 5000 字节消息（验证 16 位长度分支）内容完整 |
| 5 | 应用层 `ping`/`pong` 且 `ts` 原样回传 |
| 6 | WS 协议级 ping/pong 控制帧 |
| 7 | 优雅关闭（close 帧） |

它依赖 `WsServerSelfTest` 的 `echo` 应答和固定内容，**对真实插件不适用**：

```powershell
# 终端 1
java -cp <classes> io.github.motian16.spwremote.WsServerSelfTest 52199

# 终端 2
node verify-ws.js 52199
```

> `WsServerSelfTest` 是 `main()` 入口，**不影响插件在宿主中的运行**，
> 它只是提供了一个不依赖宿主就能验证线协议的途径。它**没有鉴权**。

---

## 七、常见坑

| 现象 | 原因 |
| --- | --- |
| LAN 不通但 Tailscale 通 | Windows 防火墙拦了物理网卡的入站，Tailscale 走独立网卡所以没事 |
| 两边都通不了 | 插件其实没启用（只是导入了 `.spmod`） |
| 连上但一直收到 `authRequired` | 没发 `auth`，或配对码不对（注意区分大小写） |
| 连上了但没有任何 `state` | 配对之前本来就收不到；配对后宿主没在播就只显示空曲目 |
| `state` 里没有时长/百分比 | 宿主公开接口没有 `duration`，见 [`../docs/数据传输能力.md`](../docs/数据传输能力.md) |
| `state.lyric` 一直是空的 | 当前版本歌词回调没接上，属已知问题 |
| 改了端口不生效 | 端口只在 `start()` 里读一次，需**停用再启用**插件 |
| 偶发断开 | 60 秒收不到任何字节就断开（服务端每 10 秒发 PING）。客户端应实现自动重连，建议自己每 30 秒发一条应用层 `ping` |
