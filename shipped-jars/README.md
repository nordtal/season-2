# shipped-jars

Every jar a release ships (the four plugins, the bot, Steward and steward-agent), loaded the way its host loads it. The module has
no production code. Each module's own tests run with a classpath of their own, so a dependency the shaded jar lost, or a class it
names by string, passes them and fails on a server.

| check                                               | what it holds                                                                                                        |
| --------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| every class our code names is in the jar or host    | the constant pool of each of our classes, class references and strings shaped like a class name, and the entry class |
| the jar opens its database pool on a fresh Postgres | `Database.open` through the jar's own class loader, whose parent is the platform loader, and one query               |

The host of a Paper plugin is Paper's API with the plugins it depends on, the host of the proxy is Velocity's API, and an
application has none. `:shipped-jars:test` builds each `shadowJar` first and needs a Docker daemon, as every database test does.
