// Writes messages/<bundle>/schema.json from each of the module's message specs, for steward to show.
// The classpath is the resource source directories, since processed resources would be a cycle, and the
// dependencies at runtime, whose jars carry the check bundle a refusal is told in.

plugins {
    java
}

interface MessageSpecExtension {
    /** The fully qualified names of the module's @MessageSpec interfaces, one per bundle it ships. */
    val specClasses: ListProperty<String>
}

val messageSpec = extensions.create<MessageSpecExtension>("messageSpec")
val schemaDirectory = layout.buildDirectory.dir("generated/message-schema")
val main = the<SourceSetContainer>()["main"]

val messageSchema =
    tasks.register<JavaExec>("messageSchema") {
        description = "Writes messages/<bundle>/schema.json from each of the module's message specs."
        classpath =
            main.output.classesDirs + files(main.resources.srcDirs) + main.compileClasspath +
            configurations["runtimeClasspath"]
        mainClass.set("eu.nordtal.season.messages.spec.MessageSchema")
        inputs.property("specClasses", messageSpec.specClasses)
        inputs.files(main.output.classesDirs, main.resources.srcDirs)
        outputs.dir(schemaDirectory)
        // Locals, not the script's own properties: a lambda reaching the script cannot be cached.
        val specClasses = messageSpec.specClasses
        val output = schemaDirectory
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(output.get().asFile.path) + specClasses.get()
            },
        )
        doFirst { output.get().asFile.deleteRecursively() }
    }

tasks.named<ProcessResources>("processResources") {
    from(messageSchema)
}
