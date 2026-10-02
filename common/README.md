# common

The shared kernel every other module stands on. It depends on the JDK alone (and Gson, which every
platform ships and nothing shades), so a Paper plugin, the proxy, the bot and Steward can all take
it without pulling a database, Adventure or the resource pack along. `:architecture` holds that list.

| Package    | What exists once here                                                                 |
| ---------- | ------------------------------------------------------------------------------------- |
| root       | `Platform`, the versions the season is built against; `SeasonPhase`                   |
| `time`     | `NetworkTime`, the only reader of the wall clock; `Waiting` and `Backoff`             |
| `http`     | `WebClient`, the one HTTP client, and the rule that keeps a token off plain http      |
| `json`     | `Json`, the one codec over records                                                    |
| `id`       | `DiscordId` and `PlayerId`; `Actor`, who asked, as rows and the internal wire hold it |
| `language` | `Languages`, the network's languages; `Locales`, a stored tag to a `Locale`           |
| `health`   | the readiness marker a container's health check reads                                 |

A process creates its clock once with `NetworkTime.clock()` and hands it to everything that asks;
no other class reads the wall clock, which is what makes countdowns and expiries testable without
sleeping.
