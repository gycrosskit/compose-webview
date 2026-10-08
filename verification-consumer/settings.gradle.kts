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
        if (providers.gradleProperty("remoteOnly").isPresent) {
            exclusiveContent {
                forRepository { maven { url = uri("https://jitpack.io") } }
                filter { includeGroup("com.github.gycrosskit.compose-webview") }
            }
        } else {
        maven { url = uri(providers.gradleProperty("webViewMavenRepo").orElse("../build/maven").get()); content { includeGroup("com.github.gycrosskit.compose-webview") } }
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

rootProject.name = "webview-artifact-consumer"

// 本地候选源码消费，默认仍消费发布坐标；不得用此模式声称远程版本已发布。
if (providers.gradleProperty("verifyLocalSource").isPresent) {
    includeBuild("..") {
        dependencySubstitution {
            substitute(module("com.github.gycrosskit.compose-webview:compose-webview")).using(project(":"))
            substitute(module("com.github.gycrosskit.compose-webview:webview-core")).using(project(":webview-core"))
            substitute(module("com.github.gycrosskit.compose-webview:webview-kuikly")).using(project(":webview-kuikly"))
        }
    }
}
