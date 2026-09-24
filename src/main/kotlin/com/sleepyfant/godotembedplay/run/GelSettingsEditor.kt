package com.sleepyfant.godotembedplay.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class GelSettingsEditor(private val project: Project) : SettingsEditor<GelRunConfiguration>() {

    private val scene = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.singleFile().withExtensionFilter("tscn").withTitle("Select Scene"), project))
    }
    private val projectDir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.singleDir().withTitle("Godot Project Directory"), project))
    }
    private val godotExecutable = JBTextField()
    private val launcherScript = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.singleFile().withTitle("Launcher Script"), project))
    }
    private val launcherArgs = JBTextField()
    private val godotArgs = JBTextField()
    private val renderingDriver = ComboBox(arrayOf("", "vulkan", "metal", "d3d12", "opengl3")).apply { isEditable = true }
    private val hiDpi = JBCheckBox("Render at native (HiDPI) resolution")
    private val streamFps = JBIntSpinner(60, 0, 1000, 5)
    private val embedded = JBCheckBox("macOS: windowless embedded mode (no Godot window, safe mouse capture)")
    private val editorDebugPort = JBIntSpinner(6007, 0, 65535, 1)
    private val syncReadback = JBCheckBox("Synchronous GPU readback (slower, for renderers without async support)")

    override fun createEditor(): JComponent = panel {
        row("Scene:") {
            cell(scene).align(AlignX.FILL)
                .comment("res://path.tscn, or a path relative to the project directory")
        }
        row("Project directory:") {
            cell(projectDir).align(AlignX.FILL).comment("Folder with project.godot; empty = IDE project root")
        }
        row("Godot executable:") { cell(godotExecutable).align(AlignX.FILL) }
        row("Rendering driver:") { cell(renderingDriver) }
        row("Extra Godot args:") { cell(godotArgs).align(AlignX.FILL) }
        group("Launcher script (optional)") {
            row("Script:") {
                cell(launcherScript).align(AlignX.FILL)
                    .comment("Runs instead of the executable; must end with <code>exec godot --path . \"\$@\"</code> " +
                        "so the Godot arguments pass through, e.g. a script that sets up the environment first")
            }
            row("Script args:") { cell(launcherArgs).align(AlignX.FILL).comment("Passed before the Godot arguments") }
        }
        row { cell(hiDpi) }
        row("Stream FPS cap:") {
            cell(streamFps).comment("Frames sent to the IDE per second; 0 = every frame. The game itself runs uncapped.")
        }
        row { cell(embedded) }
        row("Godot editor debug port:") {
            cell(editorDebugPort).comment("If the Godot editor listens here (Debug → Keep Debug Server Open), breakpoints " +
                "and stepping go through it, so Rider's GDScript debugger drives the game. 0 = never relay.")
        }
        row { cell(syncReadback) }
    }

    override fun resetEditorFrom(s: GelRunConfiguration) {
        val o = s.options
        scene.text = o.scenePath.orEmpty()
        projectDir.text = o.projectDir.orEmpty()
        godotExecutable.text = o.godotExecutable.orEmpty()
        launcherScript.text = o.launcherScript.orEmpty()
        launcherArgs.text = o.launcherArgs.orEmpty()
        godotArgs.text = o.godotArgs.orEmpty()
        renderingDriver.selectedItem = o.renderingDriver.orEmpty()
        hiDpi.isSelected = o.hiDpi
        streamFps.number = o.streamFps
        syncReadback.isSelected = o.syncReadback
        embedded.isSelected = o.embedded
        editorDebugPort.number = o.editorDebugPort
    }

    override fun applyEditorTo(s: GelRunConfiguration) {
        val o = s.options
        o.scenePath = scene.text.trim()
        o.projectDir = projectDir.text.trim()
        o.godotExecutable = godotExecutable.text.trim()
        o.launcherScript = launcherScript.text.trim()
        o.launcherArgs = launcherArgs.text.trim()
        o.godotArgs = godotArgs.text.trim()
        o.renderingDriver = (renderingDriver.editor.item?.toString() ?: "").trim()
        o.hiDpi = hiDpi.isSelected
        o.streamFps = streamFps.number
        o.syncReadback = syncReadback.isSelected
        o.embedded = embedded.isSelected
        o.editorDebugPort = editorDebugPort.number
    }
}
