import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("nordtal.java-base")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    // PacketEvents.
    maven("https://repo.codemc.io/repository/maven-releases/")
    // PlaceholderAPI.
    maven("https://repo.extendedclip.com/releases/")
    // TAB's API is published there only.
    maven("https://jitpack.io")
}

// The shipped jars and what their host provides, which is everything a jar may name without carrying it.
val paperHost = configurations.dependencyScope("paperHost")
val velocityHost = configurations.dependencyScope("velocityHost")

fun hostClasspath(
    name: String,
    host: NamedDomainObjectProvider<DependencyScopeConfiguration>,
) = configurations.resolvable(name) {
    extendsFrom(host.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

val paperClasspath = hostClasspath("paperClasspath", paperHost)
val velocityClasspath = hostClasspath("velocityClasspath", velocityHost)

dependencies {
    // What the Paper plugins compile against and the server or a sibling plugin provides at runtime.
    paperHost(libs.paper.api)
    paperHost(libs.packetevents)
    paperHost(libs.placeholderapi)
    paperHost(libs.tab.api)
    velocityHost(libs.velocity.api)

    testImplementation(testFixtures(project(":database")))
}

val hosts = mapOf("paper" to paperClasspath, "velocity" to velocityClasspath)
val shipped =
    mapOf(
        "smp" to "paper",
        "limbo" to "paper",
        "hunger-games" to "paper",
        "proxy" to "velocity",
        "discord-bot" to "none",
        "steward" to "none",
        "steward-agent" to "none",
    )

tasks.named<Test>("test") {
    val hostOf = shipped
    val classpathOf = hosts.mapValues { it.value.map { files -> files.incoming.files } }
    val jars = hostOf.keys.associateWith { rootProject.layout.projectDirectory.file("$it/build/libs/$it-${project.version}.jar") }
    hostOf.keys.forEach { dependsOn(":$it:shadowJar") }
    inputs.files(jars.values).withPropertyName("shippedJars")
    inputs.files(classpathOf.values).withPropertyName("hostClasspaths")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            hostOf.flatMap { (name, host) ->
                val classpath = classpathOf[host]?.get()?.files?.joinToString(File.pathSeparator) { it.absolutePath }.orEmpty()
                listOf("-Dshipped.$name.jar=${jars.getValue(name).asFile.absolutePath}", "-Dshipped.$name.host=$classpath")
            }
        },
    )
}
