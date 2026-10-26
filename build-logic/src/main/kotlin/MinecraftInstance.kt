package eu.nordtal.season.build

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Properties

/**
 * The Minecraft instance that [MinecraftInstanceChooser] saved in its store file: whether Fabric runs there, its bridge mod
 * of `:bridge-mod` and the port and token that the bridge mod reads from `config/nordtal-bridge.properties`.
 */
object MinecraftInstance {
    /** The port that a new settings file of the bridge mod names. */
    const val BRIDGE_PORT = 29415

    private const val BRIDGE_PREFIX = "nordtal-bridge-"
    private const val SETTINGS = "config/nordtal-bridge.properties"

    /** Returns the chosen instance, or throws when none is chosen or its folder is gone. */
    fun chosen(store: File): File {
        val chosen =
            saved(store)
                ?: throw GradleException("No Minecraft instance is chosen yet. Run 'pack: 1. choose Minecraft instance' first.")
        return File(chosen).absoluteFile.takeIf { it.isDirectory }
            ?: throw GradleException("$chosen does not exist any more. Run 'pack: 1. choose Minecraft instance' again.")
    }

    private fun saved(store: File): String? =
        store.takeIf { it.isFile }?.let { file -> Properties().apply { file.reader().use { load(it) } }.getProperty("instance") }

    /**
     * Whether Fabric Loader runs in [instance]: its `.fabric` folder, which the loader writes on the first start, a Prism or
     * MultiMC `mmc-pack.json` beside the game folder that names the loader, or a Fabric version of the vanilla launcher.
     */
    fun fabric(instance: File): Boolean =
        instance.resolve(".fabric").isDirectory ||
            instance.parentFile
                ?.resolve(
                    "mmc-pack.json",
                )?.takeIf { it.isFile }
                ?.readText()
                ?.contains("net.fabricmc.fabric-loader") == true ||
            instance
                .resolve("versions")
                .listFiles()
                .orEmpty()
                .any { it.isDirectory && it.name.startsWith("fabric-loader-") }

    /**
     * The folder that Fabric reads mods from in [instance]. Every launcher loads the jars in `mods/`. The NoRisk launcher
     * points Fabric at a folder of its own per version and adds the loose jars of `mods/` to it.
     */
    fun mods(instance: File): File = instance.resolve("mods")

    /**
     * Downloads the bridge mod of the newest release with [gh] in [repository] into the chosen instance, removes every
     * other version of it there and writes the settings file once. Returns the installed jar.
     */
    fun installBridge(
        store: File,
        gh: String,
        repository: File,
    ): File {
        val instance = chosen(store)
        if (!fabric(instance)) {
            throw GradleException(
                "$instance has no Fabric. Start the instance once with Fabric Loader, or copy the commands of each preview.",
            )
        }
        val download = Files.createTempDirectory("bridge").toFile()
        try {
            run(gh, repository, listOf("release", "download", "--pattern", "$BRIDGE_PREFIX*.jar", "--dir", download.absolutePath))
            val jar =
                download.listFiles().orEmpty().singleOrNull { bridge(it) }
                    ?: throw GradleException("The newest release has no single $BRIDGE_PREFIX<version>.jar.")
            val mods = mods(instance).apply { mkdirs() }
            mods
                .listFiles()
                .orEmpty()
                .filter { bridge(it) && it.name != jar.name }
                .forEach { it.delete() }
            val target = jar.copyTo(mods.resolve(jar.name), overwrite = true)
            settings(instance) ?: writeSettings(instance)
            return target
        } finally {
            download.deleteRecursively()
        }
    }

    private fun bridge(file: File) = file.isFile && file.name.startsWith(BRIDGE_PREFIX) && file.name.endsWith(".jar")

    /** The port and token of the settings file in [instance], or null while it is missing or names no valid pair. */
    private fun settings(instance: File): Pair<Int, String>? {
        val file = instance.resolve(SETTINGS).takeIf { it.isFile } ?: return null
        val properties = Properties().apply { file.reader().use { load(it) } }
        val port =
            properties
                .getProperty("port")
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in 1024..65535 }
        val token = properties.getProperty("token")?.trim()?.takeIf { it.length >= 16 }
        return if (port != null && token != null) port to token else null
    }

    private fun writeSettings(instance: File) {
        val token = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        instance.resolve(SETTINGS).apply { parentFile.mkdirs() }.writeText("port=$BRIDGE_PORT\ntoken=$token\n")
    }

    /**
     * The chosen instance as `instance`, `fabric`, `bridge` (the name of the installed jar), `port` and `token`, or only
     * `instance` as null while none is chosen or its folder is gone.
     */
    fun describe(store: File): Map<String, Any?> {
        val instance = saved(store)?.let(::File)?.absoluteFile?.takeIf { it.isDirectory } ?: return mapOf("instance" to null)
        val settings = settings(instance)
        return linkedMapOf(
            "instance" to instance.absolutePath,
            "fabric" to fabric(instance),
            "bridge" to
                mods(instance)
                    .listFiles()
                    .orEmpty()
                    .filter { bridge(it) }
                    .maxOfOrNull { it.name },
            "port" to settings?.first,
            "token" to settings?.second,
        )
    }
}
