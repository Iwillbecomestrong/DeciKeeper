pluginManagement {
    repositories {
        // 【核心修改】这里添加了阿里云插件镜像，加速 Gradle 下载
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        // 原有配置作为备用
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 【核心修改】这里添加了阿里云依赖库镜像，加速第三方库下载
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }

        // 原有配置作为备用
        google()
        mavenCentral()
        // 保留你之前添加的 jitpack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DeciKeeper"
include(":app")