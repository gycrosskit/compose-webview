plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

allprojects {
    group = providers.environmentVariable("GROUP").orElse("com.github.gycrosskit.compose-webview").get()
    version = providers.environmentVariable("VERSION").orElse("1.0.2").get()
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
                pom.licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
            repositories.maven {
                name = "staging"
                url = rootProject.layout.buildDirectory.dir("maven").get().asFile.toURI()
            }
        }
    }
}
