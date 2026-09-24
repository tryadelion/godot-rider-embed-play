package com.sleepyfant.godotembedplay.view

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

    /** Adds a tab for the session to the "Play Preview" tool window and shows it. Must run on the EDT. */
    fun attach(session: GelSession, hiDpi: Boolean = true) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val manager = toolWindow.contentManager
        manager.contents.filter { it.getUserData(PLACEHOLDER) == true }.forEach { manager.removeContent(it, true) }

        val panel = GelViewPanel(project, session, hiDpi)
        val content = ContentFactory.getInstance().createContent(panel, session.title, false)
        content.isCloseable = true
        content.setDisposer(Disposable { session.close() })
        manager.addContent(content)
        manager.setSelectedContent(content)
        toolWindow.setAvailable(true, null)
        toolWindow.activate(null, true)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Play Preview"
        val PLACEHOLDER: Key<Boolean> = Key.create("gel.placeholder")
        fun getInstance(project: Project): GelViewService = project.service()
    }
}
