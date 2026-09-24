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
    compilerOptions {
        // Real JVM default methods only: no forwarding stubs for the platform's interface defaults,
        // which the plugin verifier would report as internal/deprecated API use.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.sleepyfant.godot-embed-play"
        name = "Godot Embed Play"
        version = project.version.toString()
        vendor {
            name = "Sleepyfant Software"
        }
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>0.1.0</h3>
            <ul>
              <li>First release: run Godot 4 scenes inside an IDE tool window.</li>
              <li>Windowless mode on macOS; hover-based input with drag gestures.</li>
              <li>Pause overlay while the Godot editor's debugger (and Rider through it) holds a breakpoint.</li>
            </ul>
        """.trimIndent()
    }
    instrumentCode = false
    buildSearchableOptions = false

    // Secrets come from the environment only; never commit them.
    // Either the file paths (CERTIFICATE_CHAIN_FILE / PRIVATE_KEY_FILE, used by scripts/sign-plugin.sh)
    // or the file contents (CERTIFICATE_CHAIN / PRIVATE_KEY, handy for CI secrets).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        certificateChainFile = layout.file(providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { File(it) })
        privateKeyFile = layout.file(providers.environmentVariable("PRIVATE_KEY_FILE").map { File(it) })
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // Ship the license and attribution inside the plugin jar.
    processResources {
        from(files("LICENSE", "NOTICE")) {
            into("META-INF")
        }
    }
    wrapper {
        gradleVersion = "9.6.0"
    }
}
