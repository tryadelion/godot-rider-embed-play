package com.sleepyfant.godotembedplay.session

import java.nio.file.Files
import java.nio.file.Path

/** Installs the GDScript shim into the Godot project's `.godot/` folder (git-ignored by convention). */
object GelShim {
    const val RES_PATH = "res://.godot/gel/gel_shim.gd"

    fun install(projectDir: Path): String {
        val bytes = GelShim::class.java.getResourceAsStream("/godot/gel_shim.gd")?.use { it.readBytes() }
            ?: error("gel_shim.gd missing from plugin resources")
        val target = projectDir.resolve(".godot").resolve("gel").resolve("gel_shim.gd")
        Files.createDirectories(target.parent)
        if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
            Files.write(target, bytes)
        }
        return RES_PATH
    }
}
