package eu.nordtal.season.build

/**
 * Tells whether a Minecraft client seems to run on this machine.
 *
 * The answer is a guess from the process table. On Windows, which hides arguments, it comes from the path of the Java executable.
 */
object MinecraftProcesses {
    private val JAVA = setOf("java", "javaw", "java.exe", "javaw.exe")

    /** The main classes of a client: vanilla, and Knot of Fabric or Quilt, which a launcher can start with a system Java. */
    private val CLIENTS = listOf("net.minecraft.client.main.Main", ".knot.KnotClient")
    private val LAUNCHERS = listOf("minecraft", "modrinth", "norisk", "prismlauncher", "multimc", "curseforge")

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
