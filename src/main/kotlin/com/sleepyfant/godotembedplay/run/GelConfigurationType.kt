package com.sleepyfant.godotembedplay.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.NotNullLazyValue
import javax.swing.Icon

object GelIcons {
    val GODOT: Icon = IconLoader.getIcon("/icons/godot.svg", GelIcons::class.java)
}

class GelConfigurationType : ConfigurationTypeBase(
    ID,
    "Godot Scene",
    "Run a Godot scene embedded in the IDE",
    NotNullLazyValue.createValue { GelIcons.GODOT },
) {
    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId(): String = "GodotScene"

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            GelRunConfiguration(project, this, "Godot Scene")

        override fun getOptionsClass(): Class<out BaseState> = GelRunOptions::class.java
    }

    init {
        addFactory(factory)
    }

    companion object {
        const val ID = "GodotExtendedLaunch"
        fun getInstance(): GelConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(GelConfigurationType::class.java)
    }
}
