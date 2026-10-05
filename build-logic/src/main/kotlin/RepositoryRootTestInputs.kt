package eu.nordtal.season.build

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import javax.inject.Inject

/**
 * Files at the repository root that a module's tests read, declared so editing one reruns the tests.
 */
abstract class RepositoryRootTestInputs
    @Inject
    constructor(
        private val root: Directory,
    ) {
        abstract val files: ConfigurableFileCollection

        /** Declares [names], resolved against the repository root, as inputs of the test task. */
        fun reads(vararg names: String) {
            names.forEach { files.from(root.file(it)) }
        }

        /** Declares whole directories, resolved against the repository root, as inputs of the test task. */
        fun readsTree(vararg names: String) {
            names.forEach { files.from(root.dir(it)) }
        }
    }
