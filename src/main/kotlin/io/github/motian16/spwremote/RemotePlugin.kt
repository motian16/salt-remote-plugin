package io.github.motian16.spwremote

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPW 遥控插件主类。
 *
 * 做两件事：
 *   1. **播放控制**：本地监听 WebSocket，把 APP 发来的指令转成
 *      `WorkshopApi.playback` 调用，并把宿主的播放事件回推给 APP；
 *   2. **自动发现**：周期性地向局域网广播自己的地址，让 APP 不用手输 IP。
 *      广播里还带上插件探测到的**公网 IPv4 与全局 IPv6**，
 *      给"有动态公网"的用户在非局域网场景下留一条路。
 *
 * 为什么用自带的 WebSocket 实现：宿主运行时被裁剪过，没有
 * `jdk.httpserver` / `java.net.http`，且插件包越小越好，
 * 因此协议实现只用 `java.base`，`lib/` 保持为空。
 */
@OptIn(UnstableSpwWorkshopApi::class)
class RemotePlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private val started = AtomicBoolean(false)

    private var config: ConfigHelper? = null
    private var discoveryConfig: ConfigHelper? = null
    private var server: WsServer? = null
    private var discovery: DiscoveryService? = null
    private var mediaState: MediaState? = null
    private var controller: PlaybackController? = null

    /** 换曲时下发封面/完整歌词；没有配对连接时什么都不做。 */
    private var extras: MediaExtrasSender? = null

    private var port: Int = DEFAULT_PORT
    private var udpPort: Int = DEFAULT_UDP_PORT
    private var authToken: String = ""

    /**
     * 后台执行器：只用来做"不阻塞插件启动"的活儿 ——
     * 公网 IP 探测要发网络请求，绝不能卡在 start() 里。
     */
    private val background = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spw-remote-bg").apply { isDaemon = true }
    }

    // 显式写出参数类型：PublicIpResolver 有两个参数（endpoints 有默认值），
    // 不加类型时 Kotlin 会在 lambda 重载之间犯迷糊。
    private val publicIp = PublicIpResolver(log = { msg: String -> logLine(msg) })

    /** 反射读到的曲目信息，供 [MediaState] 主动拉取。失败返回 null，属正常。 */
    @Volatile
    private var reflectedTrack: TrackReflector.Track? = null

    /**
     * 插件日志。
     *
     * 名字刻意不叫 `log`：`kotlin.math.log` 会自动导入，
     * 同名会在 lambda 里产生重载歧义（编译期报 Overload resolution ambiguity）。
     */
    private fun logLine(message: String) {
        runCatching { println("[spw-remote] $message") }
    }

    override fun start() {
        if (!started.compareAndSet(false, true)) return

        // 注意：这里才允许访问 WorkshopApi —— 构造函数阶段 instance 可能还没注入
        config = runCatching {
            WorkshopApi.manager.createConfigManager().getConfig(CFG_MAIN)
        }.getOrNull()
        // 「发现与公网」是独立配置组，官方约定每组用各自的配置文件；
        // 两组共用 config.json 会导致设置页渲染不出来。
        discoveryConfig = runCatching {
            WorkshopApi.manager.createConfigManager().getConfig(CFG_DISCOVERY)
        }.getOrNull()

        port = readInt(KEY_PORT, DEFAULT_PORT, 1024, 65535)
        udpPort = readInt(KEY_UDP_PORT, DEFAULT_UDP_PORT, 1024, 65535)
        authToken = resolveAuthToken()

        var extrasHolder: MediaExtrasSender? = null
        val state = MediaState(
            broadcaster = { broadcastState() },
            trackSupplier = { readCurrentTrack() },
            // 「未连接时不发送」：把 WsServer 的**已配对**连接数交给 MediaState，
            // 让它在构造 state JSON 之前就短路。
            // 用 authedClientCount 而不是 clientCount：没配对的连接收不到 state，
            // 为了它们去构造 JSON 就是纯浪费（"无意义的行为减少"）。
            // 必须是 lambda：这里 server 还是 null（下面 srv 赋值那行才设上），
            // 直接取一次值会永远是 0，状态就一条都推不出去了。
            clientCount = { server?.authedClientCount ?: 0 },
            // 换曲才拉封面/歌词；同一首歌重复事件由 sender 自己去重。
            onTrackChanged = { extrasHolder?.request() },
            onLyricsLines = { lines -> extrasHolder?.request(lyricsOverride = lines) }
        )
        val extrasSender = MediaExtrasSender(
            snapshotProvider = { state.current() },
            clientCount = { server?.authedClientCount ?: 0 },
            send = { message -> server?.broadcast(message) },
            log = { logLine(it) }
        )
        extrasHolder = extrasSender
        extras = extrasSender
        val ctrl = PlaybackController(state)
        mediaState = state
        controller = ctrl

        // 先挂上扩展点，避免宿主在服务端启动期间刚好换曲而漏掉事件
        RemotePlaybackExtension.attach(state)

        val srv = WsServer(port, WsListener(ctrl, state))
        server = srv
        var wsOk = true
        try {
            srv.start()
        } catch (t: Throwable) {
            // 端口占用等启动失败：不要让异常冒出去影响宿主启用流程，降级为提示
            wsOk = false
            server = null
            toast("SPW 遥控启动失败（端口 $port）：${t.message}", WorkshopApi.Ui.ToastType.Error)
        }

        // 自动发现独立于 WebSocket：即使 WebSocket 起不来也先别开广播，
        // 否则会把 APP 引到一个连不上的地址上。
        if (wsOk && readDiscoveryBool(KEY_DISCOVERY, true)) {
            val disc = DiscoveryService(
                udpPort = udpPort,
                payloadProvider = { beaconJson() },
                log = { logLine(it) }
            )
            discovery = disc
            try {
                disc.start()
            } catch (t: Throwable) {
                discovery = null
                logLine("发现服务启动失败：${t.message}")
            }
        }

        // 启动时先主动读一次当前曲目：用户很可能"正在播着"才启用插件
        background.execute {
            runCatching {
                state.refreshNow()
                logLine(
                    "启动时曲目探测：" + (
                        reflectedTrack?.let { "${it.title} / ${it.artist}" }
                            ?: "未取到（" + (TrackReflector.lastError ?: "宿主未播放") + "）"
                        )
                )
            }
        }

        // 公网 IP 探测放后台，不阻塞 start()
        if (readDiscoveryBool(KEY_PUBLIC_IP, true)) {
            background.execute {
                runCatching { publicIp.resolve(force = true) }
            }
        }

        val lan = NetworkProbe.lanIpv4().joinToString(", ").ifEmpty { "无" }
        toast(
            if (wsOk) "SPW 遥控已监听 ws://0.0.0.0:$port（局域网：$lan）"
            else "SPW 遥控未启动，请在插件配置中更换端口",
            if (wsOk) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error
        )
    }

    /**
     * 读当前曲目：优先反射（dev20 没有官方接口），结果缓存下来给状态用。
     */
    private fun readCurrentTrack(): TrackReflector.Track? {
        val t = TrackReflector.current(pluginContext.spwVersion)
        if (t != null) reflectedTrack = t
        return t
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return

        RemotePlaybackExtension.attach(null)
        runCatching { discovery?.stop() }
        runCatching { server?.stop() }
        runCatching { extras?.shutdown() }
        runCatching { controller?.shutdown() }
        runCatching { mediaState?.shutdown() }
        discovery = null
        server = null
        extras = null
        controller = null
        mediaState = null
    }

    override fun delete() {
        stop()
        runCatching { background.shutdownNow() }
    }

    override fun update() {
        toast("SPW 遥控已更新", WorkshopApi.Ui.ToastType.Success)
    }

    private fun broadcastState() {
        val srv = server ?: return
        val state = mediaState ?: return
        // 兜底：MediaState 已经在入队前按连接数短路过了，这里再判一次是为了
        // "以后有人直接调 broadcastState()"的情况 —— 那时 Protocol.state(...)
        // 的 JSON 会被白白构造出来，而用户要求未连接时一点多余工作都不做。
        // 注意用**已配对**连接数：未配对的连接收不到 state（见 WsServer.broadcast）。
        if (srv.authedClientCount == 0) return
        srv.broadcast(Protocol.state(state.current()))
    }

    /**
     * 发现广播的内容。
     *
     * **默认只报身份和端口，不报地址列表** —— 广播会被同网段所有人看到，
     * 把 IP/IPv6/公网地址全放进去，等于把"这台机器开在哪"告诉所有人。
     * 地址改为配对成功后通过 WebSocket 下发。
     *
     * ## 免配对模式（默认开启，可在配置页关掉）
     *
     * 开启「局域网免配对」时，广播里**额外带上配对码**。
     * 这样手机扫到设备就能直接连上，用户一个字符都不用打。
     *
     * 代价说清楚：**同网段的人抓一个 UDP 包就能拿到配对码**。
     * 在家用 Wi-Fi 下这是"便利换安全"，可以接受；
     * 宿舍、公司、咖啡厅这类共享网络建议在配置页把它关掉 ——
     * 关掉之后广播里没有配对码，手机必须手填。
     */
    private fun beaconJson(): String {
        val trustLan = readDiscoveryBool(KEY_TRUST_LAN, true)
        return JsonValue.obj(
            "t" to JsonValue.of("spw-remote"),
            "name" to JsonValue.of("Salt Player for Windows"),
            "plugin" to JsonValue.of(pluginContext.pluginId),
            "version" to JsonValue.of(pluginContext.pluginVersion),
            "wsPort" to JsonValue.of(port),
            "udpPort" to JsonValue.of(udpPort),
            // 告诉 APP「需要配对」，好让它把输入框摆出来
            "auth" to JsonValue.of(true),
            // 免配对模式下把配对码一并广播，手机直接用它连
            "token" to (if (trustLan) JsonValue.of(authToken) else JsonValue.Null)
        ).encode()
    }

    private fun readInt(key: String, fallback: Int, min: Int, max: Int): Int {
        val raw = runCatching { config?.get(key, fallback.toString()) }.getOrNull()
        val parsed = raw?.trim()?.toIntOrNull()
        return if (parsed != null && parsed in min..max) parsed else fallback
    }

    private fun readBool(key: String, fallback: Boolean): Boolean =
        runCatching { config?.get(key, fallback) }.getOrNull() ?: fallback

    /**
     * 读「发现与公网」组的开关。
     *
     * 这一组在 preference_config.json 里对应独立的 discovery.json，
     * 所以必须用 [discoveryConfig]，不能复用主配置 —— 否则读到的永远是默认值，
     * 用户在配置页改了也不生效。
     */
    private fun readDiscoveryBool(key: String, fallback: Boolean): Boolean =
        runCatching { discoveryConfig?.get(key, fallback) }.getOrNull() ?: fallback

    /**
     * 配对码。
     *
     * **首次运行时随机生成并写回配置**，之后一直沿用（用户也可以在配置页改）。
     *
     * 为什么不是写死的默认值：写死的口令等于没有口令 —— 任何人拿到插件包
     * 就知道默认配对码是多少。随机生成之后，只有能看到这台电脑上
     * 插件配置页的人才知道它。
     *
     * 格式：`SPW-` + 8 位大写字母与数字混合，例如 `SPW-7K2M9QX4`。
     */
    private fun resolveAuthToken(): String {
        val existing = runCatching {
            config?.get(KEY_TOKEN, "")?.trim()
        }.getOrNull().orEmpty()
        if (existing.length >= 4) return existing

        val generated = generateToken()
        // 写回配置：下次启动还是同一个，用户也能在配置页看到并改
        runCatching {
            config?.set(KEY_TOKEN, generated)
            config?.save()
        }.onFailure { logLine("配对码写回配置失败：${it.message}") }
        logLine("已生成随机配对码：$generated")
        return generated
    }

    private fun generateToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去掉容易看错的 I/O/0/1
        val random = java.security.SecureRandom()
        val body = buildString(8) {
            repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
        return "SPW-$body"
    }

    private fun toast(message: String, type: WorkshopApi.Ui.ToastType) {
        runCatching { WorkshopApi.ui.toast(message, type) }
    }

    /** 处理一条客户端消息；返回给该客户端的应答（null 表示无应答） */
    private inner class WsListener(
        private val controller: PlaybackController,
        private val state: MediaState
    ) : WsServer.Listener {

        /**
         * 每个连接的配对状态。
         *
         * 用弱引用集合按 client 记：连接断开时 [onClose] 清掉，
         * 避免长期运行后集合无限增长。
         */
        private val authed = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<WsServer.Client, Boolean>()
        )

        /**
         * 认证失败计数：远端 IP → 最近失败时刻。
         *
         * 配对码只有 8 位，理论上可以被暴力枚举。以前没有任何限流，一个脚本可以
         * 每秒试几百次；现在**同一个 IP 在 60 秒内失败 5 次就直接断开连接**，
         * 让暴力枚举从"分钟级"变成"按天算"。
         */
        private val authFailures = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()

        /**
         * 指令限流：每个连接最近一段时间内执行过的 cmd 时刻。
         *
         * 遥控的正常操作（播放/暂停/下一首/拖进度）最多每秒几次；
         * 给到 [CMD_BURST] 次 / [CMD_WINDOW_MS] 已经非常宽松。超出的指令
         * **不执行**、直接回一条 ack 错误，避免一个失控的客户端把宿主刷爆。
         */
        private val cmdTimes = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<WsServer.Client, MutableList<Long>>()
        )

        override fun onOpen(client: WsServer.Client) {
            // 新连接先报"需要配对"，但不推状态 —— 状态要等配对成功再给，
            // 否则同网段任何人连上来就能看到你在听什么。
            client.sendText(Protocol.authRequired())
        }

        override fun onMessage(client: WsServer.Client, text: String) {
            val json = parseJsonOrNull(text)
            if (json !is JsonValue.Obj) {
                client.sendText(Protocol.error("消息不是合法的 JSON 对象"))
                return
            }

            val fields = json.fields
            val type = fields["t"]?.asStringOrNull()

            // 配对之前只放行 auth 和 ping，其余一律拒绝。
            if (client !in authed && type != "auth") {
                if (type == "ping") {
                    client.sendText(Protocol.pong(fields["ts"]?.asLongOrNull()))
                } else {
                    client.sendText(Protocol.authRequired())
                }
                return
            }

            when (type) {
                "auth" -> {
                    val token = fields["token"]?.asStringOrNull().orEmpty()
                    if (token == authToken) {
                        authed.add(client)
                        // WsServer.broadcast 只发给 authed 的连接：这个标记就是
                        // "播放状态不再向未配对连接泄露"的开关。
                        client.authed = true
                        authFailures.remove(client.remoteHost)
                        client.sendText(Protocol.authOk())
                        // 配对成功才给状态；先主动对一次账，
                        // 这样"已经播着"的情况下也能立刻拿到曲目。
                        state.refreshNow()
                        client.sendText(
                            Protocol.helloOk(
                                appName = "Salt Player for Windows (SPW Remote)",
                                appVersion = pluginContext.pluginVersion
                            )
                        )
                        client.sendText(Protocol.state(state.current()))
                        // 新设备刚连上：强制补一份封面/歌词，不然要等下次换歌才有。
                        extras?.request(force = true)
                        logLine("客户端 ${client.remoteLabel} 配对成功")
                    } else {
                        val failures = authFailures
                            .getOrPut(client.remoteHost) { mutableListOf() }
                        synchronized(failures) {
                            val now = System.currentTimeMillis()
                            failures.removeAll { now - it > AUTH_FAILURE_WINDOW_MS }
                            failures.add(now)
                        }
                        client.sendText(Protocol.authFail("配对码不对"))
                        logLine("客户端 ${client.remoteLabel} 配对失败：配对码不对")
                        // 同一个 IP 短时间内反复失败：直接断开，别让它把配对码试穿。
                        val recent = synchronized(failures) { failures.size }
                        if (recent >= AUTH_FAILURE_LIMIT) {
                            logLine(
                                "客户端 ${client.remoteLabel} 在 " +
                                    (AUTH_FAILURE_WINDOW_MS / 1000) + " 秒内配对失败 " +
                                    recent + " 次，已断开"
                            )
                            client.close(1008, "配对失败次数过多，请稍后再试")
                        }
                    }
                }

                "hello" -> {
                    client.sendText(
                        Protocol.helloOk(
                            appName = "Salt Player for Windows (SPW Remote)",
                            appVersion = pluginContext.pluginVersion
                        )
                    )
                    state.refreshNow()
                    client.sendText(Protocol.state(state.current()))
                    extras?.request(force = true)
                }

                "ping" -> client.sendText(Protocol.pong(fields["ts"]?.asLongOrNull()))

                // 地址列表：**只在配对成功之后**才下发。
                // 广播里不带地址，就是为了让"谁知道有哪些地址"这件事
                // 也受配对码保护。
                "netinfo", "addrs" -> client.sendText(
                    JsonValue.obj(
                        "t" to JsonValue.of("netinfo"),
                        "name" to JsonValue.of("Salt Player for Windows"),
                        "wsPort" to JsonValue.of(port),
                        "udpPort" to JsonValue.of(udpPort),
                        "lan" to JsonValue.Arr(NetworkProbe.lanIpv4().map { JsonValue.of(it) }),
                        "ipv6" to JsonValue.Arr(NetworkProbe.globalIpv6().map { JsonValue.of(it) }),
                        // 公网地址只用**缓存**：这里在 WebSocket 读线程上，
                        // 同步探测会把这台设备的所有消息卡住几秒（包括指令回执）。
                        "public" to JsonValue.of(publicIp.cachedOrNull()),
                        // 局域网免配对开启时，把配对码一并交接给手机。
                        // 手机存下来之后，用户就不用手打这 12 个字符了；
                        // 界面上仍然显示它，方便用户告诉第二台设备。
                        "token" to (
                            if (readDiscoveryBool(KEY_TRUST_LAN, true)) JsonValue.of(authToken)
                            else JsonValue.Null
                            ),
                        // 反射读曲目是否可用，排查"不显示曲目"时最有用的一条
                        "trackReflector" to JsonValue.of(
                            if (TrackReflector.isDisabled) {
                                "不可用：" + (TrackReflector.lastError ?: "未知原因")
                            } else {
                                "可用，命中字段 " + (TrackReflector.matchedField ?: "?")
                            }
                        )
                    ).encode()
                )

                "cmd" -> handleCommand(client, fields)

                null -> client.sendText(Protocol.error("缺少 t 字段"))
                else -> client.sendText(
                    Protocol.error("未知消息类型: $type")
                )
            }
        }

        override fun onClose(client: WsServer.Client) {
            authed.remove(client)
            cmdTimes.remove(client)
        }

        private fun handleCommand(client: WsServer.Client, fields: Map<String, JsonValue>) {
            val id = fields["id"]
            val action = fields["action"]?.asStringOrNull()
            if (action.isNullOrBlank()) {
                client.sendText(Protocol.ack(id, ok = false, err = "缺少 action 字段"))
                return
            }

            // 指令限流：正常遥控一秒最多几次，超过 [CMD_BURST] 次/秒级窗口就拒绝执行。
            // 目的是"失控的客户端不能把宿主刷爆"，正常用户完全碰不到这条线。
            if (!allowCommand(client)) {
                logLine("客户端 ${client.remoteLabel} 指令过于频繁，已忽略一条")
                client.sendText(
                    Protocol.ack(id, ok = false, err = "指令过于频繁，已忽略")
                )
                return
            }

            val posMs = fields["pos"]?.asLongOrNull()
            // 记下来自哪台设备、执行了什么；seek 额外带上目标秒数，方便对着日志排查
            logLine(buildString {
                append("收到来自 ").append(client.remoteLabel).append(" 的指令：")
                append(actionDescription(action))
                if (action == Protocol.ACTION_SEEK && posMs != null) {
                    append("，跳转到 ").append(posMs / 1000).append(" 秒")
                }
            })

            val err = controller.execute(action, posMs)
            client.sendText(Protocol.ack(id, ok = err == null, err = err))
            if (err == null) {
                // 指令生效后立刻回推新状态
                state.onRemoteCommand()
            } else {
                logLine("指令 $action 执行失败（来自 ${client.remoteLabel}）：$err")
            }
        }

        /**
         * 日志里显示的中文指令名。
         * 仅用于打印；协议收发仍用 action 原文（play/pause/...），不受影响。
         */
        private fun actionDescription(action: String): String = when (action) {
            Protocol.ACTION_PLAY -> "继续播放"
            Protocol.ACTION_PAUSE -> "暂停"
            Protocol.ACTION_NEXT -> "下一首"
            Protocol.ACTION_PREV -> "上一首"
            Protocol.ACTION_SEEK -> "跳转进度"
            else -> action
        }

        /** 这个连接现在还能不能再执行一条指令（滑动窗口计数）。 */
        private fun allowCommand(client: WsServer.Client): Boolean {
            val now = System.currentTimeMillis()
            val times = cmdTimes.getOrPut(client) { mutableListOf() }
            synchronized(times) {
                times.removeAll { now - it > CMD_WINDOW_MS }
                if (times.size >= CMD_BURST) return false
                times.add(now)
            }
            return true
        }
    }

    companion object {
        const val DEFAULT_PORT = 52100

        /** 发现广播用的 UDP 端口，和 WebSocket 端口错开，方便网络排查。 */
        const val DEFAULT_UDP_PORT = 52101

        /**
         * 默认配对码：8 位大写字母 + 数字混合。
         *
         * 只是**出厂默认值**，用户可以在插件配置里改。要求两边一致：
         * APP 侧 `RemoteControlPrefs.DEFAULT_TOKEN` 必须和这里逐字相同，
         * 否则开箱即用会连不上。
         *
         * **它不会出现在广播包里**，所以同网段的人必须知道它才能连。
         */
        const val DEFAULT_TOKEN = "SPW8K3M9"

        const val KEY_PORT = "remote.port"

        /**
         * 配置文件名，必须与 preference_config.json 里各配置组的 `config` 字段逐字一致。
         *
         * 官方示例里每个配置组用**各自独立**的文件；两组共用同一个文件会导致
         * 插件设置页渲染不出任何控件（实测）。
         */
        const val CFG_MAIN = "config.json"
        const val CFG_DISCOVERY = "discovery.json"
        const val KEY_UDP_PORT = "remote.udpPort"
        const val KEY_DISCOVERY = "remote.discovery"
        const val KEY_PUBLIC_IP = "remote.publicIpProbe"
        const val KEY_TOKEN = "remote.token"

        /**
         * 局域网免配对。
         *
         * 开启时：手机连上后插件会把配对码**交接**给它，用户不用手打。
         * 配对码本身仍然存在、仍然显示在配置页，所以：
         *   - 第二台设备可以直接照抄这个码连上（用户看得到）；
         *   - 想禁止自动交接就把它关掉，那时手机必须手填配对码。
         *
         * 取舍说清楚：开着的时候，**同网段任何能连上端口的人都能拿到配对码**。
         * 在家用 Wi-Fi 下这是便利换安全，可接受；在宿舍/公司网络下建议关掉。
         */
        const val KEY_TRUST_LAN = "remote.trustLan"

        /** 同一个 IP 在 [AUTH_FAILURE_WINDOW_MS] 内配对失败这么多次就断开连接。 */
        private const val AUTH_FAILURE_LIMIT = 5
        private const val AUTH_FAILURE_WINDOW_MS = 60_000L

        /**
         * 指令限流：每个连接在 [CMD_WINDOW_MS] 窗口内最多执行 [CMD_BURST] 条。
         *
         * 正常遥控（连点下一首、拖进度条）也就每秒几次，给到 1 秒 15 条已经非常宽松；
         * 超出的指令不执行，只回 ack 错误 —— 防止一个失控的客户端把宿主刷爆。
         */
        private const val CMD_BURST = 15
        private const val CMD_WINDOW_MS = 1_000L

        /**
         * 配置页「下载 AudioMeta App」按钮的回调。
         *
         * 打开 GitHub 的最新发行版页面。用 AWT Desktop 走系统默认浏览器；
         * 宿主没有桌面/浏览器时退回复制地址并提示，保证按钮不会静默失败。
         */
        @JvmStatic
        @JvmName("onOpenAppDownload")
        fun onOpenAppDownload() {
            val url = "https://github.com/motian16/AudioMeta/releases/latest"
            Thread({
                val opened = runCatching {
                    if (!java.awt.Desktop.isDesktopSupported()) {
                        error("系统不支持打开浏览器")
                    }
                    val desktop = java.awt.Desktop.getDesktop()
                    if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        error("系统没有可用的浏览器")
                    }
                    desktop.browse(java.net.URI(url))
                }.isSuccess

                if (opened) {
                    toastOnUi("已打开 AudioMeta 下载页")
                } else {
                    runCatching {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(url),
                            null
                        )
                    }
                    toastOnUi("下载地址已复制：" + url)
                }
            }, "spw-open-app-download").apply { isDaemon = true }.start()
        }

        /**
         * 配置页「查看本机网络地址」按钮的回调。
         *
         * 必须是**静态方法**（preference_config.json 的 on_click 只支持静态方法），
         * 所以这里不能依赖插件实例，全部走 NetworkProbe 的静态探测。
         *
         * ## 为什么必须异步
         *
         * 以前这里在点击线程上同步做三件事：枚举网卡、探测公网 IP（最多三个 HTTPS
         * 端点 × 4 秒）、再弹 toast —— 用户看到的就是"点一下卡住，然后才出来"。
         * 现在立即回一句"正在探测…"，真正的工作丢到后台线程，探测完再弹结果。
         *
         * ## 地址顺序
         *
         * 用的是 [NetworkProbe.lanAddresses] 的**排序结果**：真实网卡、
         * 192.168/10 网段优先，WSL/Hyper-V 之类的 172.x 虚拟网卡排最后。
         * 以前直接取枚举到的第一个，于是 172.26.x.x 盖过了真正的 192.168.0.106。
         */
        @JvmStatic
        @JvmName("onShowNetworkInfo")
        fun onShowNetworkInfo() {
            toastOnUi("正在探测本机网络地址…")

            Thread({
                // 只有用户开着「探测公网 IP」才去问外网，否则这一步纯属浪费流量和时间。
                val probePublic = runCatching {
                    WorkshopApi.manager.createConfigManager()
                        .getConfig(CFG_DISCOVERY)
                        .get(KEY_PUBLIC_IP, true)
                }.getOrDefault(true)

                val lan = runCatching { NetworkProbe.lanAddresses() }.getOrDefault(emptyList())
                val v6 = runCatching { NetworkProbe.globalIpv6() }.getOrDefault(emptyList())
                val pub = if (probePublic) {
                    runCatching { PublicIpResolver().resolve(force = true) }.getOrNull()
                } else {
                    null
                }

                val detail = buildString {
                    append("局域网 IPv4（按可用性排序）:\n")
                    if (lan.isEmpty()) append("  无")
                    lan.forEach { a ->
                        append("  ").append(a.ip).append("  [").append(a.iface).append("]")
                        if (a.virtual) append("  ← 虚拟网卡，手机通常连不回来")
                        append('\n')
                    }
                    append("全局 IPv6: ").append(v6.joinToString(", ").ifEmpty { "无" })
                    append("\n公网 IPv4: ")
                    append(
                        when {
                            pub != null -> pub
                            !probePublic -> "未探测（配置里已关闭）"
                            else -> "探测失败（内网环境或无法访问外网）"
                        }
                    )
                }
                println("[spw-remote] ===== 本机网络地址 =====")
                println("[spw-remote] $detail")
                println("[spw-remote] ========================")

                val primary = lan.firstOrNull()
                val summary = when {
                    primary != null && !primary.virtual -> "局域网：" + primary.ip
                    primary != null -> "局域网：" + primary.ip + "（虚拟网卡，建议核对）"
                    v6.isNotEmpty() -> "IPv6：" + v6.first()
                    pub != null -> "公网：" + pub
                    else -> "未发现可用地址"
                }
                toastOnUi("本机地址 $summary（完整列表见日志）")
            }, "spw-netinfo").apply { isDaemon = true }.start()
        }

        /**
         * 从任意线程弹一条 toast。
         *
         * 插件的 `ui.toast` 是给 UI 层用的，不能在后台线程直接调；这里回到 AWT
         * 事件线程再弹。取不到 EDT（理论上不会发生）就退回直接调用，绝不抛。
         */
        private fun toastOnUi(text: String) {
            val shown = runCatching {
                javax.swing.SwingUtilities.invokeLater {
                    runCatching {
                        WorkshopApi.ui.toast(text, WorkshopApi.Ui.ToastType.Success)
                    }
                }
            }.isSuccess
            if (!shown) {
                runCatching { WorkshopApi.ui.toast(text, WorkshopApi.Ui.ToastType.Success) }
            }
        }
    }
}
