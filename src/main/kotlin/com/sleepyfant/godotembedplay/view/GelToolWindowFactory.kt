package com.sleepyfant.godotembedplay.view

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import javax.swing.SwingConstants

class GelToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val label = JBLabel(
            "<html><center>No Godot scene running.<br>" +
                "Run a <b>Godot Scene</b> configuration, or right-click a .tscn file &rarr; Run.</center></html>",
            SwingConstants.CENTER,
        ).apply { border = JBUI.Borders.empty(16) }
        val content = ContentFactory.getInstance().createContent(label, "", false)
        content.isCloseable = false
        content.putUserData(GelViewService.PLACEHOLDER, true)
        toolWindow.contentManager.addContent(content)
    }
}
