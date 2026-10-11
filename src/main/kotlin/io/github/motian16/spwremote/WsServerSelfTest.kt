package io.github.motian16.spwremote

/**
 * 独立的 WebSocket 协议自测入口（仅用于开发期验证，不参与插件运行）。
 *
 * 目的：在没有宿主的情况下验证 WsServer 的线协议实现是否正确
 * （握手、掩码解码、文本帧、分片、ping/pong、close）。
 *
 * 用法（对应 test-client/verify-ws.js）：
 *   java -cp <classes>;<kotlin-stdlib>;<pf4j> \
 *     io.github.motian16.spwremote.WsServerSelfTest [port]（默认 52199，固定运行 60 秒）
 *
 * 注意：本自测**没有鉴权**，只用于验证 WebSocket 实现本身；真实插件的配对流程
 * 请用 test-client/verify-auth.js 配合 PluginSelfTest。
 */
object WsServerSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: 52199
        println("[selftest] 启动 WsServer 于端口 $port")

        val server = WsServer(port, object : WsServer.Listener {
            override fun onOpen(client: WsServer.Client) {
                println("[selftest] 新连接: ${client.remoteLabel}")
                client.sendText(
                    """{"t":"hello","ok":true,"protocol":1,"app":"selftest","version":"0.0.0"}"""
                )
                client.sendText(
                    """{"t":"state","playing":true,"title":"测试曲目","artist":"测试艺术家","album":"测试专辑","path":"C:\\Music\\test.flac","pos":12345,"ts":${System.currentTimeMillis()}}"""
                )
            }

            override fun onMessage(client: WsServer.Client, text: String) {
                println("[selftest] 收到消息: ${text.take(120)}")
                val parsed = parseJsonOrNull(text)
                if (parsed is JsonValue.Obj) {
                    when (parsed.fields["t"]?.asStringOrNull()) {
                        "ping" -> client.sendText(Protocol.pong(parsed.fields["ts"]?.asLongOrNull()))
                        "cmd" -> client.sendText(
                            Protocol.ack(parsed.fields["id"], ok = true, err = null)
                        )
                        // 把收到文本帧里的 text 字段原样回传，
                        // 这样客户端可以校验「上行掩码解码 + UTF-8」是否正确往返
                        "echo" -> client.sendText(
                            JsonValue.obj(
                                "t" to JsonValue.of("echo"),
                                "text" to (parsed.fields["text"] ?: JsonValue.Str(""))
                            ).encode()
                        )
                        else -> client.sendText(
                            """{"t":"echo","text":${JsonValue.of(text).encode()}}"""
                        )
                    }
                } else {
                    client.sendText(Protocol.error("消息不是合法的 JSON"))
                }
            }

            override fun onClose(client: WsServer.Client) {
                println("[selftest] 连接关闭: ${client.remoteLabel}")
            }
        })

        server.start()
        println("[selftest] 监听中，可连地址: ${NetworkInfo.describe()}")
        println("[selftest] 按 Ctrl+C 结束；或 60 秒后自动退出")

        // 运行 60 秒后自动退出，方便脚本化测试
        Thread.sleep(60_000)
        server.stop()
        println("[selftest] 已停止")
    }
}
