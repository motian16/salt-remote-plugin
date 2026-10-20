package io.github.motian16.spwremote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 只依赖 java.base 的极简 RFC6455 WebSocket 服务端。
 *
 * 为什么不直接用现成库：宿主运行时被 jpackage 裁剪过，只含 18 个模块，
 * **没有 jdk.httpserver，也没有 java.net.http**，即没有内置 HTTP 服务端。
 * 本实现只用到 ServerSocket / Socket / MessageDigest / Base64，全部在 java.base 里，
 * 因此插件包内不需要任何第三方 jar（lib/ 目录保持为空）。
 *
 * 已实现的 RFC6455 子集：
 *   - HTTP Upgrade 握手（含 Sec-WebSocket-Accept 校验值计算）
 *   - 文本帧收发、客户端掩码解码
 *   - 分片消息（continuation 帧）重组
 *   - ping / pong 控制帧、close 帧
 *   - 服务端主动心跳 + 读空闲超时检测
 * 未实现（本场景不需要）：扩展协商（permessage-deflate）、二进制帧投递。
 */
class WsServer(
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        /** 新连接完成握手后回调 */
        fun onOpen(client: WsServer.Client)

        /** 收到完整文本消息 */
        fun onMessage(client: WsServer.Client, text: String)

        /** 连接断开（正常或异常），只会回调一次 */
        fun onClose(client: WsServer.Client)
    }

    /** 客户端连接上限，防止被误连打满宿主线程 */
    private val maxClients = 8
    private val clients: MutableSet<Client> =
        Collections.synchronizedSet(Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap()))

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    /** 上次读取时间用于空闲检测 */
    private val lastReadAt: MutableMap<Client, Long> =
        java.util.concurrent.ConcurrentHashMap()

    @Volatile
    var lastError: String? = null
        private set

    val clientCount: Int get() = clients.size

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        // 绑定 0.0.0.0：同时接受局域网网段与 Tailscale 虚拟网段(100.x)的连接
        ss.bind(InetSocketAddress("0.0.0.0", port))
        serverSocket = ss
        running = true

        acceptThread = Thread({ acceptLoop(ss) }, "spw-remote-accept").apply {
            isDaemon = true
            start()
        }

        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "spw-remote-heartbeat").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(::heartbeat, 10, 10, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        scheduler?.shutdownNow()
        scheduler = null

        val snapshot = clients.toList()
        for (c in snapshot) c.closeQuietly()
        clients.clear()
        lastReadAt.clear()

        acceptThread?.interrupt()
        acceptThread = null
    }

    /**
     * 向**已配对**的客户端广播一条文本消息。
     *
     * 以前这里是"遍历全部连接"，于是同网段任何人只要连上端口（哪怕没有配对码），
     * 下一次状态广播就能读到 `playing/title/artist/path/pos` —— 配对码保护的只是
     * "发指令"和"拿地址列表"，播放状态其实是公开的（文档里也标了这个待修项）。
     *
     * 现在按 [Client.authed] 过滤：只有配对成功的连接才会收到 `state`。
     */
    fun broadcast(text: String) {
        for (c in clients.toList()) {
            if (c.authed) c.sendText(text)
        }
    }

    /** 已配对连接数（状态广播"有没有人听"的判断用它，而不是连接数）。 */
    val authedClientCount: Int
        get() = clients.count { it.authed }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (_: SocketException) {
                break // stop() 关闭了 ServerSocket
            } catch (t: Throwable) {
                if (!running) break
                lastError = "accept 失败: ${t.message}"
                continue
            }

            if (clients.size >= maxClients) {
                runCatching { socket.close() }
                continue
            }

            val client = Client(socket)
            clients.add(client)
            lastReadAt[client] = System.currentTimeMillis()
            Thread({ client.run() }, "spw-remote-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun heartbeat() {
        val now = System.currentTimeMillis()
        for (c in clients.toList()) {
            val last = lastReadAt[c] ?: now
            if (now - last > IDLE_TIMEOUT_MS) {
                lastError = "客户端 ${c.remoteLabel} 空闲超时，已断开"
                c.closeQuietly()
                continue
            }
            c.sendPing()
        }
    }

    private fun onClientGone(client: Client) {
        if (clients.remove(client)) {
            lastReadAt.remove(client)
            runCatching { listener.onClose(client) }
        }
    }

    /** 一帧的解析结果。Kotlin 不允许在 inner class 内再嵌套 class，所以放在外层 */
    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    inner class Client(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private val writeLock = Any()
        private val recvBuf = ByteArrayOutputStream()

        @Volatile
        private var closed = false

        /**
         * 这个连接是否已经配对成功。
         *
         * 由插件在 `auth` 校验通过时置 true。`broadcast()` 只发给 authed 的连接，
         * 这是"播放状态不再向未配对连接泄露"的唯一开关。
         */
        @Volatile
        var authed: Boolean = false

        val remoteLabel: String
            get() = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"

        /** 远端 IP（不含端口），用于认证失败限流。 */
        val remoteHost: String
            get() = socket.inetAddress?.hostAddress ?: "?"

        /**
         * 主动断开这个连接。
         *
         * 认证失败次数超限时由插件调用：回一个 1008（policy violation）再关 socket，
         * 客户端能立刻知道"不是网络问题，是认证被拒太多次"，而不是反复重连。
         */
        fun close(code: Int, reason: String?) {
            if (closed) return
            runCatching { sendClose(code, reason) }
            closeQuietly()
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                if (!handshake()) {
                    closeQuietly()
                    return
                }
                listener.onOpen(this)
                readLoop()
            } catch (_: EOFException) {
                // 对端正常关闭
            } catch (t: Throwable) {
                if (!closed) lastError = "客户端处理异常: ${t.message}"
            } finally {
                closeQuietly()
                onClientGone(this)
            }
        }

        /** 完成 HTTP Upgrade 握手；失败返回 false */
        private fun handshake(): Boolean {
            val header = readHttpHeader() ?: return false
            val lines = header.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return false
            if (!requestLine.startsWith("GET ")) {
                writeHttpStatus("405 Method Not Allowed")
                return false
            }

            var key: String? = null
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Sec-WebSocket-Key", ignoreCase = true)) key = value
            }

            if (key.isNullOrBlank()) {
                writeHttpStatus("400 Bad Request")
                return false
            }

            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).toByteArray(Charsets.UTF_8))
            )

            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                append("\r\n")
            }
            synchronized(writeLock) {
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            return true
        }

        private fun writeHttpStatus(status: String) {
            runCatching {
                val body = "spw-remote：这里是 WebSocket 端点，请用 WebSocket 协议连接\n"
                val response = buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                    append(body)
                }
                synchronized(writeLock) {
                    output.write(response.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
        }

        /** 读取到 \r\n\r\n 为止的 HTTP 头，限制大小防止恶意请求 */
        private fun readHttpHeader(): String? {
            val buf = ByteArrayOutputStream()
            var matched = 0
            while (buf.size() < MAX_HTTP_HEADER) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                matched = when {
                    matched == 0 && b == '\r'.code -> 1
                    matched == 1 && b == '\n'.code -> 2
                    matched == 2 && b == '\r'.code -> 3
                    matched == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) return buf.toString("UTF-8")
            }
            return null
        }

        private fun readLoop() {
            while (!closed) {
                val frame = readFrame() ?: return
                lastReadAt[this] = System.currentTimeMillis()

                when (frame.opcode) {
                    OP_CONTINUATION, OP_TEXT -> {
                        if (frame.opcode == OP_TEXT) recvBuf.reset()
                        if (recvBuf.size() + frame.payload.size > MAX_MESSAGE) {
                            sendClose(1009, "message too big")
                            return
                        }
                        recvBuf.write(frame.payload)
                        if (frame.fin) {
                            val text = recvBuf.toString("UTF-8")
                            recvBuf.reset()
                            runCatching { listener.onMessage(this, text) }
                                .onFailure { lastError = "处理消息失败: ${it.message}" }
                        }
                    }

                    OP_PING -> sendFrame(OP_PONG, frame.payload)

                    OP_PONG -> { /* 已通过 lastReadAt 记录存活 */ }

                    OP_CLOSE -> {
                        sendClose(1000, null)
                        return
                    }

                    else -> {
                        sendClose(1002, "unsupported opcode ${frame.opcode}")
                        return
                    }
                }
            }
        }

        /** 读一帧；EOF 或协议错误返回 null（Frame 定义在外层，见 line 166） */
        private fun readFrame(): Frame? {
            val b0 = input.read()
            if (b0 < 0) return null
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F

            val b1 = input.read()
            if (b1 < 0) return null
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()

            if (length == 126L) {
                length = readN(2).toLong()
            } else if (length == 127L) {
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or readN(1).toLong()
                length = v
            }

            if (length < 0 || length > MAX_MESSAGE) {
                sendClose(1009, "frame too big")
                return null
            }

            // RFC6455：客户端发往服务端的帧必须带掩码
            if (!masked) {
                sendClose(1002, "client frame must be masked")
                return null
            }

            val mask = ByteArray(4)
            readFully(mask, 4)

            val payload = ByteArray(length.toInt())
            readFully(payload, payload.size)
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            return Frame(fin, opcode, payload)
        }

        private fun readN(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 8) or readByteOrThrow()
            return v
        }

        private fun readByteOrThrow(): Int {
            val b = input.read()
            if (b < 0) throw EOFException("连接已关闭")
            return b
        }

        private fun readFully(dst: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val n = input.read(dst, off, len - off)
                if (n < 0) throw EOFException("连接已关闭")
                off += n
            }
        }

        fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

        fun sendPing() = sendFrame(OP_PING, ByteArray(0))

        private fun sendClose(code: Int, reason: String?) {
            val reasonBytes = reason?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val payload = ByteArray(2 + reasonBytes.size)
            payload[0] = ((code shr 8) and 0xFF).toByte()
            payload[1] = (code and 0xFF).toByte()
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
            sendFrame(OP_CLOSE, payload)
        }

        /** 服务端发出的帧不加掩码 */
        private fun sendFrame(opcode: Int, payload: ByteArray) {
            if (closed) return
            try {
                synchronized(writeLock) {
                    val header = ByteArrayOutputStream()
                    header.write(0x80 or opcode) // FIN=1
                    when {
                        payload.size < 126 -> header.write(payload.size)
                        payload.size < 65536 -> {
                            header.write(126)
                            header.write((payload.size ushr 8) and 0xFF)
                            header.write(payload.size and 0xFF)
                        }
                        else -> {
                            header.write(127)
                            var v = payload.size.toLong()
                            val tmp = ByteArray(8)
                            for (i in 7 downTo 0) {
                                tmp[i] = (v and 0xFF).toByte()
                                v = v ushr 8
                            }
                            header.write(tmp)
                        }
                    }
                    output.write(header.toByteArray())
                    if (payload.isNotEmpty()) output.write(payload)
                    output.flush()
                }
            } catch (_: Throwable) {
                closeQuietly()
            }
        }

        fun closeQuietly() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            onClientGone(this)
        }
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_HTTP_HEADER = 16 * 1024
        const val MAX_MESSAGE = 1 * 1024 * 1024
        const val SOCKET_READ_TIMEOUT_MS = 60_000
        const val IDLE_TIMEOUT_MS = 150_000L
    }
}

/** 列出本机可用于连接的所有 IPv4 地址，方便把地址告诉用户 / 在 APP 里做候选 */
object NetworkInfo {
    fun localIpv4Addresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        out.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out.distinct()
    }

    fun describe(): String = localIpv4Addresses().joinToString(", ").ifEmpty { "无可用地址" }

    @Suppress("unused")
    fun loopback(): InetAddress = InetAddress.getLoopbackAddress()
}
