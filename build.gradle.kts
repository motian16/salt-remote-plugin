import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    // kapt 是 Kotlin 插件，必须显式给版本；它是独立于 kotlin-jvm 的插件 ID
    id("org.jetbrains.kotlin.kapt") version "2.4.20"
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "io.github.motian16.spwremote"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // 与官方 API/示例工程保持一致的默认方法生成策略。
        // Kotlin 2.2+ 用 -jvm-default 取代了 -Xjvm-default，取值不是 "all"
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

dependencies {
    // 宿主提供 Kotlin 标准库与 SPW API，必须 compileOnly，否则会重复打进插件
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)

    // JitPack 生成的 API POM 没有携带它的上游依赖，这两个也要显式声明，
    // 否则编译期解析不到 org.pf4j.Plugin / androidx.compose.runtime.Composable。
    // 它们同样由宿主提供，必须 compileOnly。
    compileOnly(libs.pf4j)
    compileOnly(libs.compose.runtime)
}

// 插件元数据：PluginClass 必须与主类完整类名一致
spmod {
    PluginClass = "io.github.motian16.spwremote.RemotePlugin"
    PluginId = "io.github.motian16.spwremote"
    PluginName = "SPW 远程遥控"
    // 注意：JAR Manifest 单行上限 72 字节，超长会硬折行，折点可能切断
    // UTF-8 中文字符导致宿主读出乱码，所以介绍必须保持在一行以内
    // （前缀 "Plugin-Description: " 占 20 字节，正文控制在 ~50 字节）。
    PluginDescription = "手机遥控电脑播放（局域网/公网）"
    PluginVersion = "1.2"
    PluginProvider = "motian16"
    // 生成 MANIFEST 里的 Plugin-Open-Source-Url，供宿主展示插件来源
    PluginOpenSourceUrl = "https://github.com/motian16/salt-remote-plugin"
    PluginHasConfig = true
}
