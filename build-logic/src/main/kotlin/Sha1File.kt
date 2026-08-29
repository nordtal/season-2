package eu.nordtal.s2.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

/**
 * Writes the SHA-1 of [source] to [target].
 *
 * A real task type rather than an ad-hoc `doLast {}`: an action defined inside a build script
 * captures the script object, which the configuration cache cannot serialize.
 */
abstract class Sha1File : DefaultTask() {

    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun write() {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(source.get().asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        target.get().asFile.writeText(hash + "\n")
    }
}
