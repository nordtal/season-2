# nordtal.eu JCore
.. is a Java 25 library built using Gradle that provides all Java applications of nordtal.eu with libraries that are used across the whole organisation and utility classes.

## List of libraries
The following is a list of all libraries that come with jcore. For details view [build.gradle.kts](build.gradle.kts). Only dependencies marked as `api("...")` are exported to projects that import jcore; everything else is an implementation detail that is present at runtime but not on the consumer's compile classpath.

**Exported (`api`)**
- **JetBrains Annotations** (usually for `@NotNull` and `@Nullable` annotations)
- **SLF4J API** (the logging *facade* - retrieve a `org.slf4j.Logger` from `org.slf4j.LoggerFactory`)
- **Apache Commons Lang 3** (Java utilities)
- **Commons IO** (IO utilities)
- **FasterXML's Jackson databind** (for working with JSON and mapping objects)
- **JDBI 3 Core** (`Jdbi` is returned by `Database#jdbi()`)
- **JDBI 3 SqlObject** (so consumers can declare `@SqlQuery` / `@SqlUpdate` DAO interfaces)

**Runtime only (not on your compile classpath)**
- **JDBI 3 Postgres**, **HikariCP**, **Flyway** (`flyway-core` + `flyway-database-postgresql`), **PostgreSQL JDBC driver**

> **Breaking change in 2.0.0:** jcore no longer exports a logging *backend*. Up to 1.0.2 `logback-classic` was an `api` dependency, so every consumer got it for free. A library must not pick the backend for its consumers, so it is now test-scoped. **Consumers that log via Logback must declare `ch.qos.logback:logback-classic` themselves.** Without any SLF4J binding on the classpath you get the "no providers were found" warning and silent logs.

## List of utilities included
The following is brief overview of the utility classes provided by jcore.

### JSON config loading with config classes / objects
The [JsonConfigLoader](src/main/java/eu/nordtal/jcore/config/JsonConfigLoader.java) provides methods to load and save JSON config files to and from predefined classes / objects which inherit from [JsonConfig](src/main/java/eu/nordtal/jcore/config/JsonConfig.java). The needed inheritance of JsonConfig is currently redundant, but might be used in the future for new features. The JsonConfigLoader automatically adds and removes new config parameters on load.

### SQL persistence (JDBI 3 + HikariCP + Flyway)
`eu.nordtal.jcore.persistence.sql` replaces the Hibernate and Morphia repositories that jcore shipped up to 1.0.2. MongoDB support is gone entirely, and the relational side is now JDBI 3 against PostgreSQL.

[`Database`](src/main/java/eu/nordtal/jcore/persistence/sql/Database.java) owns **one** HikariCP pool and **one** `Jdbi` instance. Create a single instance per application per database and close it on shutdown - the old code built one Hibernate `SessionFactory` per entity, which meant one connection pool per entity against the same server.

```java
try (Database database = Database.create(
        DatabaseConfig.of("jdbc:postgresql://localhost:5432/nordtal", "user", "secret"))) {

    database.migrate();                       // runs classpath:db/migration via Flyway

    PaymentDao dao = database.jdbi().onDemand(PaymentDao.class);
    Optional<Payment> payment = dao.findById(42);
}
```

The SQL dialect is implied by the JDBC URL; no class in the package names a specific database. `DatabaseConfig` is a record with a builder covering pool sizing, timeouts and a `logSql` flag (off by default) that installs an SLF4J `SqlLogger` logging rendered statements at `DEBUG` - bound parameter values are never logged.

Schema management is Flyway, not `hbm2ddl.auto=update`. Put versioned SQL in `src/main/resources/db/migration` and call `Database#migrate()`, which returns the number of migrations applied.

Repositories are plain [JDBI SqlObject](https://jdbi.org/#_sql_objects) interfaces. There is deliberately no generic `save`/`findFirst(field, value)` abstraction any more - the typed DAO interface *is* the abstraction. [`JdbiRepository<D>`](src/main/java/eu/nordtal/jcore/persistence/sql/JdbiRepository.java) is optional sugar that holds the `Jdbi` and one on-demand DAO proxy for you.

## Publishing via JitPack
Releases are built and served by [JitPack](https://jitpack.io) straight from this repository — no Sonatype account, no GPG signing, no manual upload.

### Consuming jcore
Add the JitPack repository and the dependency:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.nordtal:jcore:<tag>")
}
```

Replace `<tag>` with a released git tag (e.g. `2.0.0`). A commit hash or `master-SNAPSHOT` also works.

### Releasing
1. Push a git tag: `git tag 2.0.0 && git push origin 2.0.0`
2. That is it. JitPack builds the tag the first time somebody requests it; the build config lives in [jitpack.yml](jitpack.yml) (Java 25 toolchain, `./gradlew build publishToMavenLocal`). Build status and logs are at https://jitpack.io/#nordtal/jcore.

The version is taken from the `VERSION` environment variable JitPack sets; local builds fall back to `local`.

### Note on the old Maven Central artifact
`eu.nordtal:jcore:1.0.1` on Maven Central is superseded and will not receive further updates. Use the `com.github.nordtal:jcore` coordinates via JitPack instead.
