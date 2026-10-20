package io.github.motian16.spwremote

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放状态跟踪 + 状态广播节流。
 *
 * 线程模型说明（重要）：
 *   - onBeforeLoadLyrics / onAfterLoadLyrics 在 **IO 线程** 回调
 *   - onPositionUpdated / onStateChanged / onIsPlayingChanged 等回调线程不固定
 *   - WebSocket 广播线程又是另一批
 * 所以内部状态一律用锁保护，广播统一丢到一个单线程调度器上串行发出，
 * 既保证线程安全，又把"每秒一个位置事件"合并成最多每秒一条 state 广播。
 *
 * ## 哪些事件要"绕开节流"立刻推
 *
 * 节流的目的是别把每秒一次的位置事件放大成洪水，但有两类事件**用户能直接看出来**，
 * 等一个节流窗口就是"手机上还显示上一首"的观感，所以它们走 [requestImmediateBroadcast]：
 *   - 换曲（[onMediaItem]）：标题/歌手/封面全变了，必须马上到手机；
 *   - 跳转进度（[onPositionUpdated] 里位置差值超过阈值，见 [isSeekJump]）：
 *     用户拖了进度条，进度条必须跟着走。
 * 它们都是**额外**的一次推送，正常的每秒位置事件照旧走节流，互不取代。
 *
 * ## 未连接时不发送
 *
 * 上面两条即时推、以及普通节流推，入口都会先用 [hasClients] 看有没有设备连着；
 * 一台都没有时直接返回，连 state 的 JSON 都不构造（[clientCount] 就是为此注入的）。
 *
 * ## 为什么还要"主动拉取"
 *
 * 只靠歌词回调拿曲目信息是有洞的：歌词**有缓存或内嵌**时宿主不调用回调，
 * 于是"开始播放"之后插件手里一直是空的，直到切歌才补上。
 * 解决办法是 [TrackReflector] 主动读宿主内部字段 —— 见那个类的说明。
 * 这里的 [refreshFrom] 就是那个主动路径，由插件在连接建立、
 * 播放状态变化、以及低频定时器上调用。
 */
class MediaState(
    private val broadcaster: () -> Unit,
    /**
     * 主动取当前曲目的回调。返回 null 表示这次没拿到（正常情况，不报错）。
     * 由插件注入，内部走 [TrackReflector]。
     */
    private val trackSupplier: () -> TrackReflector.Track? = { null },
    /**
     * 已连接设备数量的探针（来自 [WsServer.clientCount]）。
     *
     * 为什么是一个 lambda 而不是一个 Int：连接数是实时变化的，而且
     * [MediaState] 是在插件 start() 里构造的，那一刻 WsServer 还没赋值。
     * 默认返回 1（"当作有连接"）——不注入时行为与改动前完全一致，
     * 用例也可以按需伪造连接数。
     *
     * 它的唯一用途是 [hasClients] 的短路：用户要求"未连接时不发送"，
     * 没有设备时连 state 的 JSON 都不构造。
     */
    private val clientCount: () -> Int = { 1 },
    /**
     * 曲目身份变化（换歌）时回调一次。
     *
     * 插件用它去拉封面和完整歌词 —— 这两样都比 state 重得多，不能塞进每秒
     * 一次的状态广播里，只在换曲时单独发一趟（见 MediaExtrasSender）。
     */
    private val onTrackChanged: (MediaSnapshot) -> Unit = {},
    /**
     * 宿主主动推来完整歌词时间轴时回调一次。
     *
     * 宿主的 `onLyricsLinesUpdated` 在换曲后不一定立刻有内容（有时先空表），
     * 所以它只是 [onTrackChanged] 那条拉取路径的补充，不替代。
     */
    private val onLyricsLines: (List<PlaybackExtensionPoint.LyricsLine>) -> Unit = {}
) {
    /** 上一次通知过封面/歌词的曲目 key，避免同一首歌反复拉。 */
    private var notifiedTrackKey: String = ""
    private val lock = Any()
    private var snapshot: MediaSnapshot = MediaSnapshot.EMPTY
    private val lastBroadcastAt = AtomicLong(0)
    private val stopped = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "spw-remote-broadcast").apply { isDaemon = true }
        }

    /** 低频主动拉取，兜住"事件没来但曲目已经变了"的情况。 */
    private var pollJob: ScheduledExecutorService? = null

    /** 最近一次主动拉取到的曲目标识，用来判断有没有换曲。 */
    private var lastPulledKey: String = ""

    init {
        // 每 4 秒主动对一次账。间隔不能太短：反射读字段虽然便宜，
        // 但没必要每秒都做（位置事件已经负责秒级刷新了）。
        pollJob = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "spw-remote-poll").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ runCatching { refreshFrom() } }, 3, 4, TimeUnit.SECONDS)
        }
    }

    /** 换曲时更新曲目信息（歌词回调里拿到 MediaItem，是官方路径） */
    fun onMediaItem(item: PlaybackExtensionPoint.MediaItem) {
        synchronized(lock) {
            val same = snapshot.title == item.title &&
                snapshot.artist == item.artist &&
                snapshot.path == item.path
            snapshot = snapshot.copy(
                title = item.title,
                artist = item.artist,
                album = item.album,
                albumArtist = item.albumArtist,
                path = item.path,
                // dev21+ 的官方 id 比反射可靠：有就覆盖掉反射值，封面查询要用它。
                id = item.id.ifBlank { snapshot.id },
                // dev21+ 才有这个字段；旧宿主是 0（APP 会退回本机曲库时长）。
                durationMs = item.duration,
                // 换曲时位置和歌词都归零，否则界面上会短暂显示上一首的进度
                positionMs = if (same) snapshot.positionMs else 0L,
                lyricLine = if (same) snapshot.lyricLine else ""
            )
        }
        notifyTrackChanged()
        /*
         * 换曲必须**立刻**同步给已连接设备（用户要求），所以走 [requestImmediateBroadcast]
         * 而不是普通节流路径：
         *   - 普通路径用的是宿主每秒位置事件的 900ms 节流窗口，换歌正好发生在这个
         *     窗口里的话，"新歌标题"最多要等 1 秒才到手机上，进度条还会先显示上一首的；
         *   - 即时路径连 CAS 抢占都省了 —— 对"必须发出去"的事件，输掉抢占等于事件被吞。
         *
         * 为什么这里不看"曲目是否真的变了"就无条件推：这个回调只在宿主加载歌词时来
         * （onBeforeLoadLyrics / onAfterLoadLyrics），频率跟着曲目走，每首最多两次，
         * 不会把节流的意义冲掉；而重复推一条相同的 state 对客户端是幂等的。
         */
        requestImmediateBroadcast()
    }

    /**
     * 主动从宿主读一次当前曲目。
     *
     * 只在**内容真的变了**时才广播，避免每 4 秒白推一条。
     * 返回是否发生了变化。
     */
    fun refreshFrom(): Boolean {
        if (stopped.get()) return false
        val track = runCatching { trackSupplier() }.getOrNull() ?: return false
        if (track.isEmpty) return false

        val key = (track.id ?: "") + "|" + track.path + "|" + track.title + "|" + track.artist
        val changed = synchronized(lock) {
            // 路径/标题都不同才算换曲；只补空字段不算
            val isNewTrack = key != lastPulledKey && lastPulledKey.isNotEmpty()
            val wasEmpty = snapshot.title.isBlank() && snapshot.path.isBlank()

            /*
             * 播放/暂停和位置**以宿主实际状态为准**。
             *
             * 这两个值原先只靠事件更新，于是"刚连上时宿主已经在播"这种情况
             * 客户端会看到 playing=false，要等下一次事件才纠正 ——
             * 用户看到的就是"连上了还显示没有在播放"。
             * 现在每次 [refreshFrom] 都顺手读一次真实值。
             */
            val realPlaying = track.isPlaying ?: snapshot.playing
            val realPosition = track.positionMs ?: snapshot.positionMs

            val stateDiffers = realPlaying != snapshot.playing
                // 位置变化不算“需要广播”：正常播放靠手机本地时钟推进。
                // 位置仍然写回 snapshot，供暂停/换曲/播完时的 state 使用。

            snapshot = snapshot.copy(
                playing = realPlaying,
                title = track.title.ifBlank { snapshot.title },
                artist = track.artist.ifBlank { snapshot.artist },
                album = track.album.ifBlank { snapshot.album },
                albumArtist = track.albumArtist.ifBlank { snapshot.albumArtist },
                path = track.path.ifBlank { snapshot.path },
                id = track.id.orEmpty().ifBlank { snapshot.id },
                positionMs = if (isNewTrack) 0L else realPosition,
                lyricLine = if (isNewTrack) "" else snapshot.lyricLine
            )
            lastPulledKey = key
            wasEmpty || isNewTrack || stateDiffers
        }

        if (changed) {
            notifyTrackChanged()
            requestBroadcast(force = true)
        }
        return changed
    }

    /** 当前歌词行的纯文本更新。 */
    fun onLyricLine(line: PlaybackExtensionPoint.LyricsLine?) {
        val text = line?.pureMainText.orEmpty()
        synchronized(lock) {
            if (snapshot.lyricLine == text) return
            snapshot = snapshot.copy(lyricLine = text)
        }
        // 只更新快照，不广播：手机端有完整歌词时间轴，正常播放时不需要因为
        // 歌词换行而反复确认 state。下一次暂停/跳转/换曲的 state 会带上当前行。
    }

    /**
     * 宿主推来完整歌词时间轴。
     *
     * 只在**曲目已经有身份**时转给上层；空表（换曲瞬间的占位）不触发发送，
     * 否则手机会先收到一条空歌词、把上一首的歌词擦掉。
     */
    fun onLyricsTimeline(lines: List<PlaybackExtensionPoint.LyricsLine>) {
        if (lines.isEmpty()) return
        if (current().title.isBlank() && current().path.isBlank()) return
        runCatching { onLyricsLines(lines) }
    }

    /** 曲目 key 变了才通知上层去拉封面/歌词；同一首歌重复回调直接忽略。 */
    private fun notifyTrackChanged() {
        val snap = current()
        if (snap.title.isBlank() && snap.path.isBlank()) return
        if (snap.key == notifiedTrackKey) return
        notifiedTrackKey = snap.key
        runCatching { onTrackChanged(snap) }
    }

    fun onIsPlayingChanged(playing: Boolean) {
        synchronized(lock) { snapshot = snapshot.copy(playing = playing) }
        // 播放/暂停是用户最关心的状态变化，立即推。
        // 顺便主动拉一次曲目 —— "刚开始播放"正是歌词回调可能不来的时刻。
        runCatching { refreshFrom() }
        requestBroadcast(force = true)
    }

    fun onPositionUpdated(positionMs: Long) {
        // 先算出"这次是不是跳变"，再决定走哪条路。
        // 比较基准必须是**更新前**的位置，所以放在同一个锁里取。
        val seek = synchronized(lock) {
            val previous = snapshot.positionMs
            snapshot = snapshot.copy(positionMs = positionMs)
            isSeekJump(previous, positionMs)
        }
        if (seek) {
            // 用户拖了进度条 / 点了下一段：立刻推，不等节流窗口。
            // 宿主还有个 onSeekTo 回调，它也是转发到本方法的
            // （见 RemotePlaybackExtension.onSeekTo），所以两条来源都能被识别。
            requestImmediateBroadcast()
        }
        // 正常播放的每秒递增**不推**：手机端从收到的那一帧开始用本地时钟推进，
        // 两端本来就走同一条时间轴。只有人为跳转、暂停/恢复、换曲、播完才需要
        // 重新确认进度，见文件头的广播策略。
    }

    /**
     * 判断这次位置更新是不是"用户跳转了进度"。
     *
     * 判据是**相邻两次位置的差值**：宿主 onPositionUpdated 每秒一次，
     * 正常播放时差值在 +1 秒上下，而拖动进度条 / 点进度条会让它一次跳很远。
     *
     * 阈值怎么定的：
     *   - 向后（位置变小）超过 [SEEK_BACKWARD_TOLERANCE_MS] = 1.5s：
     *     正常播放位置只会前进，明显回退只可能是 seek（往回拖、或单曲循环从头开始）。
     *     留 1.5s 余量是容忍宿主偶发的几十/几百毫秒抖动（例如 1000 之后报 985）。
     *   - 向前超过 [SEEK_FORWARD_TOLERANCE_MS] = 3s：
     *     正常一秒一跳，超过 3 秒说明跳了一段（点进度条、点歌词）。
     *     这里给到 3s 而不是 1.5s，是为了容忍宿主在恢复播放、事件被延迟时
     *     一次补上 2 秒左右的位置——那属于"正常追赶"，不该当成 seek。
     *
     * [previous] <= 0 时不当 seek：那是"手里还没有基准"（刚换曲把位置归零、
     * 或插件刚启动），此时任何位置都可能是第一帧；而且换曲本身已经即时推过一次了。
     *
     * 可见性给到 internal 是给 [MediaStateSelfTest] 直接测这个判据用的。
     */
    internal fun isSeekJump(previous: Long, current: Long): Boolean {
        if (previous <= 0L) return false
        val delta = current - previous
        return delta < -SEEK_BACKWARD_TOLERANCE_MS || delta > SEEK_FORWARD_TOLERANCE_MS
    }

    fun onStateChanged(state: PlaybackExtensionPoint.State) {
        // Idle/Ended 意味着当前曲目已经不在播了
        if (state == PlaybackExtensionPoint.State.Idle || state == PlaybackExtensionPoint.State.Ended) {
            synchronized(lock) {
                // 播完时把位置钉在总时长，手机端收到“结束”这一帧就停在全满，不再靠本地时钟。
                val end = if (state == PlaybackExtensionPoint.State.Ended) snapshot.durationMs else 0L
                snapshot = snapshot.copy(
                    playing = false,
                    positionMs = if (end > 0L) end else snapshot.positionMs
                )
            }
            requestBroadcast(force = true)
        }
    }

    /** 执行遥控指令后立刻回推一次，让 APP 不用等下一个事件 */
    fun onRemoteCommand() = requestBroadcast(force = true)

    fun current(): MediaSnapshot = synchronized(lock) { snapshot }

    /** 有客户端刚连上时调用：先主动对一次账，再让调用方取快照。 */
    fun refreshNow() {
        runCatching { refreshFrom() }
    }

    fun shutdown() {
        stopped.set(true)
        scheduler.shutdownNow()
        pollJob?.shutdownNow()
        pollJob = null
    }

    /**
     * 是否至少有一台设备连着。
     *
     * 探针异常时按"有连接"处理：宁可多发一条，也不能因为探针坏了就永远不推状态。
     */
    private fun hasClients(): Boolean =
        runCatching { clientCount() > 0 }.getOrDefault(true)

    private fun requestBroadcast(force: Boolean = false) {
        if (stopped.get()) return
        // 短路点一：没有任何设备连着就直接返回 —— 不构造 JSON、不入队。
        // 刻意放在节流判断**之前**：节流会顺手把 lastBroadcastAt 改成现在，
        // 无人连接时改它没有意义，反而会让"手机刚连上"的那一秒被节流吃掉。
        if (!hasClients()) return
        val now = System.currentTimeMillis()
        val last = lastBroadcastAt.get()
        if (!force && now - last < MIN_INTERVAL_MS) return
        if (!lastBroadcastAt.compareAndSet(last, now)) return
        enqueueBroadcast()
    }

    /**
     * 立即推送一次，**绕过节流**。
     *
     * 与 [requestBroadcast] 的差别有两个，都是"切歌 / 拖进度必须马上发出去"逼出来的：
     *   1. 不看 [MIN_INTERVAL_MS]，不会被上一秒的正常位置广播挡住；
     *   2. 不做 CAS 抢占 —— CAS 的作用是把并发的重复请求合并成一条，
     *      但对"必须发"的事件，抢输的那次等于事件被吞掉。
     *      代价是极端并发下可能多发一条 state，客户端按后到的覆盖即可（幂等）。
     */
    private fun requestImmediateBroadcast() {
        if (stopped.get()) return
        // 短路点二：切歌 / seek 的即时推同样先看有没有设备。
        if (!hasClients()) return
        // 顺手把节流窗口往后推：即时推已经带上了最新状态，
        // 紧接着的那一拍正常位置事件就没必要再发一遍。
        lastBroadcastAt.set(System.currentTimeMillis())
        enqueueBroadcast()
    }

    /** 真正把广播排到单线程调度器上；调用方负责所有前置判断。 */
    private fun enqueueBroadcast() {
        runCatching {
            scheduler.execute {
                if (!stopped.get()) runCatching { broadcaster() }
            }
        }
    }

    private companion object {
        /**
         * 宿主 onPositionUpdated 每秒一次，这里再做一层节流。
         * 设成 900ms 是为了让每秒的位置事件都能过，但重复触发不会叠加。
         */
        const val MIN_INTERVAL_MS = 900L

        /** 位置回退超过这个量就算 seek。见 [isSeekJump]。 */
        const val SEEK_BACKWARD_TOLERANCE_MS = 1_500L

        /** 位置前进超过这个量就算 seek。见 [isSeekJump]。 */
        const val SEEK_FORWARD_TOLERANCE_MS = 3_000L
    }
}

/**
 * 播放控制指令执行入口。
 *
 * 注意宿主 API 的两个硬约束：
 *   1. changeExclusive 必须在主线程调用，且禁止在 onIsPlayingChanged 回调里调用
 *      —— 因此本插件完全不碰 changeExclusive。
 *   2. play/pause/previous/next/seekTo 没有线程约束说明，但保险起见统一放到
 *      独立线程里执行，避免阻塞 WebSocket 的读循环。
 */
class PlaybackController(
    private val state: MediaState
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spw-remote-command").apply { isDaemon = true }
    }

    /** 返回 null 表示成功，否则返回错误描述 */
    fun execute(action: String, posMs: Long?): String? {
        return try {
            when (action) {
                Protocol.ACTION_PLAY -> run(WorkshopApi.playback::play)
                Protocol.ACTION_PAUSE -> run(WorkshopApi.playback::pause)
                Protocol.ACTION_NEXT -> run(WorkshopApi.playback::next)
                Protocol.ACTION_PREV -> run(WorkshopApi.playback::previous)
                Protocol.ACTION_SEEK -> {
                    if (posMs == null || posMs < 0) {
                        return "seek 需要非负的 pos 参数（毫秒）"
                    }
                    run { WorkshopApi.playback.seekTo(posMs) }
                }
                else -> return "未知 action: $action"
            }
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun run(block: () -> Unit): String? {
        // 同步执行：调用方已经在 WebSocket 的读线程上，
        // 而这些方法本身是轻量的命令下发，不需要异步排队。
        // 用 executor 提交并等待，保证不会在宿主的主线程上做网络 IO。
        val future = executor.submit { block() }
        return try {
            future.get(3, TimeUnit.SECONDS)
            null
        } catch (t: Throwable) {
            t.cause?.message ?: t.message ?: t.javaClass.simpleName
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
