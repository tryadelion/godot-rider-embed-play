package com.sleepyfant.godotembedplay.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import org.jdom.Element

/** Rider's GDScript debugger (DAP to the Godot editor); its "Debug GDScript (Running session)" configuration attaches. */
internal const val GDSCRIPT_DEBUG_TYPE = "GDSCRIPT_DEBUG_RUN_CONFIGURATION"
private val ATTACH = Regex(""""request"\s*:\s*"Attach"""")

/**
 * Debug: runs the scene like Run, plus attaches Rider's GDScript debugger to the Godot editor, which the
 * game's debugger is relayed to (see GodotDebugServer). The attach session ends with the game.
 */
class GelDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "GodotEmbedPlayDebug"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is GelRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val project = environment.project
        val o = (environment.runProfile as GelRunConfiguration).options
        val relayed = SystemInfo.isMac && o.embedded && o.editorDebugPort > 0
        val attach = if (relayed) attachConfiguration(project) else null
        when {
            !relayed -> notify(project,
                "Breakpoints need macOS windowless mode and a Godot editor debug port; running without them.")
            attach == null -> notify(project,
                "No \"Debug GDScript (Running session)\" configuration (Rider's GDScript debugger) found; running without breakpoints.")
        }
        val result = state.execute(environment.executor, this) ?: return null
        val game = result.processHandler
        if (attach != null) ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || game.isProcessTerminated) return@invokeLater
            val attachEnv = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), attach)
                .build { descriptor ->
                    val debugger = descriptor?.processHandler ?: return@build
                    if (game.isProcessTerminated) debugger.destroyProcess()
                    else game.addProcessListener(object : ProcessListener {
                        override fun processTerminated(event: ProcessEvent) = debugger.destroyProcess()
                    })
                }
            ProgramRunnerUtil.executeConfiguration(attachEnv, false, true)
        }
        return showRunContent(result, environment)
    }

    private fun attachConfiguration(project: Project): RunnerAndConfigurationSettings? =
        RunManager.getInstance(project).allSettings.firstOrNull { s ->
            s.type.id == GDSCRIPT_DEBUG_TYPE && Element("configuration").also(s.configuration::writeExternal)
                .getChildren("option")
                .any { it.getAttributeValue("name") == "json" && ATTACH.containsMatchIn(it.getAttributeValue("value").orEmpty()) }
        }

    private fun notify(project: Project, text: String) =
        NotificationGroupManager.getInstance().getNotificationGroup("Godot Embed Play")
            .createNotification(text, NotificationType.WARNING).notify(project)
}
