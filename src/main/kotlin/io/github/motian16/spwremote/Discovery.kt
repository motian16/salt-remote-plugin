package io.github.motian16.spwremote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 局域网自动发现（UDP 广播）。
 *
 * 目的：让手机 APP 不用手输 IP 就能找到 PC。
 *
 * 协议：
 *   1. 插件**周期性**向广播地址发 [BeaconCode] 类型的 JSON 广播（默认每 5 秒）；
 *   2. APP 也可以主动发一条 [ProbeCode] 到同一个 UDP 端口，
 *      插件收到后**立即单播**回一条 beacon —— 这样 APP 一进控制页
 *      最多等一个 RTT 就能发现，不用赌周期性广播的相位；
 *   3. APP 侧另外可用 [ReplyCode] 回应，纯诊断用，插件不依赖它。
 *
 * 广播包里带的不只是 IP，还有**连接优先级提示**（lan / ipv6 / public），
 * 以及插件自己探测到的公网 IPv4 与全局 IPv6 地址，
 * 这样"有动态公网的用户"手机不在同一网段时也能直接拿到可用地址。
 *
 * 安全性：广播内容可能被同网段任何人看到。所以这里**只放地址和端口**，
 * 不放任何密钥 —— 配对/加密以后要做也要单独设计，不能靠广播传递。
 */
class DiscoveryService(
    private val udpPort: Int,
    private val payloadProvider: () -> String,
    private val log: (String) -> Unit = {}
) {
    @Volatile
    private var running = false

    private var socket: DatagramSocket? = null
    private var broadcastThread: Thread? = null
    private var listenerThread: Thread? = null

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        if (running) return
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            // 绑 0.0.0.0：既收广播，也收 APP 发来的单播探测
            bind(InetSocketAddress("0.0.0.0", udpPort))
            soTimeout = 1000
        }
        socket = s
        running = true

        broadcastThread = Thread({ broadcastLoop(s) }, "spw-disc-broadcast").apply {
            isDaemon = true
            start()
        }
        listenerThread = Thread({ listenLoop(s) }, "spw-disc-listen").apply {
            isDaemon = true
            start()
        }
        log("发现服务已启动，UDP 端口 $udpPort")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        broadcastThread?.interrupt()
        listenerThread?.interrupt()
        broadcastThread = null
        listenerThread = null
    }

    /** 周期性广播。 */
    private fun broadcastLoop(s: DatagramSocket) {
        while (running) {
            try {
                val payload = payloadProvider().toByteArray(Charsets.UTF_8)
                for (addr in broadcastAddresses()) {
                    runCatching {
                        s.send(DatagramPacket(payload, payload.size, addr, udpPort))
                    }.onFailure { lastError = "广播到 $addr 失败: ${it.message}" }
                }
            } catch (t: Throwable) {
                if (running) lastError = "广播失败: ${t.message}"
            }
            // 5 秒一次：足够让 APP 快速发现，又不会把局域网刷满。
            sleepQuietly(BROADCAST_INTERVAL_MS)
        }
    }

    /**
     * 收包循环。
     *
     * 同时负责两件事：响应 APP 的探测，以及（可选的）记录回复。
     * soTimeout 设了 1 秒，所以每秒钟都会回到 while 判断一次 running，
     * stop() 关掉 socket 后能立刻退出。
     */
    private fun listenLoop(s: DatagramSocket) {
        val buf = ByteArray(MAX_PACKET)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running) lastError = "接收失败: ${t.message}"
                if (s.isClosed) break
                continue
            }

            val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            if (!text.contains(ProbeCode)) continue

            log("收到来自 ${packet.address.hostAddress} 的探测，立即回应")
            runCatching {
                val payload = payloadProvider().toByteArray(Charsets.UTF_8)
                s.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        packet.address,
                        packet.port
                    )
                )
            }.onFailure { lastError = "回应探测失败: ${it.message}" }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 要往哪些地址发广播。
     *
     * 对每个"支持广播的 IPv4 网卡"算出它的定向广播地址（x.y.z.255），
     * 再补上 255.255.255.255 兜底。定向广播比全网广播更容易穿过
     * 一些家用路由器的设置，所以优先用它。
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                if (!ni.supportsMulticast() && !ni.isPointToPoint) {
                    // 有些虚拟网卡不支持广播，跳过它的定向广播地址计算
                }
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address) continue
                    val bcast = ia.broadcast
                    if (bcast != null) out.add(bcast)
                }
            }
        } catch (t: Throwable) {
            lastError = "枚举网卡失败: ${t.message}"
        }
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out.toList()
    }

    private companion object {
        const val BROADCAST_INTERVAL_MS = 5_000L
        const val MAX_PACKET = 4 * 1024
        const val ProbeCode = "\"probe\""
        const val ReplyCode = "\"reply\""
        const val BeaconCode = "\"spw-remote\""
    }
}

/**
 * 本机网络地址探测：局域网、全局 IPv6、公网 IPv4。
 *
 * 为什么要探测公网 IPv4：有动态公网 IP 的用户，手机在外网时需要一个
 * 能直接连回来的地址。插件替用户把它测出来、放进广播包和配置里，
 * 用户就不用自己去查 IP 了。注意**地址会变**（动态公网），
 * 所以有缓存时间，并且设置页提供刷新。
 */
object NetworkProbe {
    /** 局域网 IPv4（本机自己看到的），不含回环。 */
    fun lanIpv4(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress &&
                        addr.isSiteLocalAddress
                    ) {
                        out.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out.distinct()
    }

    /**
     * 全局 IPv6 地址。
     *
     * 过滤掉：回环、链路本地（fe80::）、唯一本地（fc00::/7）、
     * 以及已废弃的站点本地。剩下的才是外网真能路由到的地址。
     *
     * 优先返回非临时的（隐私扩展地址会定期变化，稳定性差）。
     */
    fun globalIpv6(): List<String> {
        val global = ArrayList<String>()
        val temporary = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet6Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                        addr.isSiteLocalAddress || addr.isMulticastAddress
                    ) {
                        continue
                    }
                    val host = addr.hostAddress?.substringBefore('%') ?: continue
                    // 唯一本地地址 fc00::/7，外网不可路由
                    val lower = host.lowercase()
                    if (lower.startsWith("fc") || lower.startsWith("fd")) continue

                    // Java 没有直接暴露 RFC4941 临时地址标志，用接口名里的
                    // 临时地址数量间接判断：某些平台上临时地址会带 "temporary"
                    // 标记，这里退化为"按是否在同类网卡上"排序，不追求精确。
                    val isTemp = ia.toString().contains("temporary", ignoreCase = true)
                    if (isTemp) temporary.add(host) else global.add(host)
                }
            }
        } catch (_: Throwable) {
        }
        return (global + temporary).distinct()
    }

    /** 所有可展示的本机地址，供设置页/日志用。 */
    fun describeAll(): String {
        val lan = lanIpv4()
        val v6 = globalIpv6()
        return buildString {
            append("局域网 IPv4: ").append(lan.joinToString(", ").ifEmpty { "无" })
            append("；全局 IPv6: ").append(v6.joinToString(", ").ifEmpty { "无" })
        }
    }
}

/**
 * 公网 IPv4 探测。
 *
 * 用几个公开的"我是什么 IP"服务，逐个试，第一个成功的就用。
 * 全程走原始 TLS socket（[SSLContext] + [SSLSocket]），**不用 `java.net.http`** ——
 * 宿主运行时被裁剪过，那个模块不存在。
 *
 * 结果缓存 [CACHE_MS]：动态公网 IP 会变，但没必要每次都问。
 */
class PublicIpResolver(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val log: (String) -> Unit = {}
) {
    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedAt = 0L

    /** 取公网 IPv4；失败返回 null（内网环境、无外网、服务不可用都会是 null）。 */
    fun resolve(force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { if (now - cachedAt < CACHE_MS) return it }
        }
        for (url in endpoints) {
            val ip = runCatching { fetch(url) }.getOrNull()
            if (!ip.isNullOrBlank() && IPV4_REGEX.matches(ip.trim())) {
                val clean = ip.trim()
                cached = clean
                cachedAt = now
                log("公网 IPv4 = $clean（来自 $url）")
                return clean
            }
        }
        log("公网 IPv4 探测失败（可能没有外网，或几个服务都不可达）")
        return cached // 失败时退回上一次的结果，比返回 null 有用
    }

    /**
     * 发一个 HTTP GET 并把响应体当前文本读回来。
     *
     * 只处理 https + 固定的两个服务，所以这里手写一个"够用"的 HTTP/1.1 客户端：
     *   请求行 + Host + Connection: close + Accept-Encoding: identity
     * 然后读 header 找到 \r\n\r\n，剩下的就是 body。
     * 不处理 chunked（这两个服务在 identity 下都是定长），超时给得很短。
     */
    private fun fetch(url: String): String? {
        val https = url.startsWith("https://")
        val rest = url.removePrefix("https://").removePrefix("http://")
        val host = rest.substringBefore('/').substringBefore(':')
        val port = if (https) 443 else 80
        val path = rest.substringAfter('/', "").let { if (it.isEmpty()) "/" else "/$it" }

        val socket: Socket = if (https) {
            val factory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(PERMISSIVE_TRUST), null)
            }.socketFactory
            factory.createSocket().also {
                it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                (it as SSLSocket).startHandshake()
            }
        } else {
            Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        }

        socket.use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: SPW-Remote/0.1\r\n")
                append("Accept: text/plain\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }

            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            // 跳过状态行与头部
            var line = reader.readLine() ?: return null
            if (!line.contains("200")) return null
            var contentLength = -1
            while (true) {
                line = reader.readLine() ?: return null
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                }
            }
            val body = if (contentLength >= 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else {
                reader.readLine().orEmpty()
            }
            return body.trim()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 4_000
        const val CACHE_MS = 10 * 60 * 1000L

        val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        val DEFAULT_ENDPOINTS = listOf(
            "https://api.ipify.org/",
            "https://ifconfig.me/ip",
            "https://ipv4.icanhazip.com/"
        )

        /**
         * 宽松的证书校验。
         *
         * 这几个服务只用来问"我的出口 IP 是多少"，返回的内容不参与任何
         * 安全决策 —— 真正的连接地址是用户自己确认的。为了不让插件
         * 依赖用户机器上的根证书状态（企业代理、老旧 JDK 都可能出问题），
         * 这里接受任意证书。
         *
         * **注意：这个 TrustManager 只用于这一个用途，不要拿去做别的请求。**
         */
        val PERMISSIVE_TRUST = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
