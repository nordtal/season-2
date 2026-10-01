import eu.nordtal.s2.build.GenerateGlyphAdvances
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

// The base runs prepare() and enable() before anything reads SmpPlugin's fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:KnownInitializers", "eu.nordtal.s2.smp.SmpPlugin.prepare,eu.nordtal.s2.smp.SmpPlugin.enable")
    }
}

// The menus size their rows from this advance table, derived from the assembled pack on every build and never
// committed.
val assembledPack = configurations.resolvable("packForAdvances") { extendsFrom(configurations["resourcePack"]) }

val menuAdvances =
    tasks.register<GenerateGlyphAdvances>("generateMenuAdvances") {
        pack.from(assembledPack)
        font.set("nordtal/font/gui_r0.json")
        target.set(layout.buildDirectory.file("generated/advances/gui-row-advances.properties"))
    }

// Into the processed resources, not a source directory: messageSchema reads the source directories.
tasks.named<ProcessResources>("processResources") {
    from(menuAdvances) { into("nordtal/menu") }
}

// Files outside every source set that tests read; undeclared, an edit would leave :smp:test UP-TO-DATE.
repositoryRootTestInputs {
    readsTree("resource-pack/src/assets")
    reads("compose.yml")
    reads("deploy/minecraft/entrypoint.sh")
}

repositories {
    // jcore and papermc-display-tags are published via JitPack.
    maven("https://jitpack.io")
}

dependencies {
    // The assembled resource pack: the menu advance table and PanelWalk's chest-row fonts only exist there.
    "resourcePack"(project(":resource-pack", "pack"))

    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":pack-rendering")))

    // Flyway is excluded: this plugin never migrates, and flyway-core drags in Jackson 3.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    implementation(libs.hikaricp)

    // jcore declares jdbi3-postgres in runtime scope only.
    implementation(libs.jdbi.postgres)

    // Never shaded: a bundled copy of the API would not match the running DisplayTags plugin's classes.
    compileOnly(libs.display.tags)

    testImplementation(testFixtures(project(":database")))
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.smp.SmpMessages")
}
