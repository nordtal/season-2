// Writes messages/<bundle>/schema.json into the jar from the module's message spec: the names,
// placeholders and sections steward-worker shows next to the texts it reads from the same jar.
// The classpath is the resource source directories, not the processed resources, because
// processResources is what picks the schema up, and depending on its output would be a cycle.

plugins {
    java
}

interface MessageSpecExtension {
    /** The fully qualified name of the module's @MessageSpec interface. */
    val specClass: Property<String>
}

val messageSpec = extensions.create<MessageSpecExtension>("messageSpec")
val schemaDirectory = layout.buildDirectory.dir("generated/message-schema")
val main = the<SourceSetContainer>()["main"]

val messageSchema =
    tasks.register<JavaExec>("messageSchema") {
        description = "Writes messages/<bundle>/schema.json from the module's message spec."
        classpath = main.output.classesDirs + files(main.resources.srcDirs) + main.compileClasspath
        mainClass.set("eu.nordtal.s2.common.message.spec.MessageSchema")
        inputs.property("specClass", messageSpec.specClass)
        inputs.files(main.output.classesDirs, main.resources.srcDirs)
        outputs.dir(schemaDirectory)
        // Locals, not the script's own properties: a lambda reaching the script cannot be cached.
        val specClass = messageSpec.specClass
        val output = schemaDirectory
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(specClass.get(), output.get().asFile.path)
            },
        )
        doFirst { output.get().asFile.deleteRecursively() }
    }

tasks.named<ProcessResources>("processResources") {
    from(messageSchema)
}
