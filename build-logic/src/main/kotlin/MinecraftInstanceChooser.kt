package eu.nordtal.s2.build

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
 * Asks for the folder of a Minecraft instance and remembers it for [InstallPack].
 *
 * A program of its own rather than a task action, because it opens a window and a Gradle daemon
 * has no business doing that. The first argument is the file to write; a second one is taken as
 * the answer instead of asking, which is also the way in on a machine with no screen.
 */
object MinecraftInstanceChooser {
    private const val TITLE = "Choose your Minecraft instance folder"

    @JvmStatic
    fun main(args: Array<String>) {
        val store = File(args[0])
        val given = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)
        val picked = given ?: ask(start(store)) ?: finish(0, "Nothing was chosen, so nothing changed.")
        val instance = normalise(picked)
        if (!instance.isDirectory) finish(1, "$instance is not a folder.")
        if (!looksLikeInstance(instance) && (given != null || !confirm(instance))) {
            finish(1, "$instance has neither options.txt nor a resourcepacks folder, so it is not a Minecraft instance.")
        }
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
            // Swing's chooser looks foreign on macOS; this property turns the native one into a folder picker.
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
                    "$instance\nhas no options.txt and no resourcepacks folder yet.\n" +
                        "Is this the folder of a Minecraft instance that was never started?",
                    TITLE,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                )
        }
        return answer == JOptionPane.YES_OPTION
    }

    /** The folder the game runs in, for the folders people commonly pick instead. */
    private fun normalise(picked: File): File {
        val folder = picked.absoluteFile
        if (folder.name == "resourcepacks") return folder.parentFile
        if (looksLikeInstance(folder)) return folder
        // Prism and MultiMC keep the game one level down, in minecraft/ or .minecraft/.
        return listOf("minecraft", ".minecraft").map(folder::resolve).firstOrNull(::looksLikeInstance) ?: folder
    }

    private fun looksLikeInstance(folder: File): Boolean =
        folder.resolve("options.txt").isFile || folder.resolve("resourcepacks").isDirectory

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
        // The dialogs leave AWT threads behind that would keep the JVM alive.
        exitProcess(status)
    }
}
