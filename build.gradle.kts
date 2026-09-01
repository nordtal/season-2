// season-2 produces no artifact of its own. Every deployable is a module; a release
// attaches each module's own build output. Keep this file free of `subprojects {}` —
// shared configuration lives in build-logic's convention plugins.

tasks.register("releaseArtifacts") {
    group = "distribution"
    description = "Builds every artifact that goes onto a GitHub release."
    dependsOn(
        ":limbo:shadowJar",
        ":hunger-games:shadowJar",
        ":smp:shadowJar",
        ":network-control:shadowJar",
        ":discord-bot:shadowJar",
        // The updater is on the release like every other module, and for the same reason it
        // exists: its own version has to be movable by the mechanism it implements.
        ":updater:shadowJar",
        ":resource-pack:packZip",
    )
}
