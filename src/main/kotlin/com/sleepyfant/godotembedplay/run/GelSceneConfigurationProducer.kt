package com.sleepyfant.godotembedplay.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/** Right-click a `.tscn` -> Run 'scene'. */
class GelSceneConfigurationProducer : LazyRunConfigurationProducer<GelRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory = GelConfigurationType.getInstance().factory

    override fun setupConfigurationFromContext(
        configuration: GelRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val (file, projectDir) = sceneAndProject(context) ?: return false
        val o = configuration.options
        o.projectDir = projectDir.path
        o.scenePath = "res://" + VfsUtilCore.getRelativePath(file, projectDir)
        configuration.name = file.nameWithoutExtension
        return true
    }

    override fun isConfigurationFromContext(configuration: GelRunConfiguration, context: ConfigurationContext): Boolean {
        val (file, projectDir) = sceneAndProject(context) ?: return false
        val o = configuration.options
        return o.scenePath == "res://" + VfsUtilCore.getRelativePath(file, projectDir) &&
            (o.projectDir.isNullOrBlank() || o.projectDir == projectDir.path)
    }

    private fun sceneAndProject(context: ConfigurationContext): Pair<VirtualFile, VirtualFile>? {
        val file = context.location?.virtualFile ?: return null
        if (file.isDirectory || !file.name.endsWith(".tscn", ignoreCase = true)) return null
        var dir = file.parent
        while (dir != null) {
            if (dir.findChild("project.godot") != null) return file to dir
            dir = dir.parent
        }
        return null
    }
}
