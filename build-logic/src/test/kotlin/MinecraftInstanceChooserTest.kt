package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MinecraftInstanceChooserTest {
    @TempDir
    lateinit var root: File

    private fun folder(
        name: String,
        vararg folders: String,
    ) = root.resolve(name).apply {
        mkdirs()
        folders.forEach { resolve(it).mkdirs() }
    }

    @Test
    fun aStartedInstanceOrItsResourcePacksFolderIsTheInstance() {
        val started = folder("started", "resourcepacks")
        assertEquals(started, MinecraftInstanceChooser.instance(started))
        assertEquals(started, MinecraftInstanceChooser.instance(started.resolve("resourcepacks")))
    }

    @Test
    fun aNeverStartedPrismInstanceIsTheGameFolderPrismWillUse() {
        val fresh = folder("fresh").apply { resolve("mmc-pack.json").writeText("{}") }
        assertEquals(fresh.resolve("minecraft"), MinecraftInstanceChooser.instance(fresh))
        assertEquals(fresh.resolve("minecraft"), MinecraftInstanceChooser.instance(fresh.resolve("minecraft").apply { mkdirs() }))

        val dotted = folder("dotted", ".minecraft").apply { resolve("mmc-pack.json").writeText("{}") }
        assertEquals(dotted.resolve(".minecraft"), MinecraftInstanceChooser.instance(dotted))
        assertEquals(dotted.resolve(".minecraft"), MinecraftInstanceChooser.instance(dotted.resolve(".minecraft")))
    }

    @Test
    fun anotherFolderOfAPrismInstanceIsNoInstance() {
        val prism = folder("prism", "patches").apply { resolve("mmc-pack.json").writeText("{}") }
        assertNull(MinecraftInstanceChooser.instance(prism.resolve("patches")))
    }

    @Test
    fun aGameFolderThatCannotBeCreatedFailsTheChoiceAndSavesNothing() {
        val prism = folder("locked").apply { resolve("mmc-pack.json").writeText("{}") }
        val store = root.resolve("minecraft-instance.properties")
        prism.setWritable(false)
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process =
                ProcessBuilder(
                    java,
                    "-Djava.awt.headless=true",
                    "-cp",
                    System.getProperty("java.class.path"),
                    MinecraftInstanceChooser::class.java.name,
                    store.path,
                    prism.path,
                ).redirectErrorStream(true).start()
            val printed = process.inputStream.bufferedReader().readText()
            assertEquals(1, process.waitFor(), printed)
            assertEquals("${prism.resolve("minecraft")} cannot be created, so nothing changed.\n", printed)
            assertFalse(store.exists())
        } finally {
            prism.setWritable(true)
        }
    }

    @Test
    fun aNeverStartedFolderOfTheVanillaLauncherOrOfFabricIsAnInstance() {
        val vanilla = folder("vanilla", "versions")
        assertEquals(vanilla, MinecraftInstanceChooser.instance(vanilla))
        val fabric = folder("fabric", ".fabric")
        assertEquals(fabric, MinecraftInstanceChooser.instance(fabric))
    }

    @Test
    fun aFolderThatNoLauncherMarksIsNoInstance() {
        assertNull(MinecraftInstanceChooser.instance(folder("empty")))
    }
}
