import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/"); content { includeVersionByRegex(".*", ".*", ".*(-1\\.0\\.0|-1\\.1\\.0-04)") } }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public"); content { excludeGroupByRegex("com\\.tencent.*") } }
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 测试依赖使用可用镜像，避免 Maven Central 限流阻断行为回归。
        exclusiveContent {
            forRepository { maven { url = uri("https://repo.huaweicloud.com/repository/maven") } }
            filter { includeGroup("org.robolectric") }
        }
        maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/"); content { includeVersionByRegex(".*", ".*", ".*(-1\\.0\\.0|-1\\.1\\.0-04)") } }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public"); content { excludeGroupByRegex("com\\.tencent.*") } }
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
    }
}

rootProject.name = "compose-webview"

include(":webview-core", ":webview-kuikly")
