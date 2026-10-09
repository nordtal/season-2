// Puts nordtal-plugin.json into the jar: what steward-agent reads to tell Steward whose groups of settings these are,
// which editor draws each group, and whether the message bundles are worth editing.

plugins {
    java
}

interface PluginDescriptorExtension {
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
        description = "Writes nordtal-plugin.json for steward-agent."
        // The module's name is the service its settings are published under.
        id.set(project.name)
        editors.set(descriptor.editors)
        followsMessages.set(descriptor.followsMessages)
        // Every release is built by GitHub Actions, which sets this; any other build is a local one.
        local.set(providers.environmentVariable("GITHUB_ACTIONS").map { it != "true" }.orElse(true))
        target.set(layout.buildDirectory.dir("generated/plugin-descriptor"))
    }

tasks.named<ProcessResources>("processResources") {
    from(pluginDescriptor)
}
