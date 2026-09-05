plugins {
    id("nordtal.paper-plugin")
}

// ComposeWorldTest compares config.yml's world-nordtal default against the level-name compose
// actually starts Paper on, and checks that the entrypoint still writes it. Both files are outside
// every source set, so without this Gradle cannot see them and an edit to either would leave
// :smp:test UP-TO-DATE - the one check that would have caught it being the one that does not run.
repositoryRootTestInputs {
    // TravelPanelTest reads the travel panel's PNG and gui.json back and asserts every card is
    // where TravelPanel says it is - the one check that keeps the generator and the Java geometry
    // from drifting apart. The whole assets tree, for the reason common's build file gives.
    readsTree("resource-pack/src/assets")
    reads("compose.yml")
    reads("deploy/minecraft/entrypoint.sh")

    // SmpCommandWiringTest reads this plugin's main class as text. Gradle's test input is the
    // compiled class, not the source, so without this a change that produced identical bytecode
    // would leave :smp:test UP-TO-DATE - and the check it would skip is the one that says the
    // command inbox is actually started, which is the difference between /smp working from Discord
    // and timing out as though this server were down.
    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java")
}

repositories {
    // jcore and papermc-display-tags are both published via JitPack, not Maven Central - see the
    // workspace CLAUDE.md.
    maven("https://jitpack.io")

    // Chunky. Its author publishes to CodeMC, and nowhere else that carries this artefact; the
    // "chunky" coordinates on Maven Central belong to unrelated projects.
    maven("https://repo.codemc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":paper-common"))
    // The same block hunger-games and limbo have: eu.nordtal.jcore.config is this repository's
    // config system, and jcore 3.1.0 exports the JDBI 3 / HikariCP / PostgreSQL stack this module
    // needs anyway. The SMP is the module docs/architecture.md says needs persistence "heavily" -
    // aura, prestige, milestone progress, contributions, POIs, duels and graves all outlive a
    // restart.
    //
    // Flyway is excluded for the reason network-control's build file spells out at length: this
    // plugin never migrates anything (the bot owns the schema), and flyway-core alone drags in
    // ~1200 classes of Jackson 3 databind.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // SmpPool builds a HikariCP pool directly so the pool name, size and both timeouts are ours.
    implementation(libs.hikaricp)

    // JDBI's PostgresPlugin, which :common's directories and this module's own DAO install. jcore
    // declares jdbi3-postgres in `runtime` scope only.
    implementation(libs.jdbi.postgres)

    // Nametags, decided 2026-09-01 (docs/smp.md#what-a-player-looks-like).
    //
    // compileOnly AND NEVER SHADED. The api module is an interface over the running DisplayTags
    // plugin, not a library: a bundled copy would be a second set of classes for the same
    // interfaces and the lookup would hand back an instance of the wrong one. The consequence is
    // operational and is stated in deploy/README.md#third-party-plugins - DisplayTags, and
    // PacketEvents underneath it, become REQUIRED on the SMP server, which is the network's first
    // mandatory third-party runtime dependency. paper-plugin.yml declares it with load: BEFORE and
    // required: true so a server missing it fails loudly at start rather than quietly rendering
    // plain nametags.
    compileOnly(libs.display.tags)

    // Chunky, decided 2026-09-01. The farm world is regenerated daily and has to be pre-generated
    // to its border before anyone is let in - roughly 15 000 chunks, every day, next to a live
    // server, which docs/smp.md calls the single biggest technical risk in the concept. Writing our
    // own throttled generator was the alternative and was rejected: Chunky already solves exactly
    // this, its throttle is the one operators know, and an in-house copy would be a second thing to
    // test on every Minecraft update.
    //
    // compileOnly AND NEVER SHADED, for the same reason as DisplayTags: `ChunkyAPI` is an interface
    // over the running plugin, handed out through Bukkit's ServicesManager. A bundled copy would be
    // a second set of classes for the same interface and the service lookup would return an
    // instance the plugin cannot cast. paper-plugin.yml declares it required: true, so a server
    // without it fails at start rather than at 04:00 the next morning.
    compileOnly(libs.chunky)

    // SpinRefundIntegrationTest drives the two wheel-refund statements against a real PostgreSQL
    // running the real migrations. It is the only test in this module that needs a database, and it
    // needs one because everything that can go wrong in a refund is a property of the database and
    // not of Java: `last_free` is a nullable date and refunding a first-ever free spin writes null
    // into it, which is exactly where PostgreSQL answers "could not determine data type of
    // parameter" unless the statement casts; the free refund has to be idempotent; and the earned
    // one has to stay inside smp_spin_used_not_negative. None of the three has an in-memory
    // stand-in. The test skips itself when no Docker daemon is reachable.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)

    // The persistence stack is `implementation` above (this module uses it heavily), but the driver
    // arrives at runtime only - and the test builds a PGSimpleDataSource by hand, so it has to ask
    // for it by name to compile against it.
    testImplementation(libs.postgresql.driver)
}
