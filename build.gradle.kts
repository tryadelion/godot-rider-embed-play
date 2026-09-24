import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.sleepyfant"
version = "0.1.0"

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val localIde: String? = (localProps.getProperty("gel.localIde")
    ?: providers.gradleProperty("gel.localIde").orNull)
    ?.trim()?.takeIf { it.isNotEmpty() }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (localIde != null) {
            local(localIde)
        } else {
            rider("2026.2.1")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.sleepyfant.godot-extended-launch"
        name = "Godot Extended Launch"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
    instrumentCode = false
    buildSearchableOptions = false
}

tasks {
    wrapper {
        gradleVersion = "9.6.0"
    }
}
