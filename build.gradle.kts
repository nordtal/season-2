// season-2 produces no artifact of its own. Every deployable is a module; a release
// attaches each module's own build output. Keep this file free of `subprojects {}` —
// shared configuration lives in build-logic's convention plugins.

tasks.register("releaseArtifacts") {
    group = "distribution"
    description = "Builds every artifact that goes onto a GitHub release."
    dependsOn(
        ":resource-pack-coercion:shadowJar",
        ":hunger-games:shadowJar",
        ":smp:shadowJar",
        ":network-control:shadowJar",
        ":access-bot:shadowJar",
        ":resource-pack:packZip",
    )
}
