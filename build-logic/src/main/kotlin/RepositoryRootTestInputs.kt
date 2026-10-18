package eu.nordtal.season.build

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import javax.inject.Inject

/** Declares the files at the repository root that the tests of a module read, so that an edit of a file reruns the tests. */
abstract class RepositoryRootTestInputs
    @Inject
    constructor(
        private val root: Directory,
    ) {
        abstract val files: ConfigurableFileCollection

        fun reads(vararg names: String) {
            names.forEach { files.from(root.file(it)) }
        }

        fun readsTree(vararg names: String) {
            names.forEach { files.from(root.dir(it)) }
        }
    }
