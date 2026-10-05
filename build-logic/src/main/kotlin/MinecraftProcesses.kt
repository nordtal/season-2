package eu.nordtal.season.build

/**
 * Whether a Minecraft client appears to be running on this machine.
 *
 * A guess from the process table; on Windows, which hides arguments, from the Java executable's path.
 */
object MinecraftProcesses {
    private val JAVA = setOf("java", "javaw", "java.exe", "javaw.exe")

    /** Main classes of a client: vanilla, and Fabric's or Quilt's Knot, which a launcher may start with a system Java. */
    private val CLIENTS = listOf("net.minecraft.client.main.Main", ".knot.KnotClient")
    private val LAUNCHERS = listOf("minecraft", "modrinth", "norisk", "prismlauncher", "multimc", "curseforge")

    /** @return true when some Java process looks like a Minecraft client */
    fun running(): Boolean {
        val own = ProcessHandle.current().pid()
        return ProcessHandle.allProcesses().anyMatch { process ->
            if (process.pid() == own) return@anyMatch false
            val info = process.info()
            val command = info.command().orElse("")
            if (command.substringAfterLast('/').substringAfterLast('\\').lowercase() !in JAVA) return@anyMatch false
            val line = info.commandLine().orElse("")
            CLIENTS.any { it in line } || LAUNCHERS.any { it in command.lowercase() }
        }
    }
}
