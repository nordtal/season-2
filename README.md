# nordtal.eu JCore
.. is a Java 25 library built using Gradle that provides all Java applications of nordtal.eu with libraries that are used across the whole organisation and utility classes.

## List of libraries
The following is a list of all libraries that come with jcore. For details view [build.gradle.kts](build.gradle.kts). Only dependencies marked as `api("...")` are exported to projects that import jcore; everything else is an implementation detail that is present at runtime but not on the consumer's compile classpath.

**Exported (`api`)**
- **JetBrains Annotations** (usually for `@NotNull` and `@Nullable` annotations)
- **SLF4J API** (the logging *facade* - retrieve a `org.slf4j.Logger` from `org.slf4j.LoggerFactory`)
- **Apache Commons Lang 3** (Java utilities)
- **Commons IO** (IO utilities)
- **Gson** (config values are serialized through it; `ConfigLoader.gsonBuilder()` returns a `GsonBuilder`)
- **SnakeYAML** (the YAML reader/writer under the config system)
- **JDBI 3 Core** (`Jdbi` is returned by `Database#jdbi()`)
- **JDBI 3 SqlObject** (so consumers can declare `@SqlQuery` / `@SqlUpdate` DAO interfaces)

**Runtime only (not on your compile classpath)**
- **JDBI 3 Postgres**, **HikariCP**, **Flyway** (`flyway-core` + `flyway-database-postgresql`), **PostgreSQL JDBC driver**

> **Breaking change in 2.0.0 — Jackson is gone.** `com.fasterxml.jackson.core:jackson-databind` was an `api` dependency up to 1.0.2, purely for the JSON config loader. That loader has been replaced (see below) and nothing in jcore uses Jackson any more, so it was dropped rather than demoted. **A consumer that uses Jackson must now declare it itself.** In exchange jcore brings Gson 2.14.0 and SnakeYAML 2.6 — both of which Paper 26.2 already ships in its `libraries/` directory (verified on a running 26.2 server on 2026-08-30), so a Paper plugin can declare them `compileOnly` and keep them out of its shaded jar. Jackson is not among Paper's libraries.

> **Breaking change in 2.0.0:** jcore no longer exports a logging *backend*. Up to 1.0.2 `logback-classic` was an `api` dependency, so every consumer got it for free. A library must not pick the backend for its consumers, so it is now test-scoped. **Consumers that log via Logback must declare `ch.qos.logback:logback-classic` themselves.** Without any SLF4J binding on the classpath you get the "no providers were found" warning and silent logs.

## List of utilities included
The following is brief overview of the utility classes provided by jcore.

### Commented YAML configuration

`eu.nordtal.jcore.config` describes a config file as an **annotated interface** and keeps a
commented YAML file in step with it. It replaces `JsonConfigLoader` and `JsonConfig`, which are
gone in 2.0.0.

```java
@ConfigSpec(header = {
        "Payment processing",
        "Any setting here can be overridden with NORDTAL_<SETTING>."
})
public interface PaymentProcessingSpec {

    @Order(1) @Key("check-interval-seconds")
    @Comment("How often the bunq account is polled for new payments, in seconds.")
    default long checkIntervalSeconds() { return 10; }

    @Order(2) @Key("confirmation-channel-id")
    @Comment("Discord channel that receives payment confirmations.")
    default String confirmationChannelId() { return "1397264662545957056"; }

    @Reload void reload();
}

ConfigHandle<PaymentProcessingSpec> handle = ConfigLoader
        .builder(Path.of("config/payment-processing.yml"), PaymentProcessingSpec.class)
        .validator(config -> {
            if (config.checkIntervalSeconds() <= 0)
                throw new IllegalArgumentException("check-interval-seconds must be positive");
        })
        .onLoad(config -> log.info("Polling every {}s", config.checkIntervalSeconds()))
        .load();

PaymentProcessingSpec config = handle.get();   // stable across reloads, safe to keep in a field
```

The generated file carries the header and every `@Comment`, in `@Order`:

```yaml
# Payment processing
# Any setting here can be overridden with NORDTAL_<SETTING>.

# How often the bunq account is polled for new payments, in seconds.
check-interval-seconds: 10
# Discord channel that receives payment confirmations.
confirmation-channel-id: '1397264662545957056'
```

**What a load does, in order.** Write a defaults file if none exists; read it; reject any key that
reads as a *misspelling* of a declared one; deserialize; if the canonical rendering differs from
what is on disk — a new setting, a reworded comment, a setting the interface has dropped — back the
file up to `.bak` and rewrite it *atomically*; apply the environment overlay; validate; run the
`onLoad` hook. The hook runs **every** time, whether or not anything changed.

**A misspelled key stops the start; a retired one is deleted.** Both are keys the interface does
not declare, and the difference is whether a declared key is close enough to name. If one is, the
load aborts with the full path — including the index inside a list, `worlds[1].display-color` — and
the key that was probably meant, and **the file is not touched**: only the operator knows what they
meant by that line, and deleting it would cost them the setting and the evidence at once. If
nothing is close, that setting no longer exists in the software; there is nothing to fix and
nothing to decide, so it is dropped by the rewrite, named in a `WARN` line, and still readable in
the `.bak`. Stopping a process over a line that is already dead helps nobody — and the old loader's
mistake was not deleting, it was deleting the *typo* case, silently and without a backup.

**Every value can be overridden by an environment variable**, named `NORDTAL_<PATH>` with `.` and
`-` both becoming `_` — `balance.channel-id` is `NORDTAL_BALANCE_CHANNEL_ID`. The environment wins
over the file, and an overridden value is **never written back**, so a secret handed to a container
cannot leak into a mounted config volume. Which settings were overridden is logged; the values are
not. Two settings whose variable names would collide are rejected the first time the config loads,
so a collision is a bug in the interface rather than a surprise in production.

**Reload and threading.** A `@Reload` method on the interface re-reads the file through the same
strict path, which is what a `/reload` command should call. Reads through `get()` are lock-free;
reload and save take a write lock shared by every handle on the same file.

**Validation is by hand**, in a `ConfigValidator`, modelled on
[`DatabaseConfig`](src/main/java/eu/nordtal/jcore/persistence/sql/DatabaseConfig.java). Jakarta Bean
Validation was considered and rejected: roughly 1.4 MiB in every plugin jar for a handful of
if-statements.

Spec interfaces must be `public` — they are served by a reflective proxy. jcore checks this when
the config is built rather than letting it fail later.

The comment machinery is [Spec](https://github.com/Revxrsal/spec) (MIT, Copyright (c) 2021
Revxrsal), **vendored** into `eu.nordtal.jcore.config.spec` rather than depended on, because it is
unmaintained (last push 2025-05-01) and needed the hardening above. See [NOTICE](NOTICE) for the
licence, and the header of each vendored file for what was changed.

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

## Migrating to 2.0.0

| 1.0.2 | 2.0.0 |
|---|---|
| `class Foo extends JsonConfig` with fields | `public interface FooSpec` with `@ConfigSpec`, one default method per setting |
| `JsonConfigLoader.load(file, Foo.class)` | `ConfigLoader.builder(path, FooSpec.class).load()` returning a `ConfigHandle` |
| `JsonConfigLoader.save(file, foo)` | `handle.save()` |
| `postLoad()` — only ran when the file differed | `.onLoad(...)` — runs on every load |
| `preSave()` | do it before calling `handle.save()` |
| `config.json`, Jackson, `SNAKE_CASE` field names | `config.yml`, Gson + SnakeYAML, explicit `@Key` |
| unknown keys deleted silently | a misspelled key aborts the load, file untouched; a retired one is deleted with a warning and a `.bak` |
| scattered `System.getenv` calls | `NORDTAL_<SETTING>` overlay on every value |
| `ConfigInitializationException` | gone — there is no reflective instantiation of a config class any more |
| `jackson-databind` on your compile classpath | declare it yourself if you need it |

An existing JSON file is **not** read by the new loader. Convert it — `payments-bot` does this
once, automatically, on first start; see its `Configs` class.

### Note on the old Maven Central artifact
`eu.nordtal:jcore:1.0.1` on Maven Central is superseded and will not receive further updates. Use the `com.github.nordtal:jcore` coordinates via JitPack instead.
