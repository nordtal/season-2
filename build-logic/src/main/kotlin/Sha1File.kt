package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

/** Writes the SHA-1 of [source] to [target]. It is a task type, because a `doLast {}` in a script breaks the configuration cache. */
abstract class Sha1File : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun write() {
        target.get().asFile.writeText(sha1(source.get().asFile.readBytes()) + "\n")
    }
}

/** Returns the SHA-1 of [bytes] in lower-case hex, as Mojang's manifests and the pack's `.sha1` write it. */
fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
