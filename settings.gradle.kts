pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            // JitPack 的多模块发布把 groupId 改成了仓库坐标，必须显式映射，
            // 否则 Gradle 会去找 com.xuncorp.spw.workshop:...gradle.plugin 而解析失败
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:" +
                        "spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
        google()
    }
}

rootProject.name = "spw-remote-plugin"
