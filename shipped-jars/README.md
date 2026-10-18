# shipped-jars

`:shipped-jars:test` builds each `shadowJar` of a release (the four plugins, the bot, Steward and
steward-agent) first. It needs a Docker daemon. The module has no production code.

| check                                               | what it holds                                                                                           |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| every class our code names is in the jar or host    | Every class reference and class-shaped string in our classes, and the entry class, resolves.            |
| the jar opens its database pool on a fresh Postgres | `Database.open` runs one query through the jar's own class loader, whose parent is the platform loader. |

The host of a Paper plugin is Paper's API with the plugins it depends on. The host of the proxy is
Velocity's API. An application has no host.
