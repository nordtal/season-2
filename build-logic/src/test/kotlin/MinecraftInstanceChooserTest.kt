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
        val prism =
            folder("blocked").apply {
                resolve("mmc-pack.json").writeText("{}")
                resolve("minecraft").writeText("")
            }
        val store = root.resolve("minecraft-instance.properties")
        assertEquals(
            "${prism.resolve("minecraft")} cannot be created, so nothing changed.",
            MinecraftInstanceChooser.save(store, prism, prism)?.trim(),
        )
        assertFalse(store.exists())
    }

    @Test
    fun aChosenInstanceIsSaved() {
        val started = folder("started", "resourcepacks")
        val store = root.resolve("minecraft-instance.properties")
        assertNull(MinecraftInstanceChooser.save(store, started.resolve("resourcepacks"), started.resolve("resourcepacks")))
        assertEquals(
            started.absolutePath,
            java.util.Properties().apply { store.reader().use { load(it) } }.getProperty("instance"),
        )
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
