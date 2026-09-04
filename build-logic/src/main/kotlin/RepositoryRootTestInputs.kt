package eu.nordtal.s2.build

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import javax.inject.Inject

/**
 * Files at the repository root that a module's tests read straight off the filesystem instead of
 * from their own resources.
 *
 * Two tests do that, and both on purpose: `TopologyTest` reads `compose.yml` and `ConfigsTest`
 * reads `.env.example`, because a fixture copy of either would be a second source of truth that
 * nothing compares. The cost is that Gradle cannot see the dependency - the file is outside every
 * source set - so editing it leaves the test task UP-TO-DATE and the check that exists to catch
 * the drift is the one thing that does not run. Declaring it here is what closes that.
 */
abstract class RepositoryRootTestInputs @Inject constructor(private val root: Directory) {

    abstract val files: ConfigurableFileCollection

    /** Declares [names], resolved against the repository root, as inputs of the test task. */
    fun reads(vararg names: String) {
        names.forEach { files.from(root.file(it)) }
    }

    /**
     * Declares whole directories, resolved against the repository root, as inputs of the test task.
     *
     * `ResourcePackTest` walks `resource-pack/src/assets` rather than naming eighty-odd PNG files,
     * so what it depends on is the tree and not a list somebody would have to keep in step with it.
     */
    fun readsTree(vararg names: String) {
        names.forEach { files.from(root.dir(it)) }
    }
}
