package com.sleepyfant.godotembedplay.view

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** Saves screenshots from the Play Preview as PNGs and copies them to the clipboard. */
object GelScreenshots {
    private const val NOTIFICATION_GROUP = "Godot Embed Play"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** Folder the PNGs go to: `~/Pictures/Godot Embed Play`. */
    val folder: Path get() = Path.of(System.getProperty("user.home"), "Pictures", "Godot Embed Play")

    /** Encodes off the EDT, then copies to the clipboard and reports where the file went. */
    fun save(project: Project, sceneTitle: String, image: BufferedImage) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val base = "${sceneTitle.substringBeforeLast('.').ifBlank { "scene" }}_${LocalDateTime.now().format(STAMP)}"
            val file = try {
                Files.createDirectories(folder)
                var f = folder.resolve("$base.png")
                var n = 2
                while (Files.exists(f)) f = folder.resolve("${base}_${n++}.png")
                ImageIO.write(image, "png", f.toFile())
                f
            } catch (e: IOException) {
                notify(project, "Screenshot not saved", e.message ?: e.toString(), NotificationType.ERROR)
                null
            }
            ApplicationManager.getApplication().invokeLater {
                CopyPasteManager.getInstance().setContents(ImageTransferable(image))
                if (file == null) return@invokeLater
                val n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        "Screenshot saved",
                        "${image.width}×${image.height}, copied to the clipboard.<br>${file.fileName}",
                        NotificationType.INFORMATION,
                    )
                n.addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) {
                    RevealFileAction.openFile(file.toFile())
                })
                n.notify(project)
            }
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
