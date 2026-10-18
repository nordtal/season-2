# common

`common` is the kernel that every other module uses. It depends on the JDK and Gson only, so a Paper
plugin, the proxy, the bot and Steward use it without a database, Adventure or the resource pack.
`:architecture` holds that dependency list.

| Package    | Contents                                                                              |
| ---------- | ------------------------------------------------------------------------------------- |
| root       | `Platform`, `SeasonPhase`                                                             |
| `time`     | `NetworkTime`, `Scheduler`, `ProcessScheduler`, `Waiting`, `Backoff`, `CountdownPlan` |
| `http`     | `WebClient`                                                                           |
| `json`     | `Json`                                                                                |
| `id`       | `DiscordId`, `PlayerId`, `Actor`                                                      |
| `language` | `Languages`, `Locales`                                                                |
| `health`   | `Readiness`, the marker file that a container's health check reads                    |

`NetworkTime.clock()` is the only reader of the wall clock. A process creates its clock once and
passes it on, so a test runs countdowns and expiries without sleeping.

A JVM process creates one `ProcessScheduler` at start and passes it on as a `Scheduler`. A timer
thread hands work to a virtual thread per run. `Scheduler.every` arms the next run after the last
one ended, so two runs never overlap. No feature owns a thread pool or a timer, and `:architecture`
holds that. A test drives timed work through the test fixture `ManualScheduler`.

Each platform has one facade that is a `Scheduler`: `PaperScheduler` in paper-common and
`VelocityScheduler` in the proxy. Only these two classes reach the platform's scheduler, and
`:architecture` holds that.
