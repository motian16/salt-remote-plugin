SPW 遥控插件

Salt Player for Windows 远程控制插件，通过 WebSocket 将电脑端的播放状态同步到手机，并支持远程播放控制。

功能

- 播放、暂停、切歌、调整播放进度
- 同步当前曲目、艺术家、专辑及播放状态
- 局域网自动发现设备
- 支持公网 IPv4、IPv6 和 Tailscale 连接
- 使用配对码进行连接鉴权

下载与安装

下载地址："GitHub Releases" (https://github.com/motian16/salt-remote-plugin/releases/latest)

1. 下载 ".spmod" 插件包。
2. 在 Salt Player 中进入「创意工坊」→「本地导入」。
3. 导入后，在创意工坊中启用插件。

启用成功后，插件会启动 WebSocket 服务，默认端口为 "52100"。

配置与连接

配置项| 默认值| 说明
"remote.port"| "52100"| WebSocket 端口
"remote.udpPort"| "52101"| 局域网自动发现端口
"remote.token"| 随机生成| 连接配对码
"remote.discovery"| 开启| 局域网自动发现
"remote.publicIpProbe"| 开启| 公网 IPv4 探测
"remote.trustLan"| 开启| 局域网发现时自动提供配对码

除 "remote.trustLan" 外，修改配置后需要停用并重新启用插件。

手机与电脑处于同一局域网时，可使用电脑的局域网 IP 连接；跨网络使用时，可通过 Tailscale 或配置公网访问。

默认 WebSocket 地址格式：

- 局域网："ws://电脑IP:52100"
- Tailscale："ws://Tailscale-IP:52100"
- 公网 IPv4："ws://公网IP:52100"
- IPv6："ws://[IPv6地址]:52100"

公网 IPv4 需要配置 TCP 端口转发，IPv6 需要允许入站连接。UDP "52101" 仅用于局域网发现，无需进行公网端口转发。

安全提示： 当前使用明文 "ws://"，尚无传输加密；开启局域网免配对会在发现广播中附带配对码。此外，当前版本的播放状态广播尚未过滤未配对连接，未配对设备也可能获取播放信息，不建议将服务直接暴露给不可信网络。

封面与歌词限制

受 Salt Player for Windows 当前公开插件接口的限制，插件无法直接获取并传输歌曲封面，也无法获取完整歌词。

- 歌曲封面： 当前接口不提供可供传输的原始封面图片数据，手机端需要通过本地音频库匹配歌曲封面。
- 完整歌词： 当前接口无法提供完整歌词文本，只能获取当前播放行的歌词信息；手机端需要通过本地音频库匹配完整歌词。
- 匹配要求： 建议手机端优先根据歌曲标题、艺术家等元数据匹配，必要时结合文件名或其他信息。匹配结果取决于本地曲库的数据和匹配规则。

以上属于宿主接口能力限制，并非 WebSocket 本身的限制。只有当宿主开放相应数据接口后，插件才能直接传输完整封面和歌词。

开发与构建

环境要求：JDK 25 或更高版本。

Windows 下执行：

.\build.ps1

构建产物位于 "build/libs/"，包括 ".spmod" 插件包、".zip" 副本及 "SHA256SUMS.txt"。

相关文档

- "协议参考" (docs/协议参考.md)
- "数据传输能力" (docs/数据传输能力.md)
- "测试客户端" (test-client/README.md)
- "许可证" (LICENSE)