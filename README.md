# nordtal.eu JCore

A Java 25 library, built with Gradle, providing shared libraries and infrastructure code for
nordtal.eu's Java applications.

## Libraries

See [build.gradle.kts](build.gradle.kts) for exact versions. Dependencies declared `api(...)` are
exported to consumers; everything else is an implementation detail present at runtime only.

**Exported**: JSpecify (nullness annotations), SLF4J API, Apache Commons Lang 3, Commons IO, Gson,
SnakeYAML, JDBI 3 Core, JDBI 3 SqlObject.

**Runtime only**: JDBI 3 Postgres, HikariCP, Flyway, the PostgreSQL JDBC driver.

jcore does not export a logging backend or a JSON library beyond Gson; a consumer declares its own
(`ch.qos.logback:logback-classic` for Logback, for example).

## Commented YAML configuration

`eu.nordtal.jcore.config` describes a config file as an annotated interface (`@ConfigSpec`) and
keeps a YAML file in step with it:

```java
@ConfigSpec(header = {"Payment processing"})
public interface PaymentProcessingSpec {

    @Order(1) @Key("check-interval-seconds")
    @Explain("How often payments are checked, in seconds.")
    default long checkIntervalSeconds() { return 10; }

    @Reload void reload();
}

ConfigHandle<PaymentProcessingSpec> handle = ConfigLoader
        .builder(Path.of("config/payment-processing.yml"), PaymentProcessingSpec.class)
        .validator(config -> { /* ... */ })
        .onLoad(config -> log.info("Polling every {}s", config.checkIntervalSeconds()))
        .load();

PaymentProcessingSpec config = handle.get();   // stable across reloads, safe to keep in a field
```

The generated YAML carries no comments. `@Explain` supplies a short description that goes into a
schema file written beside the YAML (`<file>.schema.json`), not into the file itself; `@Comment` is
still read but only feeds the schema. `@NoExplanationNeeded`, `@Secret` and `@AllowedValues` also
shape that schema — see `eu.nordtal.jcore.config.schema.SchemaWriter` and `SchemaNode`.

A load writes a defaults file if none exists, deserializes, rejects a key that looks like a
misspelling of a declared one (the file is left untouched), drops a key that matches nothing,
rewrites the file and schema when either changed, applies the `NORDTAL_<PATH>` environment
overlay, validates, then runs the `onLoad` hook. Every value can be overridden by an environment
variable named `NORDTAL_<PATH>` (`.` and `-` become `_`); the environment wins over the file and is
never written back.

Validation is by hand, via a `ConfigValidator` passed to the builder — see
[`DatabaseConfig`](src/main/java/eu/nordtal/jcore/persistence/sql/DatabaseConfig.java) for an
example. A spec interface must be `public`; it is served by a reflective proxy.

The comment machinery is [Spec](https://github.com/Revxrsal/spec) (MIT), vendored into
`eu.nordtal.jcore.config.spec` rather than depended on. See [NOTICE](NOTICE) for the licence and
the header of each vendored file for what changed relative to upstream.

## SQL persistence

`eu.nordtal.jcore.persistence.sql` is JDBI 3 against PostgreSQL.
[`Database`](src/main/java/eu/nordtal/jcore/persistence/sql/Database.java) owns one HikariCP pool
and one `Jdbi` instance; create a single instance per application and close it on shutdown.

```java
try (Database database = Database.create(
        DatabaseConfig.of("jdbc:postgresql://localhost:5432/nordtal", "user", "secret"))) {

    database.migrate();                       // runs classpath:db/migration via Flyway

    PaymentDao dao = database.jdbi().onDemand(PaymentDao.class);
    Optional<Payment> payment = dao.findById(42);
}
```

`DatabaseConfig` is a record with a builder covering pool sizing, timeouts and an optional SQL
logger. Migrations live in `src/main/resources/db/migration`. Repositories are plain
[JDBI SqlObject](https://jdbi.org/#_sql_objects) interfaces;
[`JdbiRepository<D>`](src/main/java/eu/nordtal/jcore/persistence/sql/JdbiRepository.java) is
optional sugar holding the `Jdbi` and one on-demand DAO proxy.

## Publishing via JitPack

Releases are built and served by [JitPack](https://jitpack.io) directly from this repository.

Consuming jcore:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.nordtal:jcore:<tag>")
}
```

Releasing: push a git tag (`git tag 2.0.0 && git push origin 2.0.0`). JitPack builds it on first
request, using [jitpack.yml](jitpack.yml). Build status and logs are at
https://jitpack.io/#nordtal/jcore. The version comes from the `VERSION` environment variable
JitPack sets; a local build falls back to `local`.

Only `com.github.nordtal:jcore` via JitPack is maintained; `eu.nordtal:jcore` on Maven Central is
not.

## Building

`sh gradlew check` runs Spotless, Checkstyle, Error Prone + NullAway, the architecture test and the
test suite. `sh gradlew spotlessApply` formats. See [CONVENTIONS.md](CONVENTIONS.md) for the rules
this build enforces.
