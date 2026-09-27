package eu.nordtal.s2.build

/**
 * Whether a Minecraft client appears to be running on this machine.
 *
 * A guess from the process table, because no launcher leaves a lock file behind. Windows does not
 * reveal another process's arguments, so there it goes by the path of the Java executable, which
 * the vanilla launcher, Modrinth and NoRisk all keep under their own folder.
 */
object MinecraftProcesses {
    private val JAVA = setOf("java", "javaw", "java.exe", "javaw.exe")
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
            "net.minecraft.client.main.Main" in line || LAUNCHERS.any { it in command.lowercase() }
        }
    }
}
