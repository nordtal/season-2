// Writes messages/<bundle>/schema.json from each message spec of the module, and documents/<namespace>/declarations.json
// from the module's document declarations after checking each document against its declaration.
// The classpath holds the resource source directories, because processed resources would form a cycle, and the
// runtime dependencies, whose jars carry the check bundle that words a refusal.

plugins {
    java
}

interface MessageSpecExtension {
    val specClasses: ListProperty<String>

    val documentClasses: ListProperty<String>
}

val messageSpec = extensions.create<MessageSpecExtension>("messageSpec")
val schemaDirectory = layout.buildDirectory.dir("generated/message-schema")
val main = the<SourceSetContainer>()["main"]

val messageSchema =
    tasks.register<JavaExec>("messageSchema") {
        description = "Writes messages/<bundle>/schema.json and declarations.json from the module's specs and documents."
        classpath =
            main.output.classesDirs + files(main.resources.srcDirs) + main.compileClasspath +
            configurations["runtimeClasspath"]
        mainClass.set("eu.nordtal.season.messages.document.Documents")
        inputs.property("specClasses", messageSpec.specClasses)
        inputs.property("documentClasses", messageSpec.documentClasses)
        inputs.files(main.output.classesDirs, main.resources.srcDirs)
        outputs.dir(schemaDirectory)
        // Local variables, not properties of the script, because a lambda that reaches the script cannot be cached.
        val specClasses = messageSpec.specClasses
        val documentClasses = messageSpec.documentClasses
        val output = schemaDirectory
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(output.get().asFile.path) + specClasses.get() + "--documents" + documentClasses.get()
            },
        )
        doFirst { output.get().asFile.deleteRecursively() }
    }

tasks.named<ProcessResources>("processResources") {
    from(messageSchema)
}
