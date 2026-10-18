// Writes messages/<bundle>/schema.json from each message spec of the module, and layouts.json beside it from
// the Discord layouts of the module, for steward to show.
// The classpath holds the resource source directories, because processed resources would form a cycle, and the
// runtime dependencies, whose jars carry the check bundle that words a refusal.

plugins {
    java
}

interface MessageSpecExtension {
    val specClasses: ListProperty<String>

    val layoutClasses: ListProperty<String>
}

val messageSpec = extensions.create<MessageSpecExtension>("messageSpec")
val schemaDirectory = layout.buildDirectory.dir("generated/message-schema")
val main = the<SourceSetContainer>()["main"]

val messageSchema =
    tasks.register<JavaExec>("messageSchema") {
        description = "Writes messages/<bundle>/schema.json and layouts.json from the module's specs and layouts."
        classpath =
            main.output.classesDirs + files(main.resources.srcDirs) + main.compileClasspath +
            configurations["runtimeClasspath"]
        mainClass.set("eu.nordtal.season.messages.spec.MessageSchema")
        inputs.property("specClasses", messageSpec.specClasses)
        inputs.property("layoutClasses", messageSpec.layoutClasses)
        inputs.files(main.output.classesDirs, main.resources.srcDirs)
        outputs.dir(schemaDirectory)
        // Local variables, not properties of the script, because a lambda that reaches the script cannot be cached.
        val specClasses = messageSpec.specClasses
        val layoutClasses = messageSpec.layoutClasses
        val output = schemaDirectory
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(output.get().asFile.path) + specClasses.get() + "--layouts" + layoutClasses.get()
            },
        )
        doFirst { output.get().asFile.deleteRecursively() }
    }

tasks.named<ProcessResources>("processResources") {
    from(messageSchema)
}
