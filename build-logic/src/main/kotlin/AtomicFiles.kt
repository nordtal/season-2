package eu.nordtal.season.build

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes a set of files all or none: each to a temporary file beside its target first, then all moved into place. */
object AtomicFiles {
    /** @throws IllegalStateException when a file cannot be written, after removing what this call had written */
    fun write(contents: Map<File, ByteArray>) {
        val created = mutableListOf<File>()
        val temporary = linkedMapOf<File, Path>()
        var current: File? = null
        try {
            contents.forEach { (target, bytes) ->
                current = target
                val folder = target.absoluteFile.parentFile
                generateSequence(folder) { it.parentFile }.takeWhile { !it.exists() }.lastOrNull()?.let { created += it }
                Files.createDirectories(folder.toPath())
                temporary[target] = Files.createTempFile(folder.toPath(), ".${target.name}", ".tmp").also { Files.write(it, bytes) }
            }
            temporary.forEach { (target, file) ->
                current = target
                Files.move(file, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temporary.values.forEach { Files.deleteIfExists(it) }
            created.forEach { it.deleteRecursively() }
            throw IllegalStateException("$current cannot be written (${e.javaClass.simpleName}: ${e.message}), so nothing was written.", e)
        }
    }
}
