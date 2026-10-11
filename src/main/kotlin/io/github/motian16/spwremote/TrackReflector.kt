package io.github.motian16.spwremote

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 当前曲目查询。
 *
 * ## 为什么需要它
 *
 * 宿主（Salt Player 1.18.5 / API `0.1.0-dev20`）**没有**
 * `WorkshopApi.Playback.getCurrentMediaItem()` —— 那个接口是 dev21 才加的
 * （本机宿主归档里该方法名出现 0 次）。
 *
 * 所以插件原先只能从歌词回调 `onBeforeLoadLyrics` 拿 `MediaItem`。这有两个洞：
 *   1. 歌词有**缓存**或**内嵌**时，宿主可能根本不调用这个回调，
 *      于是"开始播放"之后插件手里一直没有曲目信息；
 *   2. 切歌时才必然触发一次，所以表现为"切到下一首才显示"——这正是用户报的现象。
 *
 * ## 做法
 *
 * 宿主的 `PlaybackService` 上有 `currentMediaItem` / `selectedMediaItem` 字段，
 * 类型是内部的 `com.xuncorp.pisces.PiscesMediaItem`（带 id / title / artist /
 * album / albumArtist / path，比公开 API 的 MediaItem 还多一个 id）。
 * 这里用反射把它读出来。
 *
 * ## 定位：尽力而为，不是正式接口
 *
 * **反射读宿主内部字段不受任何兼容性保证**，字段名随宿主版本和混淆变化。
 * 所以这里的策略是：
 *   - 全部用 runCatching 包住，任何一步失败就永久降级（[disabled]），
 *     退回到"只能靠歌词回调"的老行为；
 *   - 每次宿主版本变化（[versionTag] 不同）重新探测一次；
 *   - 调用方（[MediaState]）必须把 null 当成正常情况处理。
 *
 * 将来宿主升到 dev21+ 时，应优先走官方 `getCurrentMediaItem()`，
 * 反射只作为老宿主的兜底。
 */
object TrackReflector {

    /** 读到的最简曲目信息，字段名与公开 API 对齐，另外多一个 id。 */
    data class Track(
        val id: String?,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val path: String,
        /** 当前是否正在播放。读不到时为 null，调用方保留原值。 */
        val isPlaying: Boolean? = null,
        /** 当前播放位置（毫秒）。读不到时为 null。 */
        val positionMs: Long? = null
    ) {
        val isEmpty: Boolean
            get() = title.isBlank() && path.isBlank() && artist.isBlank()
    }

    @Volatile
    private var disabled = false

    @Volatile
    private var versionTag: String? = null

    /** 已经解析好的 service 实例与字段，避免每次调用都重新查。 */
    @Volatile
    private var serviceInstance: Any? = null

    @Volatile
    private var mediaField: Field? = null

    @Volatile
    private var mediaFieldName: String? = null

    /** 缓存的内层 getter：PiscesMediaItem 的 title/artist/... 读法 */
    private val innerGetters = java.util.concurrent.ConcurrentHashMap<String, Method?>()

    /** 宿主的 Application 实例（用来找 Service）。只探测一次。 */
    @Volatile
    private var applicationRef: Any? = null

    /** 探测失败的原因，给日志用。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 读当前曲目。读不到返回 null —— **这是很常见的正常结果**，调用方必须能处理。
     */
    fun current(spwVersion: String): Track? {
        if (disabled) return null

        // 宿主版本变了就重新探测（插件可能在宿主升级后继续运行）
        if (versionTag != spwVersion) {
            synchronized(this) {
                if (versionTag != spwVersion) {
                    versionTag = spwVersion
                    reset()
                }
            }
        }

        return try {
            readInternal()
        } catch (t: Throwable) {
            disable("反射读取失败：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    private fun readInternal(): Track? {
        val service = resolveService() ?: return null
        val field = resolveField(service) ?: return null
        val item = field.get(service) ?: return null

        val playState = readPlayState(item)
        val track = readPiscesItem(item, playState.first, playState.second)
        return track
    }

    /**
     * 读播放/暂停与当前位置。
     *
     * 宿主把这两项放在**播放器自己**身上（`PiscesMediaMonitor` 的
     * `_isPlaying` / `_progress`），而 `currentMediaItem` 指向的媒体项
     * 也持有同一个播放器引用。所以从媒体项出发沿对象图找
     * "名字里带 isPlaying / progress 的字段"即可，不必写死路径。
     *
     * 找不到就返回 (null, null)：这不是错误 —— 老版本或字段改名时
     * 插件退回到"只靠事件更新播放状态"的老行为。
     */
    private fun readPlayState(item: Any): Pair<Boolean?, Long?> {
        if (mediaMonitor == null) mediaMonitor = findMediaMonitor(item)
        val monitor = mediaMonitor ?: return null to null

        val playing = runCatching {
            val f = monitor.javaClass.declaredFields.firstOrNull { it.name == "_isPlaying" }
                ?: monitor.javaClass.declaredFields.firstOrNull {
                    it.name.contains("isPlaying", ignoreCase = true) &&
                        (it.type == Boolean::class.javaPrimitiveType ||
                            it.type == java.lang.Boolean::class.java)
                }
                ?: return@runCatching null
            f.isAccessible = true
            f.get(monitor) as? Boolean
        }.getOrNull()

        val progress = runCatching {
            val f = monitor.javaClass.declaredFields.firstOrNull {
                it.name == "_progress" || it.name.contains("progress", ignoreCase = true)
            } ?: return@runCatching null
            f.isAccessible = true
            when (val v = f.get(monitor)) {
                is Long -> v
                is Int -> v.toLong()
                is Double -> v.toLong()
                is Float -> v.toLong()
                else -> null
            }
        }.getOrNull()

        return playing to progress
    }

    /**
     * 从媒体项出发找 `PiscesMediaMonitor`（它持有 _isPlaying / _progress）。
     *
     * 走的是"看类型名"而不是"看字段名"：字段名随版本变化，
     * 而类名 `com.xuncorp.pisces.PiscesMediaMonitor` 是公开的模块名，稳定得多。
     */
    private fun findMediaMonitor(item: Any): Any? {
        val seen = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<Any, Boolean>()
        )
        var cls: Class<*>? = item.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (!f.type.name.contains("PiscesMediaMonitor")) continue
                val v = runCatching { f.isAccessible = true; f.get(item) }.getOrNull()
                    ?: continue
                if (seen.add(v)) return v
            }
            cls = cls.superclass
        }
        return null
    }

    @Volatile
    private var mediaMonitor: Any? = null

    /** 读内部 PiscesMediaItem 的字段。 */
    private fun readPiscesItem(
        item: Any,
        isPlaying: Boolean?,
        positionMs: Long?
    ): Track? {
        val cls = item.javaClass
        val id = readString(item, cls, "id")
        val title = readString(item, cls, "title").orEmpty()
        val artist = readString(item, cls, "artist").orEmpty()
        val album = readString(item, cls, "album").orEmpty()
        val albumArtist = readString(item, cls, "albumArtist").orEmpty()
        val path = readString(item, cls, "path").orEmpty()

        val track = Track(id, title, artist, album, albumArtist, path, isPlaying, positionMs)
        return if (track.isEmpty) null else track
    }

    /** 找到宿主的 PlaybackService 实例。 */
    private fun resolveService(): Any? {
        serviceInstance?.let { return it }

        val app = resolveApplication() ?: return null

        // 桌面版是单例 Service：优先找 Companion / INSTANCE 上的实例字段，
        // 失败再退回"静态字段里类型匹配的那一个"。
        val serviceClass = runCatching {
            Class.forName("com.xuncorp.voxzen.service.PlaybackService")
        }.getOrNull() ?: run {
            disable("找不到 PlaybackService 类")
            return null
        }

        // 1) 静态字段里直接就是 PlaybackService 的
        for (f in serviceClass.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (!serviceClass.isAssignableFrom(f.type)) continue
            val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull()
            if (v != null && serviceClass.isInstance(v)) {
                serviceInstance = v
                return v
            }
        }

        // 2) Kotlin object 的 INSTANCE
        runCatching {
            val inst = serviceClass.getDeclaredField("INSTANCE")
            inst.isAccessible = true
            inst.get(null)
        }.getOrNull()?.let {
            if (serviceClass.isInstance(it)) {
                serviceInstance = it
                return it
            }
        }

        // 3) Application 上（以及它的父类上）的字段
        var appCls: Class<*>? = app.javaClass
        while (appCls != null && appCls != Any::class.java) {
            for (f in appCls.declaredFields) {
                val v = runCatching { f.isAccessible = true; f.get(app) }.getOrNull() ?: continue
                if (serviceClass.isInstance(v)) {
                    serviceInstance = v
                    return v
                }
            }
            appCls = appCls.superclass
        }

        disable("没有找到 PlaybackService 实例")
        return null
    }

    private fun resolveApplication(): Any? {
        applicationRef?.let { return it }
        // android.app.ActivityThread.currentApplication() 在桌面 JVM 上不存在，
        // 所以直接找宿主的 Application 类。
        val cls = runCatching { Class.forName("com.xuncorp.voxzen.VoxzenApplication") }
            .recoverCatching { Class.forName("com.xuncorp.voxzen.App") }
            .getOrNull() ?: return null

        for (f in cls.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull() ?: continue
            if (cls.isInstance(v)) {
                applicationRef = v
                return v
            }
        }
        // Kotlin object
        runCatching {
            val inst = cls.getDeclaredField("INSTANCE")
            inst.isAccessible = true
            inst.get(null)
        }.getOrNull()?.let {
            applicationRef = it
            return it
        }
        return null
    }

    /** 在 service 上找"当前曲目"字段。 */
    private fun resolveField(service: Any): Field? {
        mediaField?.let { return it }

        var cls: Class<*>? = service.javaClass
        while (cls != null && cls != Any::class.java) {
            for (name in CANDIDATE_FIELDS) {
                val f = runCatching { cls.getDeclaredField(name) }.getOrNull() ?: continue
                f.isAccessible = true
                val typeName = f.type.name
                // 只接受看起来像媒体项的字段
                if (typeName.contains("PiscesMediaItem") ||
                    typeName.contains("MediaItem") ||
                    typeName.contains("PlayableItem")
                ) {
                    mediaField = f
                    mediaFieldName = name
                    return f
                }
            }
            cls = cls.superclass
        }

        disable("PlaybackService 上找不到当前曲目字段（试过 ${CANDIDATE_FIELDS.joinToString()}）")
        return null
    }

    /**
     * 读一个字符串属性。Kotlin 的 `val` 会同时有字段和 getter，
     * 两种都试：先 getXxx()，再直接读字段。
     */
    private fun readString(item: Any, cls: Class<*>, name: String): String? {
        val cached = innerGetters[name]
        if (cached != null) {
            return runCatching { cached.invoke(item) as? String }.getOrNull()
        }
        if (innerGetters.containsKey(name)) {
            // 之前在 getter 上失败过，试字段
            return readFieldString(item, cls, name)
        }

        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val m = runCatching { cls.getMethod(getterName) }.getOrNull()
        if (m != null) {
            val v = runCatching { m.invoke(item) as? String }.getOrNull()
            if (v != null) {
                innerGetters[name] = m
                return v
            }
        }
        innerGetters[name] = null
        return readFieldString(item, cls, name)
    }

    private fun readFieldString(item: Any, cls: Class<*>, name: String): String? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return runCatching { f.get(item) as? String }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun reset() {
        serviceInstance = null
        mediaField = null
        mediaFieldName = null
        innerGetters.clear()
        applicationRef = null
        disabled = false
        lastError = null
    }

    private fun disable(reason: String) {
        disabled = true
        lastError = reason
    }

    /** 反射是否已经确认不可用（给状态消息用）。 */
    val isDisabled: Boolean get() = disabled

    /** 命中的字段名，日志里显示。 */
    val matchedField: String? get() = mediaFieldName

    private val CANDIDATE_FIELDS = arrayOf(
        "currentMediaItem",
        "selectedMediaItem",
        "mediaItem",
        "currentItem",
        "playingItem"
    )
}
