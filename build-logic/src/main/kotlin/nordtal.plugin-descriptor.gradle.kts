// Puts nordtal-plugin.json into the jar. steward-agent reads it to tell Steward which service owns the groups of settings,
// which editor draws each group and whether the message bundles can be edited.

plugins {
    java
}

interface PluginDescriptorExtension {
    /** The custom editor that draws a group instead of the form from its schema, by group name. */
    val editors: MapProperty<String, String>

    /** Whether the process follows the message overrides. Only then can its bundles be edited. */
    val followsMessages: Property<Boolean>
}

val descriptor = extensions.create<PluginDescriptorExtension>("pluginDescriptor")
descriptor.editors.convention(emptyMap())
descriptor.followsMessages.convention(false)

val pluginDescriptor =
    tasks.register<eu.nordtal.season.build.WritePluginDescriptor>("pluginDescriptor") {
        description = "Writes nordtal-plugin.json for steward-agent."
        // The name of the module is the service that publishes its settings.
        id.set(project.name)
        editors.set(descriptor.editors)
        followsMessages.set(descriptor.followsMessages)
        // GitHub Actions builds every release and sets this variable. Any other build is a local build.
        local.set(providers.environmentVariable("GITHUB_ACTIONS").map { it != "true" }.orElse(true))
        target.set(layout.buildDirectory.dir("generated/plugin-descriptor"))
    }

tasks.named<ProcessResources>("processResources") {
    from(pluginDescriptor)
}
