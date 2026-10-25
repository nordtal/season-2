package eu.nordtal.season.build

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * Writes and deletes a set of files all or none. Each new content goes to a temporary file beside its target and each
 * deleted file is moved aside, and only when all of that succeeded are the new files moved into place.
 */
object AtomicFiles {
    /** Writes each file of [contents], or deletes it where its content is null. */
    fun write(contents: Map<File, ByteArray?>) {
        val created = mutableListOf<File>()
        val written = linkedMapOf<File, Path>()
        val parked = linkedMapOf<File, Path>()
        var current: File? = null
        try {
            contents.forEach { (target, bytes) ->
                current = target
                val folder = target.absoluteFile.parentFile.toPath()
                if (bytes == null) {
                    parked[target] =
                        Files.move(target.toPath(), folder.resolve(".${target.name}.${UUID.randomUUID()}.deleted"), ATOMIC_MOVE)
                } else {
                    generateSequence(folder.toFile()) { it.parentFile }.takeWhile { !it.exists() }.lastOrNull()?.let { created += it }
                    Files.createDirectories(folder)
                    written[target] = Files.createTempFile(folder, ".${target.name}", ".tmp").also { Files.write(it, bytes) }
                }
            }
            written.forEach { (target, file) ->
                current = target
                Files.move(file, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            written.values.forEach { Files.deleteIfExists(it) }
            created.forEach { it.deleteRecursively() }
            parked.forEach { (target, file) -> Files.move(file, target.toPath(), ATOMIC_MOVE) }
            throw IllegalStateException("$current cannot be written (${e.javaClass.simpleName}: ${e.message}), so nothing was written.", e)
        }
        val left = parked.filterValues { !it.toFile().delete() }
        check(left.isEmpty()) { "Everything was written, but these files moved aside could not be deleted: ${left.values.joinToString()}" }
    }
}
