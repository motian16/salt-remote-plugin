package io.github.motian16.spwremote

/**
 * 发现服务的独立自测入口（仅开发期用，不参与插件在宿主中的运行）。
 *
 * 起一个真实的 DiscoveryService（外加一个空的 WsServer 占住端口），
 * 这样就能在没有宿主的情况下用 test-client/verify-discovery.js 验证：
 *   - UDP 广播格式是否正确、能否被收到
 *   - probe 探测能否触发单播回应
 *   - 局域网 / IPv6 / 公网地址探测的结果
 *
 * 用法：java -cp <classes>;<kotlin-stdlib>;<pf4j> \
 *         io.github.motian16.spwremote.DiscoverySelfTest [wsPort] [udpPort] [秒数]
 */
object DiscoverySelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val wsPort = args.getOrNull(0)?.toIntOrNull() ?: 52100
        val udpPort = args.getOrNull(1)?.toIntOrNull() ?: 52101
        val seconds = args.getOrNull(2)?.toLongOrNull() ?: 30L

        println("[selftest] ===== 本机网络地址探测 =====")
        println("[selftest] " + NetworkProbe.describeAll())

        val resolver = PublicIpResolver { println("[selftest] $it") }
        println("[selftest] 公网 IPv4 = " + (resolver.resolve(force = true) ?: "探测失败"))
        println("[selftest] ==============================")

        // 占住 WebSocket 端口，让 beacon 里的 wsPort 是真的可连的
        val ws = WsServer(wsPort, object : WsServer.Listener {
            override fun onOpen(client: WsServer.Client) {
                client.sendText(Protocol.helloOk("selftest", "selftest"))
            }

            override fun onMessage(client: WsServer.Client, text: String) = Unit
            override fun onClose(client: WsServer.Client) = Unit
        })
        ws.start()
        println("[selftest] WsServer 已监听 $wsPort")

        val discovery = DiscoveryService(
            udpPort = udpPort,
            payloadProvider = {
                val eps = ArrayList<JsonValue>()
                for (a in NetworkProbe.lanIpv4()) {
                    eps.add(JsonValue.obj("host" to JsonValue.of(a), "kind" to JsonValue.of("lan")))
                }
                for (a in NetworkProbe.globalIpv6()) {
                    eps.add(JsonValue.obj("host" to JsonValue.of(a), "kind" to JsonValue.of("ipv6")))
                }
                resolver.resolve()?.let {
                    eps.add(JsonValue.obj("host" to JsonValue.of(it), "kind" to JsonValue.of("pub")))
                }
                JsonValue.obj(
                    "t" to JsonValue.of("spw-remote"),
                    "name" to JsonValue.of("selftest"),
                    "plugin" to JsonValue.of("io.github.motian16.spwremote.selftest"),
                    "version" to JsonValue.of("selftest"),
                    "wsPort" to JsonValue.of(wsPort),
                    "udpPort" to JsonValue.of(udpPort),
                    "eps" to JsonValue.Arr(eps)
                ).encode()
            },
            log = { println("[selftest] $it") }
        )
        discovery.start()
        println("[selftest] DiscoveryService 已启动，UDP $udpPort，运行 $seconds 秒")

        Thread.sleep(seconds * 1000)

        discovery.stop()
        ws.stop()
        println("[selftest] 已停止")
    }
}
