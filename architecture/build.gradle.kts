plugins {
    id("nordtal.java-base")
}

val checkedModules =
    listOf(
        ":common",
        ":commands",
        ":paper-common",
        ":limbo",
        ":hunger-games",
        ":smp",
        ":proxy",
        ":discord-bot",
        ":steward-worker",
        ":steward-ui",
        ":steward-deployer",
    )

dependencies {
    testImplementation(libs.archunit.junit6)
    checkedModules.forEach { testRuntimeOnly(project(it)) { isTransitive = false } }
}

// Compiled classes only: a module's jar would pull in its resources, and steward-ui's are the frontend build.
configurations.testRuntimeClasspath {
    attributes {
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
    }
}
