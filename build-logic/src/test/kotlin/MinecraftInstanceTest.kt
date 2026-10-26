package eu.nordtal.season.build

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties

class MinecraftInstanceTest {
    @TempDir
    lateinit var root: File

    private val store by lazy { root.resolve("minecraft-instance.properties") }
    private val ghLog by lazy { root.resolve("gh.log") }

    /** An instance folder under [root] that the chooser saved, as a launcher of any kind lays it out. */
    private fun chosen(name: String = "instance"): File {
        val instance = root.resolve(name).apply { resolve("resourcepacks").mkdirs() }
        Properties().apply { setProperty("instance", instance.absolutePath) }.also { properties ->
            store.writer().use { properties.store(it, null) }
        }
        return instance
    }

    /** A stub of gh that logs its calls and downloads the asset [asset] into the folder after `--dir`. */
    private fun gh(asset: String = "nordtal-bridge-0.63.0.jar"): String {
        val gh = root.resolve("gh")
        gh.writeText(
            """
            #!/bin/sh
            echo "${'$'}*" >> '${ghLog.absolutePath}'
            while [ "${'$'}#" -gt 0 ]; do
              if [ "${'$'}1" = "--dir" ]; then printf 'jar' > "${'$'}2/$asset"; fi
              shift
            done
            """.trimIndent(),
        )
        gh.setExecutable(true)
        return gh.absolutePath
    }

    private fun settings(instance: File) =
        Properties().apply { instance.resolve("config/nordtal-bridge.properties").reader().use { load(it) } }

    @Test
    fun anInstanceWithoutAChoiceIsRefusedWithWhatToDo() {
        val failure = assertThrows<GradleException> { MinecraftInstance.chosen(store) }
        assertTrue(failure.message!!.startsWith("No Minecraft instance is chosen yet."), failure.message)
    }

    @Test
    fun theFolderTheChooserSavedIsTheInstance() {
        val instance = chosen()
        assertEquals(instance.absoluteFile, MinecraftInstance.chosen(store))
    }

    @Test
    fun fabricShowsInItsOwnFolderOrInTheLauncherFilesBesideTheInstance() {
        val bare = chosen("bare")
        assertFalse(MinecraftInstance.fabric(bare))

        val started = root.resolve("started").apply { resolve(".fabric").mkdirs() }
        assertTrue(MinecraftInstance.fabric(started))

        val prism = root.resolve("prism").apply { resolve("minecraft").mkdirs() }
        prism.resolve("mmc-pack.json").writeText("""{ "components": [ { "uid": "net.fabricmc.fabric-loader" } ] }""")
        assertTrue(MinecraftInstance.fabric(prism.resolve("minecraft")))

        val vanilla = root.resolve("vanilla").apply { resolve("versions/fabric-loader-0.20.1-26.2").mkdirs() }
        assertTrue(MinecraftInstance.fabric(vanilla))

        val forge = root.resolve("forge").apply { resolve("versions/26.2-forge-60.0.0").mkdirs() }
        assertFalse(MinecraftInstance.fabric(forge))
    }

    @Test
    fun theBridgeModReplacesAnOlderOneInTheModsFolderAndGetsItsSettings() {
        val instance = chosen().apply { resolve(".fabric").mkdirs() }
        val mods = instance.resolve("mods").apply { mkdirs() }
        mods.resolve("nordtal-bridge-0.62.0.jar").writeText("old")
        mods.resolve("sodium.jar").writeText("other")

        val jar = MinecraftInstance.installBridge(store, gh(), root)

        assertEquals(mods.resolve("nordtal-bridge-0.63.0.jar"), jar)
        assertEquals(listOf("nordtal-bridge-0.63.0.jar", "sodium.jar"), mods.list()!!.sorted())
        assertTrue(ghLog.readText().startsWith("release download --pattern nordtal-bridge-*.jar --dir "), ghLog.readText())
        val settings = settings(instance)
        assertEquals("29415", settings.getProperty("port"))
        assertTrue(settings.getProperty("token").matches(Regex("[0-9a-f]{32}")), settings.getProperty("token"))
    }

    @Test
    fun aSecondInstallKeepsTheTokenTheClientAlreadyRead() {
        val instance = chosen().apply { resolve(".fabric").mkdirs() }
        MinecraftInstance.installBridge(store, gh(), root)
        val token = settings(instance).getProperty("token")

        MinecraftInstance.installBridge(store, gh("nordtal-bridge-0.64.0.jar"), root)

        assertEquals(token, settings(instance).getProperty("token"))
        assertEquals(listOf("nordtal-bridge-0.64.0.jar"), instance.resolve("mods").list()!!.toList())
    }

    @Test
    fun anInstanceWithoutFabricGetsNoBridgeMod() {
        val instance = chosen()
        val failure = assertThrows<GradleException> { MinecraftInstance.installBridge(store, gh(), root) }
        assertTrue(failure.message!!.contains("Fabric"), failure.message)
        assertFalse(instance.resolve("mods").exists())
        assertFalse(ghLog.exists())
    }

    @Test
    fun theDescriptionNamesTheInstanceItsFabricItsBridgeModAndTheBridgeSettings() {
        assertEquals(mapOf("instance" to null), MinecraftInstance.describe(store))

        val instance = chosen()
        assertEquals(
            mapOf("instance" to instance.absolutePath, "fabric" to false, "bridge" to null, "port" to null, "token" to null),
            MinecraftInstance.describe(store),
        )

        instance.resolve(".fabric").mkdirs()
        MinecraftInstance.installBridge(store, gh(), root)
        val described = MinecraftInstance.describe(store)
        assertEquals(true, described["fabric"])
        assertEquals("nordtal-bridge-0.63.0.jar", described["bridge"])
        assertEquals(29415, described["port"])
        assertEquals(settings(instance).getProperty("token"), described["token"])
    }

    @Test
    fun aFolderThatIsGoneIsDescribedAsNoInstance() {
        chosen().deleteRecursively()
        assertNull(MinecraftInstance.describe(store)["instance"])
    }
}
