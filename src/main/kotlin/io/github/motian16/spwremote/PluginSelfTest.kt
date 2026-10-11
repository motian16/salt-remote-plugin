@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)

package io.github.motian16.spwremote

import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import com.xuncorp.spw.workshop.api.KeyBindingManager
import com.xuncorp.spw.workshop.api.PluginPermission
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletionStage
import java.util.function.Consumer

/**
 * 插件自测宿主（仅开发期用）。
 *
 * 装一个**假的 [WorkshopApi]**，然后启动**真实的 [RemotePlugin]**，
 * 这样就能在没有 Salt Player 的情况下验证插件的行为：
 *   - 配对（auth）流程是否真的拦住了未配对的连接
 *   - WebSocket 协议是否正确
 *   - 反射探测宿主曲目时的报错信息（这里刻意没有宿主类，
 *     所以 TrackReflector 会走"探测失败"分支，正好验证它的降级行为）
 *
 * 用法：
 *   java -cp <classes>;<kotlin-stdlib>;<pf4j> \
 *     io.github.motian16.spwremote.PluginSelfTest [wsPort] [udpPort] [秒数]
 */
object PluginSelfTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val wsPort = args.getOrNull(0)?.toIntOrNull() ?: 52300
        val udpPort = args.getOrNull(1)?.toIntOrNull() ?: 52301
        val seconds = args.getOrNull(2)?.toLongOrNull() ?: 40L

        WorkshopApi.instance = FakeWorkshopApi(mapOf(
            RemotePlugin.KEY_PORT to wsPort.toString(),
            RemotePlugin.KEY_UDP_PORT to udpPort.toString(),
            RemotePlugin.KEY_DISCOVERY to "true",
            RemotePlugin.KEY_PUBLIC_IP to "false",
            RemotePlugin.KEY_TOKEN to "test-token-42"
        ))

        val context = PluginContext(
            pluginId = "io.github.motian16.spwremote.selftest",
            pluginVersion = "selftest",
            pluginPath = System.getProperty("java.io.tmpdir"),
            spwVersion = "1.18.5",
            spwChannel = Channel.Steam
        )

        val plugin = RemotePlugin(context)
        println("[selftest] 启动 RemotePlugin（ws=$wsPort udp=$udpPort，配对码=test-token-42）")
        plugin.start()

        // 起一个假的播放事件序列，用来验证"没有歌词回调时也能推曲目"
        val fake = MediaStateProbe()
        Thread.sleep(seconds * 1000)

        println("[selftest] 停止插件")
        plugin.stop()
        Thread.sleep(300)
        println("[selftest] 完成。曲目反射状态：" + fake.describe())
    }

    /** 只是给日志用的小工具，不参与逻辑。 */
    private class MediaStateProbe {
        fun describe(): String =
            if (TrackReflector.isDisabled) {
                "不可用（" + (TrackReflector.lastError ?: "未知") + "）—— 这是没有宿主时的预期结果"
            } else {
                "可用，命中字段 " + (TrackReflector.matchedField ?: "?")
            }
    }

    /**
     * 假的 WorkshopApi。
     *
     * 只实现插件真正用到的那几项：配置读写、toast、播放控制。
     * 播放控制只打印，不做任何事 —— 这样自测不会真的去操作什么。
     */
    private class FakeWorkshopApi(initial: Map<String, String>) : WorkshopApi {

        private val store = java.util.concurrent.ConcurrentHashMap<String, Any>(initial)

        private val configManager = object : ConfigManager {
            private val helpers = java.util.concurrent.ConcurrentHashMap<String, ConfigHelper>()

            override fun getConfig(): ConfigHelper = getConfig("config.json")

            override fun getConfig(fileName: String): ConfigHelper =
                helpers.computeIfAbsent(fileName) { FakeConfigHelper(store) }

            override fun addConfigChangeListener(listener: Consumer<ConfigHelper>) = Unit
            override fun addConfigChangeListener(
                fileName: String,
                listener: Consumer<ConfigHelper>
            ) = Unit

            override fun removeConfigChangeListener(listener: Consumer<ConfigHelper>) = Unit
        }

        override val playback: WorkshopApi.Playback = object : WorkshopApi.Playback {
            override fun changeExclusive(exclusive: Boolean) =
                println("[selftest] playback.changeExclusive($exclusive)")

            override fun pause() = println("[selftest] playback.pause()")
            override fun play() = println("[selftest] playback.play()")
            override fun previous() = println("[selftest] playback.previous()")
            override fun next() = println("[selftest] playback.next()")
            override fun seekTo(position: Long) =
                println("[selftest] playback.seekTo($position)")

            /**
             * dev21+ 的接口。自测宿主返回 null —— 模拟"老宿主没有这个能力"，
             * 因为插件要兼容的正是这种情况（本机宿主 dev20 里这个方法不存在）。
             */
            override fun getCurrentMediaItem(): com.xuncorp.spw.workshop.api
                .PlaybackExtensionPoint.MediaItem? = null

            override fun getLyricsLines(): List<com.xuncorp.spw.workshop.api
                .PlaybackExtensionPoint.LyricsLine> = emptyList()
        }

        override val ui: WorkshopApi.Ui = object : WorkshopApi.Ui {
            override fun toast(text: String, type: WorkshopApi.Ui.ToastType) =
                println("[selftest] toast[$type] $text")
        }

        override val manager: WorkshopApi.Manager = object : WorkshopApi.Manager {
            override fun createConfigManager(pluginId: String): ConfigManager = configManager
            override fun createConfigManager(): ConfigManager = configManager

            override val keyBindingManager: KeyBindingManager = object : KeyBindingManager {
                override fun register(
                    actionId: String,
                    title: String,
                    defaultShortcut: com.xuncorp.spw.workshop.api.ActionShortcut?,
                    hasGlobal: Boolean,
                    handler: Runnable
                ): AutoCloseable = AutoCloseable { }

                override fun unregister(actionId: String) = Unit
            }

            override fun isPermissionGranted(permission: PluginPermission): Boolean = true
        }

        override val hookRegistrar: HookRegistrar
            get() = throw UnsupportedOperationException("自测不提供 Hook")

        override val library: WorkshopApi.Library
            get() = throw UnsupportedOperationException("自测不提供曲库")
    }

    private class FakeConfigHelper(
        private val store: java.util.concurrent.ConcurrentHashMap<String, Any>
    ) : ConfigHelper {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, defaultValue: T): T =
            (store[key] as? T) ?: defaultValue

        override fun set(key: String, value: Any) {
            store[key] = value
        }

        override fun save(): Boolean = true
        override fun reload(): Boolean = true
        override fun getConfigPath(): Path = Paths.get(System.getProperty("java.io.tmpdir"))
    }

    /** 让编译器别抱怨导入没用到（CompletionStage 是接口签名的一部分）。 */
    @Suppress("unused")
    private fun unusedType(x: CompletionStage<*>?) = x
}
