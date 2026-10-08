package com.sleepyfant.godotembedplay.view

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.sleepyfant.godotembedplay.session.GelSession

@Service(Service.Level.PROJECT)
class GelViewService(private val project: Project) {

    /**
     * Adds a tab for the session to the "Play Preview" tool window and shows it. A stopped tab of the same
     * run configuration (e.g. the one whose Play button started this run) is replaced in place.
     * Must run on the EDT.
     */
    fun attach(session: GelSession, hiDpi: Boolean, environment: ExecutionEnvironment) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val manager = toolWindow.contentManager
        manager.contents.filter { it.getUserData(PLACEHOLDER) == true }.forEach { manager.removeContent(it, true) }

        val profile = environment.runProfile.name
        val stale = manager.contents.firstOrNull {
            it.getUserData(PROFILE) == profile && (it.component as? GelViewPanel)?.isStopped == true
        }

        val debugging = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val panel = GelViewPanel(project, session, hiDpi, debugging) { debug ->
            val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
            if (executor.id == environment.executor.id) ExecutionUtil.restart(environment)
            else environment.runnerAndConfigurationSettings?.let { ProgramRunnerUtil.executeConfiguration(it, executor) }
        }
        val content = ContentFactory.getInstance().createContent(panel, session.title, false)
        content.isCloseable = true
        content.putUserData(PROFILE, profile)
        content.setDisposer(Disposable { session.close() })
        if (stale != null) {
            manager.addContent(content, manager.getIndexOfContent(stale))
            manager.removeContent(stale, true)
        } else {
            manager.addContent(content)
        }
        manager.setSelectedContent(content)
        toolWindow.setAvailable(true, null)
        toolWindow.activate(null, true)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Play Preview"
        val PLACEHOLDER: Key<Boolean> = Key.create("gel.placeholder")
        /** Run configuration name of the session shown in a tab. */
        private val PROFILE: Key<String> = Key.create("gel.profile")
        fun getInstance(project: Project): GelViewService = project.service()
    }
}
