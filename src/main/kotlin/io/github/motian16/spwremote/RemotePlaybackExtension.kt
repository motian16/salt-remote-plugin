package io.github.motian16.spwremote

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展点：接收宿主的播放事件，驱动状态广播。
 *
 * 生命周期要点：
 *   PF4J 会在插件启用时用无参构造器创建本扩展的实例，创建时机可能早于
 *   WorkshopApi.instance 的注入，因此 **构造函数里绝不能访问 WorkshopApi**。
 *   这里只在回调中读取插件 start() 阶段设置好的 [instance]。
 *
 * 注意 dev20 宿主的限制：
 *   - MediaItem 只有 title/artist/album/albumArtist/path 五个字段（无 id/duration）
 *   - 没有专门的"换曲"回调，onBeforeLoadLyrics 是最可靠的换曲挂点
 *   - onPositionUpdated 每秒回调一次
 */
@Extension
class RemotePlaybackExtension : PlaybackExtensionPoint {

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        instance?.onMediaItem(mediaItem)
        return null // 返回 null 表示继续使用宿主默认的歌词逻辑
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        // 默认逻辑拿不到歌词时才会走到这里，作为换曲检测的兜底
        instance?.onMediaItem(mediaItem)
        return null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        instance?.onIsPlayingChanged(isPlaying)
    }

    override fun onPositionUpdated(position: Long) {
        instance?.onPositionUpdated(position)
    }

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        instance?.onLyricLine(lyricsLine)
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        // 完整时间轴：换曲后宿主可能晚一拍才给，所以它也是封面/歌词补发的触发点。
        instance?.onLyricsTimeline(lyricsLines)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        instance?.onStateChanged(state)
    }

    override fun onSeekTo(position: Long) {
        // 用户跳转后立即同步位置，避免 APP 侧进度条滞后
        instance?.onPositionUpdated(position)
    }

    companion object {
        /**
         * 由插件 start() 设置、stop() 清空。
         * 使用 @Volatile 保证跨线程可见性。
         */
        @Volatile
        var instance: MediaState? = null
            private set

        fun attach(state: MediaState?) {
            instance = state
        }
    }
}
