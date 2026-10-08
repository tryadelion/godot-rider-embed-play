import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.sleepyfant"
version = "0.1.3"

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
            <h3>0.1.3</h3>
            <ul>
              <li>Debug a Godot Scene configuration to run it in the preview with Rider's GDScript debugger attached,
                so breakpoints work without starting the debugger by hand. Debug on a .tscn now uses the preview too.</li>
              <li>The pause overlay shows script errors styled like an exception, cut to two lines, with a link to the
                line that stopped the game.</li>
              <li>Leave Scene empty to run the project's main scene, like Rider's Player configuration but in the preview.</li>
              <li>Replace a platform API scheduled for removal, so newer IDE builds no longer flag the plugin.</li>
            </ul>
            <h3>0.1.2</h3>
            <ul>
              <li>Play again: once a game ends, whether by Stop, the Run window or the game quitting on its own,
                the Play Preview's Stop button turns into Play and lets you start that scene again from the preview.</li>
              <li>Running a configuration again reuses its stopped tab instead of adding a new one.</li>
              <li>Screen shapes: preview at 21:9, 16:10, 16:9, 4:3, Standard Mobile (19.5:9) or Narrow Mobile (20:9),
                fitted to the panel's width and height. Phone screens can be turned sideways.</li>
              <li>Screenshots from the preview toolbar, at 1×, 2× or 4× the view's resolution. Saved as PNG and
                copied to the clipboard.</li>
              <li>Mute / unmute the game from the preview.</li>
              <li>Game speed: set the time scale from 0.1× to 4×.</li>
              <li>The preview's controls moved into a toolbar row, with status on its own line below.</li>
            </ul>
            <h3>0.1.1</h3>
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
    // Either the file paths (CERTIFICATE_CHAIN_FILE / PRIVATE_KEY_FILE)
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
    // The Gradle plugin does not declare that the check consumes signPlugin's output; Gradle 9 rejects that.
    verifyPluginSignature {
        dependsOn(signPlugin)
    }

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
