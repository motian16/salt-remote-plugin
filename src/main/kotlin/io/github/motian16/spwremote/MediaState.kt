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
    private val trackSupplier: () -> TrackReflector.Track? = { null }
) {
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
        val changed = synchronized(lock) {
            val same = snapshot.title == item.title &&
                snapshot.artist == item.artist &&
                snapshot.path == item.path
            snapshot = snapshot.copy(
                title = item.title,
                artist = item.artist,
                album = item.album,
                albumArtist = item.albumArtist,
                path = item.path,
                // 换曲时位置和歌词都归零，否则界面上会短暂显示上一首的进度
                positionMs = if (same) snapshot.positionMs else 0L,
                lyricLine = if (same) snapshot.lyricLine else ""
            )
            !same
        }
        requestBroadcast(force = changed)
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

            val stateDiffers = realPlaying != snapshot.playing ||
                (track.positionMs != null && realPosition != snapshot.positionMs)

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

        if (changed) requestBroadcast(force = true)
        return changed
    }

    /** 当前歌词行的纯文本更新。 */
    fun onLyricLine(line: PlaybackExtensionPoint.LyricsLine?) {
        val text = line?.pureMainText.orEmpty()
        val changed = synchronized(lock) {
            if (snapshot.lyricLine == text) return@synchronized false
            snapshot = snapshot.copy(lyricLine = text)
            true
        }
        if (changed) requestBroadcast()
    }

    fun onIsPlayingChanged(playing: Boolean) {
        synchronized(lock) { snapshot = snapshot.copy(playing = playing) }
        // 播放/暂停是用户最关心的状态变化，立即推。
        // 顺便主动拉一次曲目 —— "刚开始播放"正是歌词回调可能不来的时刻。
        runCatching { refreshFrom() }
        requestBroadcast(force = true)
    }

    fun onPositionUpdated(positionMs: Long) {
        synchronized(lock) { snapshot = snapshot.copy(positionMs = positionMs) }
        requestBroadcast()
    }

    fun onStateChanged(state: PlaybackExtensionPoint.State) {
        // Idle/Ended 意味着当前曲目已经不在播了
        if (state == PlaybackExtensionPoint.State.Idle || state == PlaybackExtensionPoint.State.Ended) {
            synchronized(lock) { snapshot = snapshot.copy(playing = false) }
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

    private fun requestBroadcast(force: Boolean = false) {
        if (stopped.get()) return
        val now = System.currentTimeMillis()
        val last = lastBroadcastAt.get()
        if (!force && now - last < MIN_INTERVAL_MS) return
        if (!lastBroadcastAt.compareAndSet(last, now)) return
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
