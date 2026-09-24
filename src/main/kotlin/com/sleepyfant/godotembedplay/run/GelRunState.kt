package com.sleepyfant.godotembedplay.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.sleepyfant.godotembedplay.session.GelSession
import com.sleepyfant.godotembedplay.session.GodotDebugServer
import com.sleepyfant.godotembedplay.session.GelShim
import com.sleepyfant.godotembedplay.view.GelViewService
import java.nio.file.Files

class GelRunState(env: ExecutionEnvironment, private val config: GelRunConfiguration) : CommandLineState(env) {

    override fun startProcess(): ProcessHandler {
        val o = config.options
        val projectDir = config.resolvedProjectDir()
        val scene = config.resolvedScene()
        val shimRes = try {
            GelShim.install(projectDir)
        } catch (e: Exception) {
            throw ExecutionException("Cannot write shim into $projectDir/.godot: ${e.message}", e)
        }

        val session = GelSession(scene.substringAfterLast('/'))

        val godotSide = mutableListOf<String>()
        if (o.embedded && SystemInfo.isMac) {
            // Windowless: no OS window, no Dock icon, mouse mode/cursor reported over the debugger.
            val dbg = GodotDebugServer(session.title, o.editorDebugPort)
            session.debugServer = dbg
            dbg.start()
            godotSide += listOf("--embedded", "--remote-debug", "tcp://127.0.0.1:${dbg.port}")
        }
        o.renderingDriver?.takeIf { it.isNotBlank() }?.let { godotSide += listOf("--rendering-driver", it) }
        godotSide += splitArgs(o.godotArgs)
        godotSide += listOf("--windowed", "--resolution", "64x64", "-s", shimRes, "--")
        godotSide += "--gel-port=${session.port}"
        godotSide += "--gel-scene=$scene"
        godotSide += "--gel-w=1280"
        godotSide += "--gel-h=720"
        if (o.syncReadback) godotSide += "--gel-sync=1"
        godotSide += "--gel-fps=${o.streamFps.coerceIn(0, 1000)}"

        val cmd = GeneralCommandLine()
        val launcher = o.launcherScript?.takeIf { it.isNotBlank() }
        if (launcher != null) {
            val script = config.resolveAgainstProject(launcher)
            if (Files.isExecutable(script)) {
                cmd.exePath = script.toString()
            } else {
                cmd.exePath = "/bin/sh"
                cmd.addParameter(script.toString())
            }
            cmd.addParameters(splitArgs(o.launcherArgs))
            cmd.addParameters(godotSide)
        } else {
            cmd.exePath = o.godotExecutable?.takeIf { it.isNotBlank() } ?: "godot"
            cmd.addParameters("--path", projectDir.toString())
            cmd.addParameters(godotSide)
        }
        cmd.workDirectory = projectDir.toFile()
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        cmd.charset = Charsets.UTF_8

        val handler = try {
            KillableColoredProcessHandler(cmd)
        } catch (e: ExecutionException) {
            session.close()
            throw e
        }
        ProcessTerminatedListener.attach(handler)
        session.processHandler = handler
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = session.onProcessExited(event.exitCode)
        })
        session.start()

        val project = environment.project
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) GelViewService.getInstance(project).attach(session, o.hiDpi)
        }
        return handler
    }

    private fun splitArgs(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return com.intellij.util.execution.ParametersListUtil.parse(s)
    }
}
