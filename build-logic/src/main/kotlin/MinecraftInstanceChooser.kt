package eu.nordtal.season.build

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.Properties
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Asks for the folder of a Minecraft instance and saves it for [InstallPack].
 *
 * It is a separate program, so the Gradle daemon opens no window. The first argument is the file to write.
 * A second argument is the answer, and the program does not ask then.
 */
object MinecraftInstanceChooser {
    private const val TITLE = "Choose your Minecraft instance folder"

    /** The folders that Prism and MultiMC run the game in, below the folder of `mmc-pack.json`. */
    private val GAME_FOLDERS = listOf("minecraft", ".minecraft")

    @JvmStatic
    fun main(args: Array<String>) {
        val store = File(args[0])
        val given = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)
        val picked = (given ?: ask(start(store)) ?: finish(0, "Nothing was chosen, so nothing changed.")).absoluteFile
        if (!picked.isDirectory) finish(1, "$picked is not a folder.")
        val instance =
            instance(picked)
                ?: picked.takeIf { given == null && confirm(it) }
                ?: finish(1, "$picked holds no file of Minecraft or of a launcher, so it is not a Minecraft instance.")
        if (!instance.isDirectory && !instance.mkdirs()) finish(1, "$instance cannot be created, so nothing changed.")
        Properties().apply { setProperty("instance", instance.absolutePath) }.also { properties ->
            store.writer().use { properties.store(it, "The Minecraft instance the resource pack is installed into") }
        }
        finish(0, "Minecraft instance: $instance\nNext, run 'pack: 2. install into Minecraft'.")
    }

    private fun ask(start: File): File? {
        if (GraphicsEnvironment.isHeadless()) {
            finish(1, "There is no screen to ask on. Pass the folder instead: -PminecraftInstance=<folder>")
        }
        if (System.getProperty("os.name").startsWith("Mac")) {
            // Makes the native chooser of macOS pick folders.
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(null as Frame?, TITLE, FileDialog.LOAD).apply { directory = start.path }
            dialog.isVisible = true
            return dialog.file?.let { File(dialog.directory, it) }
        }
        var chosen: File? = null
        SwingUtilities.invokeAndWait {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            val chooser =
                JFileChooser(start).apply {
                    dialogTitle = TITLE
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isFileHidingEnabled = false
                }
            if (chooser.showDialog(null, "Use this folder") == JFileChooser.APPROVE_OPTION) chosen = chooser.selectedFile
        }
        return chosen
    }

    private fun confirm(instance: File): Boolean {
        var answer = JOptionPane.NO_OPTION
        SwingUtilities.invokeAndWait {
            answer =
                JOptionPane.showConfirmDialog(
                    null,
                    "$instance\nholds no file of Minecraft or of a launcher yet.\n" +
                        "Is this the folder of a Minecraft instance that was never started?",
                    TITLE,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                )
        }
        return answer == JOptionPane.YES_OPTION
    }

    /** Returns the folder that the game runs in for [picked], or null when [picked] is no Minecraft instance. */
    internal fun instance(picked: File): File? {
        val folder = picked.absoluteFile
        if (folder.name == "resourcepacks") return folder.parentFile
        if (folder.resolve("mmc-pack.json").isFile) {
            // Prism and MultiMC run the game in minecraft/, or in .minecraft/ while only that one exists.
            val dotted = folder.resolve(".minecraft")
            return if (dotted.isDirectory && !folder.resolve("minecraft").exists()) dotted else folder.resolve("minecraft")
        }
        if (looksLikeInstance(folder)) return folder
        return GAME_FOLDERS.map(folder::resolve).firstOrNull(::looksLikeInstance)
    }

    /**
     * Whether a file of the game or of a launcher marks [folder], which then may never have run. The `mmc-pack.json` of
     * Prism and MultiMC marks only the `minecraft/` or `.minecraft/` beside it.
     */
    private fun looksLikeInstance(folder: File): Boolean =
        folder.resolve("options.txt").isFile ||
            listOf("resourcepacks", "versions", ".fabric").any { folder.resolve(it).isDirectory } ||
            (folder.name in GAME_FOLDERS && folder.parentFile?.resolve("mmc-pack.json")?.isFile == true)

    private fun start(store: File): File {
        val previous = runCatching { Properties().apply { store.reader().use { load(it) } }.getProperty("instance") }.getOrNull()
        val home = File(System.getProperty("user.home"))
        val os = System.getProperty("os.name")
        val vanilla =
            when {
                os.startsWith("Windows") -> File(System.getenv("APPDATA") ?: home.path, ".minecraft")
                os.startsWith("Mac") -> home.resolve("Library/Application Support/minecraft")
                else -> home.resolve(".minecraft")
            }
        return listOfNotNull(previous?.let(::File), vanilla).firstOrNull { it.isDirectory } ?: home
    }

    private fun finish(
        status: Int,
        message: String,
    ): Nothing {
        (if (status == 0) System.out else System.err).println(message)
        // The dialogs leave AWT threads that keep the JVM alive.
        exitProcess(status)
    }
}
