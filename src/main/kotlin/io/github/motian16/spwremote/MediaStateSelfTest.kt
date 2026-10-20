package io.github.motian16.spwremote

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [MediaState] 推送逻辑的自测入口（仅开发期用，不参与插件在宿主里的运行）。
 *
 * 覆盖的是"切歌 / 跳进度要即时推、未连接不发、正常递增仍走节流"这四条要求：
 *   1. [caseNoClientNoSend]        —— clientCount == 0 时一条都不发（不构造 JSON、不入队）
 *   2. [caseSeekDetectedImmediately] —— 位置跳变被识别成 seek 并立刻推，正常递增仍被节流
 *   3. [caseTrackChangeImmediately]  —— 换曲立刻推
 *   4. [caseSeekPredicate]           —— seek 判据本身的边界值（1.5s / 3s）
 *
 * 为什么不用 JUnit：本工程没有测试依赖，自测一律是"object + main"的形式，
 * 这样在宿主里不会被打包执行、编译也不引入新依赖（见 WsServerSelfTest）。
 *
 * 用法（需要 kotlin-stdlib 与 spw-workshop-api，因为用到了 MediaItem）：
 *   java -cp "build\classes\kotlin\main;<kotlin-stdlib.jar>;<spw-workshop-api-0.1.0-dev22.jar>" `
 *     io.github.motian16.spwremote.MediaStateSelfTest
 *
 * 全部通过时退出码 0，有失败项时退出码 1，可以直接串在脚本里。
 */
object MediaStateSelfTest {

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("[selftest] ===== MediaState 推送逻辑自测 =====")
        caseNoClientNoSend()
        caseSeekDetectedImmediately()
        caseTrackChangeImmediately()
        caseSeekPredicate()
        println("[selftest] ===== 结束：失败 $failures 项 =====")
        if (failures > 0) System.exit(1)
    }

    /**
     * 需求 3：没有设备连着时，任何路径都不许发（连 JSON 都不许构造）。
     *
     * 判定方式：broadcaster 就是"去构造并发送 state JSON"的那一步（
     * RemotePlugin 注入的是 broadcastState），它一次都不能被调用。
     */
    private fun caseNoClientNoSend() {
        val sent = AtomicInteger(0)
        val track = AtomicReference(track("track-1"))

        val state = MediaState(
            broadcaster = { sent.incrementAndGet() },
            // 给了曲目，"主动拉取"那条路径才会真的走到推送那一步
            trackSupplier = { track.get() },
            clientCount = { 0 }
        )

        state.onPositionUpdated(1_000L) // 正常递增
        state.onPositionUpdated(90_000L) // 向前大幅跳变（相当于拖了进度条）
        state.onMediaItem(mediaItem("甲")) // 换曲
        state.refreshFrom() // 4 秒兜底那条主动拉取
        state.onIsPlayingChanged(true) // 播放/暂停
        Thread.sleep(400) // 给广播线程足够时间去跑（如果它真被排队了的话）

        check(
            "clientCount==0 时不发送（不构造 JSON、不入队）",
            sent.get() == 0,
            "实际发出 ${sent.get()} 条"
        )
        state.shutdown()
    }

    /**
     * 需求 1/2/5：跳变即时推、正常递增仍走节流。
     *
     * 时间线刻意都压在 900ms 的节流窗口**内部**，这样"发了"就只能解释为即时推。
     */
    private fun caseSeekDetectedImmediately() {
        val sent = AtomicInteger(0)
        val state = MediaState(
            broadcaster = { sent.incrementAndGet() },
            clientCount = { 1 }
        )

        // ① 首帧：手里还没有基准（previous=0），走普通节流路径；
        //    lastBroadcastAt 初值是 0，所以这一条本身会发出去。
        state.onPositionUpdated(1_000L)
        check("正常位置事件会被推送", awaitAtLeast(sent, 1), "count=${sent.get()}")

        // ② 窗口内再来一次"正常递增"（只前进 100ms）：必须被节流吃掉
        Thread.sleep(200)
        state.onPositionUpdated(1_100L)
        Thread.sleep(300)
        check("正常递增仍受 900ms 节流约束（没有变成每秒都推）", sent.get() == 1, "count=${sent.get()}")

        // ③ 仍在窗口内，这次前进 60 秒 —— 必须立刻发，而且是在同一个窗口里
        state.onPositionUpdated(61_100L)
        check("向前大幅跳变立刻推送（绕开节流）", awaitAtLeast(sent, 2), "count=${sent.get()}")

        // ④ 仍在窗口内，前进 2 秒属于阈值内的"正常追赶"：不该即时发
        state.onPositionUpdated(63_100L)
        Thread.sleep(300)
        check("+2s 在阈值内，不算 seek，仍走节流", sent.get() == 2, "count=${sent.get()}")

        // ⑤ 仍在窗口内，回退 53 秒 —— 必须立刻发
        state.onPositionUpdated(10_000L)
        check("向后跳变立刻推送", awaitAtLeast(sent, 3), "count=${sent.get()}")

        // ⑥ 仍在窗口内，回退 600ms 只是抖动：不该即时发
        state.onPositionUpdated(9_400L)
        Thread.sleep(300)
        check("小幅回退（<1.5s）不算 seek", sent.get() == 3, "count=${sent.get()}")

        state.shutdown()
    }

    /** 需求 1：换曲那一刻立刻推（即使正处在别人的节流窗口里）。 */
    private fun caseTrackChangeImmediately() {
        val sent = AtomicInteger(0)
        val state = MediaState(
            broadcaster = { sent.incrementAndGet() },
            clientCount = { 1 }
        )

        state.onPositionUpdated(5_000L)
        check("第一拍位置事件发出", awaitAtLeast(sent, 1), "count=${sent.get()}")

        // 200ms 之后换曲：还在 900ms 窗口内，普通路径会把它挡掉
        Thread.sleep(200)
        state.onMediaItem(mediaItem("新歌"))
        check("换曲立刻推送（绕开节流）", awaitAtLeast(sent, 2), "count=${sent.get()}")
        check("换曲确实更新了曲目", state.current().title == "新歌", "title=${state.current().title}")

        state.shutdown()
    }

    /** 需求 2 的判据本身：把阈值两侧的边界值都钉住。 */
    private fun caseSeekPredicate() {
        // 这个实例只用来调判据，不推任何东西（clientCount=0）
        val state = MediaState(broadcaster = {}, clientCount = { 0 })

        val cases = listOf(
            // previous, current, 期望是不是 seek, 说明
            Triple(10_000L, 11_000L, false) to "正常 +1s",
            Triple(10_000L, 10_000L, false) to "重复回调（位置没动）",
            Triple(10_000L, 12_000L, false) to "+2s 阈值内（宿主补课）",
            Triple(10_000L, 13_000L, false) to "正好 +3s（不算超过）",
            Triple(10_000L, 13_100L, true) to "+3.1s 超过向前阈值",
            Triple(10_000L, 9_000L, false) to "-1s 抖动",
            Triple(10_000L, 8_500L, false) to "正好 -1.5s（不算超过）",
            Triple(10_000L, 8_400L, true) to "-1.6s 超过向后阈值",
            Triple(0L, 60_000L, false) to "没有基准（刚换曲/刚启动）"
        )

        for ((input, desc) in cases) {
            val (previous, current, expected) = input
            val actual = state.isSeekJump(previous, current)
            check(
                "seek 判据：$desc（$previous -> $current）",
                actual == expected,
                "期望 $expected，实际 $actual"
            )
        }

        state.shutdown()
    }

    // ---------- 小工具 ----------

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            println("[selftest] PASS $name")
        } else {
            failures++
            println("[selftest] FAIL $name" + if (detail.isBlank()) "" else " —— $detail")
        }
    }

    /** 广播是异步排队到调度器上的，所以要用轮询等它落地，不能直接断言。 */
    private fun awaitAtLeast(counter: AtomicInteger, expected: Int, timeoutMs: Long = 1_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() >= expected) return true
            Thread.sleep(10)
        }
        return counter.get() >= expected
    }

    private fun mediaItem(title: String): PlaybackExtensionPoint.MediaItem =
        PlaybackExtensionPoint.MediaItem(
            title = title,
            artist = "测试艺术家",
            album = "测试专辑",
            albumArtist = "测试艺术家",
            path = "C:\\Music\\$title.flac"
        )

    private fun track(id: String): TrackReflector.Track = TrackReflector.Track(
        id = id,
        title = "测试曲目",
        artist = "测试艺术家",
        album = "测试专辑",
        albumArtist = "测试艺术家",
        path = "C:\\Music\\$id.flac",
        isPlaying = true,
        positionMs = 1_000L
    )
}
