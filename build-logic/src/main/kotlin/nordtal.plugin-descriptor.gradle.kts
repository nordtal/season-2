// Puts nordtal-plugin.json and the Nordtal mark into the jar: what steward-agent reads to tell Steward whose groups
// of settings these are, how the plugin is shown, and which editor draws each group.

plugins {
    java
}

interface PluginDescriptorExtension {
    /** The name an admin reads in the settings sidebar, such as `SMP`. */
    val displayName: Property<String>

    /** The custom editor that draws a group instead of the form built from its schema, by group name. */
    val editors: MapProperty<String, String>

    /** Whether the process follows the message overrides, which is what makes its bundles worth editing. */
    val followsMessages: Property<Boolean>
}

val descriptor = extensions.create<PluginDescriptorExtension>("pluginDescriptor")
descriptor.editors.convention(emptyMap())
descriptor.followsMessages.convention(false)

val pluginDescriptor =
    tasks.register<eu.nordtal.season.build.WritePluginDescriptor>("pluginDescriptor") {
        description = "Writes nordtal-plugin.json and nordtal/logo.png for steward-agent."
        // The module's name is the service its settings are published under.
        id.set(project.name)
        displayName.set(descriptor.displayName)
        editors.set(descriptor.editors)
        followsMessages.set(descriptor.followsMessages)
        // Every release is built by GitHub Actions, which sets this; any other build is a local one.
        local.set(providers.environmentVariable("GITHUB_ACTIONS").map { it != "true" }.orElse(true))
        logo.set(rootProject.layout.projectDirectory.file("resource-pack/src/pack.png"))
        target.set(layout.buildDirectory.dir("generated/plugin-descriptor"))
    }

tasks.named<ProcessResources>("processResources") {
    from(pluginDescriptor)
}
