plugins {
    id("nordtal.paper-plugin")
}

repositories {
    // jcore and papermc-display-tags are both published via JitPack, not Maven Central - see the
    // workspace CLAUDE.md.
    maven("https://jitpack.io")
}

dependencies {
    // The same block hunger-games and limbo have: eu.nordtal.jcore.config is this repository's
    // config system, and jcore 3.0.0 exports the JDBI 3 / HikariCP / PostgreSQL stack this module
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
}
