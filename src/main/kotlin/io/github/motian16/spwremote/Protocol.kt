package io.github.motian16.spwremote

/**
 * 遥控协议定义（WebSocket 文本帧承载 JSON）。
 *
 * 设计目标：与传输方式解耦。局域网直连和 Tailscale 走的是完全相同的协议，
 * 将来换成 Cloudflare Tunnel 时 APP 侧不需要改协议层。
 *
 * 客户端 -> 服务端
 *   {"t":"hello"}                          握手，取回协议版本与当前状态
 *   {"t":"ping","ts":<ms>}                 应用层心跳（与 WS 控制帧 ping 二选一即可）
 *   {"t":"cmd","id":<任意>, "action":"play"|"pause"|"next"|"prev"|"seek", "pos":<ms>}
 *
 * 服务端 -> 客户端
 *   {"t":"hello","ok":true,"protocol":1,"app":"...","version":"..."}
 *   {"t":"pong","ts":<ms>}
 *   {"t":"state","playing":<bool>,"title":"...","artist":"...","album":"...",
 *    "path":"...","pos":<ms>,"ts":<ms>}
 *   {"t":"ack","id":<原样回传>,"ok":<bool>,"err":"..."?}
 *   {"t":"error","err":"..."}
 */
object Protocol {
    const val VERSION = 2

    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_NEXT = "next"
    const val ACTION_PREV = "prev"
    const val ACTION_SEEK = "seek"

    fun helloOk(appName: String, appVersion: String): String = JsonValue.obj(
        "t" to JsonValue.of("hello"),
        "ok" to JsonValue.of(true),
        "protocol" to JsonValue.of(VERSION),
        "app" to JsonValue.of(appName),
        "version" to JsonValue.of(appVersion)
    ).encode()

    /**
     * 需要配对。
     *
     * 插件用它告诉 APP「你得先发 auth」。APP 收到这条就应该弹配对码输入框。
     */
    fun authRequired(): String = JsonValue.obj(
        "t" to JsonValue.of("authRequired"),
        "protocol" to JsonValue.of(VERSION)
    ).encode()

    fun authOk(): String = JsonValue.obj(
        "t" to JsonValue.of("authOk")
    ).encode()

    fun authFail(reason: String): String = JsonValue.obj(
        "t" to JsonValue.of("authFail"),
        "err" to JsonValue.of(reason)
    ).encode()

    fun pong(ts: Long?): String = JsonValue.obj(
        "t" to JsonValue.of("pong"),
        "ts" to (ts?.let { JsonValue.of(it) } ?: JsonValue.Null)
    ).encode()

    fun ack(id: JsonValue?, ok: Boolean, err: String?): String = JsonValue.obj(
        "t" to JsonValue.of("ack"),
        "id" to (id ?: JsonValue.Null),
        "ok" to JsonValue.of(ok),
        "err" to (err?.let { JsonValue.of(it) })
    ).encode()

    fun error(message: String): String = JsonValue.obj(
        "t" to JsonValue.of("error"),
        "err" to JsonValue.of(message)
    ).encode()

    fun state(snapshot: MediaSnapshot): String = JsonValue.obj(
        "t" to JsonValue.of("state"),
        "playing" to JsonValue.of(snapshot.playing),
        "title" to JsonValue.of(snapshot.title),
        "artist" to JsonValue.of(snapshot.artist),
        "album" to JsonValue.of(snapshot.album),
        "albumArtist" to JsonValue.of(snapshot.albumArtist),
        "path" to JsonValue.of(snapshot.path),
        "pos" to JsonValue.of(snapshot.positionMs),
        // 宿主 dev21+ 的 MediaItem.duration；旧宿主为 0。
        // 手机侧优先用自己的曲库时长，拿不到时用这个画真实百分比。
        "duration" to JsonValue.of(snapshot.durationMs),
        // id 来自宿主内部字段（见 TrackReflector），拿不到时是空串。
        // APP 侧应该把它当"可能为空"的提示，不能当主键依赖。
        "id" to JsonValue.of(snapshot.id),
        // 当前歌词行的纯文本；没有歌词或未推送时是空串。
        "lyric" to JsonValue.of(snapshot.lyricLine),
        "ts" to JsonValue.of(System.currentTimeMillis())
    ).encode()
}

/**
 * 对外广播的播放状态快照。
 *
 * 字段来源分两类，**APP 侧要区别对待**：
 *   - 官方 API（稳定）：title / artist / album / albumArtist / path / playing / positionMs
 *     来自 `PlaybackExtensionPoint.MediaItem`；
 *   - 尽力而为（可能为空）：[id] 与 [lyricLine]。
 *     id 来自反射读宿主内部字段（老宿主没有公开接口），
 *     lyric 来自 `onLyricsLineUpdated`。
 *
 * **没有封面，也没有总时长**：宿主 dev20 的公开 API 两者都不提供。
 * 封面由 APP 侧从自己的曲库按 id/path/标题匹配得到（那样更快也更清晰）。
 */
data class MediaSnapshot(
    val playing: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val path: String,
    val positionMs: Long,
    val id: String = "",
    val lyricLine: String = "",
    /** 曲目总时长（毫秒）。宿主 dev21+ 提供；旧宿主为 0。 */
    val durationMs: Long = 0L
) {
    /** 手机侧用它把 state 与封面/歌词对应起来，格式与 APP 的 keyOf 一致。 */
    val key: String get() = id + "|" + path + "|" + title + "|" + artist

    companion object {
        val EMPTY = MediaSnapshot(
            playing = false,
            title = "",
            artist = "",
            album = "",
            albumArtist = "",
            path = "",
            positionMs = 0L
        )
    }
}
