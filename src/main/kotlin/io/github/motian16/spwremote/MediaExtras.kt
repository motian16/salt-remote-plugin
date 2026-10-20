package io.github.motian16.spwremote

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * 换曲时单独下发**封面 + 完整歌词**。
 *
 * ## 为什么不塞进 state
 *
 * `state` 每秒都在推，而封面（压缩后仍有几十 KB）和整首歌词都不该跟着每秒走。
 * 它们只在换曲时变，所以单独一条 `trackExtra`：一条消息、一个 key，
 * 手机侧可以原子地替换，不会出现"新封面配旧歌词"。
 *
 * ## 能拿到什么
 *
 * - 封面：`WorkshopApi.library.getCoverById(id)` —— 宿主 dev21+ 的官方接口，
 *   返回内嵌封面原始字节（JPEG/PNG）。插件统一压成**最长边 640 的 JPEG**，
 *   体积控制在 150KB 以内；手机侧优先用自己的曲库封面，拿不到才用这一张。
 * - 歌词：`WorkshopApi.playback.getLyricsLines()` —— 完整时间轴，含翻译。
 *   宿主换曲后歌词可能晚一拍就绪，所以 `onLyricsLinesUpdated` 推来时会再补发一次。
 *
 * ## 失败不影响遥控
 *
 * 没有 LIBRARY_READ 授权、宿主没给 id、封面不存在、歌词还没加载 —— 全都只是
 * "这一次没有"，绝不让 state/指令链路停摆。
 */
class MediaExtrasSender(
    private val snapshotProvider: () -> MediaSnapshot,
    private val clientCount: () -> Int,
    private val send: (String) -> Unit,
    private val log: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spw-remote-extras").apply { isDaemon = true }
    }

    /** 每次请求 +1；旧任务发现代数变了就直接放弃，避免换歌后还把上一首的封面发出去。 */
    private val generation = AtomicInteger(0)

    @Volatile
    private var lastCoverKey: String = ""

    @Volatile
    private var lastLyricsKey: String = ""

    @Volatile
    private var lastLyricsSignature: String = ""

    /**
     * 请求发送一次。
     *
     * @param force 刚配对成功时用：即使 key 没变也重发一份，新设备才有东西显示
     * @param lyricsOverride 宿主主动推来的完整时间轴；null 表示去 API 拉
     */
    fun request(
        force: Boolean = false,
        lyricsOverride: List<PlaybackExtensionPoint.LyricsLine>? = null
    ) {
        if (clientCount() <= 0) return
        val snapshot = snapshotProvider()
        if (snapshot.title.isBlank() && snapshot.path.isBlank()) return
        val key = snapshot.key
        val gen = generation.incrementAndGet()

        executor.execute {
            if (gen != generation.get()) return@execute
            if (snapshotProvider().key != key) return@execute

            // 换曲瞬间 getLyricsLines 可能还是上一首的：等一拍再拉，别把旧歌词发过去。
            if (lyricsOverride == null && key != lastLyricsKey) {
                runCatching { Thread.sleep(LYRICS_SETTLE_MS) }
                if (snapshotProvider().key != key) return@execute
            }

            val lyrics = lyricsOverride?.let(::toDtoList) ?: pullLyrics()
            val signature = lyrics.joinToString("|") { it.startMs.toString() + ":" + it.main }
            val wantCover = force || key != lastCoverKey
            val wantLyrics = force || key != lastLyricsKey || signature != lastLyricsSignature
            if (!wantCover && !wantLyrics) return@execute

            val cover = if (wantCover) pullCover(snapshot.id) else null
            var message = build(
                snapshot = snapshot,
                cover = cover,
                lyrics = if (wantLyrics) lyrics else null
            )
            if (message.length > MAX_MESSAGE_CHARS && cover != null) {
                log("封面+歌词超出单帧上限，本条只发歌词")
                message = build(snapshot, cover = null, lyrics = if (wantLyrics) lyrics else null)
            }

            // 拉取期间又换歌了：这一条已经过期，丢掉。
            if (snapshotProvider().key != key) return@execute
            runCatching { send(message) }
                .onFailure { log("封面/歌词发送失败：" + it.message) }

            if (wantCover) lastCoverKey = key
            if (wantLyrics) {
                lastLyricsKey = key
                lastLyricsSignature = signature
            }
        }
    }

    fun shutdown() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun pullLyrics(): List<LyricDto> = runCatching {
        WorkshopApi.playback.getLyricsLines().mapNotNull { line ->
            val main = line.pureMainText.trim()
            if (main.isEmpty()) return@mapNotNull null
            LyricDto(
                startMs = line.startTime,
                endMs = line.endTime,
                main = main,
                translation = line.pureSubText?.trim().orEmpty()
            )
        }
    }.onFailure {
        log("读取歌词失败（不影响遥控）：" + it.message)
    }.getOrDefault(emptyList())

    private fun pullCover(id: String): CoverPayload? {
        if (id.isBlank()) {
            log("宿主没有提供曲目 id，跳过封面")
            return null
        }
        val raw = runCatching {
            WorkshopApi.library.getCoverById(id)
                .toCompletableFuture()
                .get(COVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure {
            // 最常见的是没有授予 LIBRARY_READ；其次才是"这首歌没有内嵌封面"。
            log("读取封面失败：" + (it.cause?.message ?: it.message))
        }.getOrNull() ?: return null
        return compressCover(raw)
    }

    /** 原始内嵌图 → 最长边 [COVER_MAX_PX] 的 JPEG。任何一步失败都返回 null。 */
    private fun compressCover(raw: ByteArray): CoverPayload? = runCatching {
        val source = ImageIO.read(ByteArrayInputStream(raw)) ?: return null
        val scale = minOf(
            1.0,
            COVER_MAX_PX.toDouble() / maxOf(source.width, source.height).toDouble()
        )
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            // 带透明通道的 PNG 转 JPEG 时先铺白底，否则透明区域会变成黑色。
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        if (!ImageIO.write(target, "jpg", out)) return null
        val bytes = out.toByteArray()
        if (bytes.size > MAX_COVER_BYTES) {
            log("压缩后封面仍有 " + bytes.size + " 字节，本条不带封面")
            return null
        }
        CoverPayload(
            base64 = Base64.getEncoder().encodeToString(bytes),
            mime = "image/jpeg",
            width = width,
            height = height
        )
    }.onFailure {
        log("封面转 JPEG 失败：" + it.message)
    }.getOrNull()

    private fun build(
        snapshot: MediaSnapshot,
        cover: CoverPayload?,
        lyrics: List<LyricDto>?
    ): String {
        val fields = mutableListOf<Pair<String, JsonValue?>>(
            "t" to JsonValue.of("trackExtra"),
            "key" to JsonValue.of(snapshot.key),
            "durationMs" to JsonValue.of(snapshot.durationMs),
            "ts" to JsonValue.of(System.currentTimeMillis())
        )
        if (cover != null) {
            fields += "cover" to JsonValue.of(cover.base64)
            fields += "coverMime" to JsonValue.of(cover.mime)
            fields += "coverW" to JsonValue.of(cover.width)
            fields += "coverH" to JsonValue.of(cover.height)
        }
        if (lyrics != null) {
            fields += "lyrics" to JsonValue.Arr(
                lyrics.map { line ->
                    JsonValue.obj(
                        "s" to JsonValue.of(line.startMs),
                        "e" to JsonValue.of(line.endMs),
                        "m" to JsonValue.of(line.main),
                        "t" to JsonValue.of(line.translation)
                    )
                }
            )
        }
        return JsonValue.obj(*fields.toTypedArray()).encode()
    }

    private fun toDto(line: PlaybackExtensionPoint.LyricsLine): LyricDto? {
        val main = line.pureMainText.trim()
        if (main.isEmpty()) return null
        return LyricDto(
            startMs = line.startTime,
            endMs = line.endTime,
            main = main,
            translation = line.pureSubText?.trim().orEmpty()
        )
    }

    private fun toDtoList(list: List<PlaybackExtensionPoint.LyricsLine>): List<LyricDto> =
        list.mapNotNull(::toDto)

    private data class LyricDto(
        val startMs: Long,
        val endMs: Long,
        val main: String,
        val translation: String
    )

    private data class CoverPayload(
        val base64: String,
        val mime: String,
        val width: Int,
        val height: Int
    )

    private companion object {
        /** 最长边。手机控制页封面约 350dp，640px 在 3x 屏上也够清晰。 */
        const val COVER_MAX_PX = 640

        /** 压缩后更大就放弃封面（base64 后仍要留出歌词和 JSON 的余量）。 */
        const val MAX_COVER_BYTES = 150_000

        /** 宿主单帧上限 1MiB；留足余量，超了只发歌词。 */
        const val MAX_MESSAGE_CHARS = 850_000

        const val COVER_TIMEOUT_SECONDS = 3L

        /** 换曲后等宿主把歌词切过来。 */
        const val LYRICS_SETTLE_MS = 220L
    }
}
