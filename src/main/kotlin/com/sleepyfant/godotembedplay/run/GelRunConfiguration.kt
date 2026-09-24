package com.sleepyfant.godotembedplay.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GelRunOptions : LocatableRunConfigurationOptions() {
    var godotExecutable by string("godot")
    var projectDir by string("")
    var scenePath by string("")
    var launcherScript by string("")
    var launcherArgs by string("")
    var godotArgs by string("")
    var renderingDriver by string("")
    var hiDpi by property(true)
    /** Frames per second streamed to the IDE; 0 = every frame. The game itself is not capped. */
    var streamFps by property(60)
    var syncReadback by property(false)
    /** macOS: run Godot windowless (--embedded) so mouse capture never touches the real cursor. */
    var embedded by property(true)
    /** Godot editor debug server port to relay the game's debugger to (Rider debugs through it); 0 = off. */
    var editorDebugPort by property(6007)
}

class GelRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<GelRunOptions>(project, factory, name) {

    public override fun getOptions(): GelRunOptions = super.getOptions() as GelRunOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GelSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        GelRunState(environment, this)

    override fun checkConfiguration() {
        val o = options
        if (o.scenePath.isNullOrBlank()) throw RuntimeConfigurationError("Scene is not set")
        val dir = resolvedProjectDir()
        if (!Files.isRegularFile(dir.resolve("project.godot"))) {
            throw RuntimeConfigurationError("No project.godot in $dir")
        }
        if (!o.launcherScript.isNullOrBlank() && !Files.isRegularFile(resolveAgainstProject(o.launcherScript!!))) {
            throw RuntimeConfigurationError("Launcher script not found: ${o.launcherScript}")
        }
    }

    override fun suggestedName(): String? =
        options.scenePath?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

    fun resolvedProjectDir(): Path {
        val raw = options.projectDir?.takeIf { it.isNotBlank() } ?: project.basePath ?: "."
        return Paths.get(expandHome(raw)).toAbsolutePath().normalize()
    }

    fun resolveAgainstProject(path: String): Path {
        val p = Paths.get(expandHome(path))
        return (if (p.isAbsolute) p else resolvedProjectDir().resolve(p)).normalize()
    }

    /** Scene as a `res://` path, whatever form the user typed (res://, relative, absolute). */
    fun resolvedScene(): String {
        val raw = options.scenePath?.trim().orEmpty()
        if (raw.startsWith("res://")) return raw
        val p = Paths.get(expandHome(raw))
        if (p.isAbsolute) {
            val rel = resolvedProjectDir().relativize(p.normalize()).toString().replace('\\', '/')
            if (rel.startsWith("..")) throw RuntimeConfigurationError("Scene $raw is outside the project dir")
            return "res://$rel"
        }
        return "res://" + raw.trimStart('/').replace('\\', '/')
    }

    private fun expandHome(s: String): String =
        if (s.startsWith("~/")) System.getProperty("user.home") + s.substring(1) else s
}
