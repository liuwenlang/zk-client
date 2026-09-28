import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
    // server-side only: needed by the embedded ZooKeeper in ZkSessionIntegrationTest
    testImplementation("io.dropwizard.metrics:metrics-core:4.2.25")
    testImplementation("org.xerial.snappy:snappy-java:1.1.10.5")

    implementation("org.apache.zookeeper:zookeeper:3.9.3") {
        exclude(group = "org.slf4j", module = "slf4j-api") // provided by the platform
        exclude(group = "io.netty")                        // classic NIO client is enough
        exclude(group = "org.eclipse.jetty")               // admin server only
        exclude(group = "jakarta.servlet")
        exclude(group = "io.dropwizard.metrics")
        exclude(group = "com.googlecode.json-simple")
        exclude(group = "org.xerial.snappy")
        exclude(group = "org.apache.yetus")
        exclude(group = "commons-cli")
        exclude(group = "org.apache.commons", module = "commons-cli")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
}

intellijPlatform {
    pluginConfiguration {
        // Patched into plugin.xml <change-notes> at build time.
        changeNotes.set(provider {
            changelog.renderItem(changelog.getLatest(), Changelog.OutputType.HTML)
        })
    }

    publishing {
        // Upload token from https://plugins.jetbrains.com/author/me/tokens
        // Provide at publish time:  PUBLISH_TOKEN=... ./gradlew publishPlugin
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    pluginVerification {
        ides {
            // Default (recommended()) also downloads the latest IDE release for
            // forward-compat verification; this network's proxy kills Gradle's TLS
            // handshakes to the JetBrains CDN. Verify against the locally cached
            // target IDE (2025.3.6.1) only.
            current()
        }
    }
}
