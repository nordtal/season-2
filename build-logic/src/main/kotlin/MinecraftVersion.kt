package eu.nordtal.season.build

/** The Minecraft version of the build, read from the Paper version of the catalog as `PlatformTest` holds it to `Platform.MINECRAFT`. */
object MinecraftVersion {
    /** Returns the part of [paper] before `.build.`, such as `26.2` of `26.2.build.121-stable`. */
    fun of(paper: String): String {
        val build = paper.indexOf(".build.")
        check(build > 0) { "The paper version $paper carries no .build., so it names no Minecraft version." }
        return paper.substring(0, build)
    }
}
