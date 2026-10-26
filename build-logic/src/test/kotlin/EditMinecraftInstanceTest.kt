package eu.nordtal.season.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EditMinecraftInstanceTest {
    @TempDir
    lateinit var root: File

    @Test
    fun aKindOtherThanDescribeOrBridgeIsRefused() {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("edit", EditMinecraftInstance::class.java).get()
        task.kind.set("install")
        task.instanceFile.set(root.resolve("minecraft-instance.properties"))
        task.repository.set(root)
        val failure = assertThrows<GradleException> { task.run() }
        assertEquals("kind is describe or bridge, not install.", failure.message)
    }
}
