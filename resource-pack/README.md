# resource-pack
The nordtal.eu resource pack provides assets for the game in the form of characters within the range of `\uE000` to `\uF8FF`. It also has some vanilla overrides.

## Building and deploying

This is a module of the [season-2](../) build. From the repository root:

```bash
./gradlew :resource-pack:packZip
```

produces `resource-pack/build/distributions/nordtal-resource-pack-<version>.zip` and a
`.sha1` file next to it. The zip's root is the contents of [`src/`](src/) — `pack.mcmeta` and
`assets/` must sit at the top level, not inside a `src/` folder.

The client is sent the zip's URL **and** its SHA-1, and refuses the pack if they disagree, which
is why the hash is generated on every build rather than written down. The zip and its hash are
attached to each GitHub release; `resource-pack-coercion` serves them to players.

To test locally, copy the contents of [`src/`](src/) into a folder in your game's
`resourcepacks/` directory.

# Complete asset overview

## Default font
These characters are defined in [`minecraft/font/default.json`](src/assets/minecraft/font/default.json), making them availbale across the entire Minecraft client.

### Role tags (`\uE000` - `\uE00F`)
| Char code      | File      | Char | Description |
|----------------|-----------|------|-------------|
| `\uE000`| ![source](src/assets/nordtal/textures/tags/settler.png) |  | Settler role full tag |
| `\uE001`| ![source](src/assets/nordtal/textures/tags/citizen.png) |  | Citizen role full tag |
| `\uE002`| ![source](src/assets/nordtal/textures/tags/knight.png) |  | Knight role full tag |
| `\uE003`| ![source](src/assets/nordtal/textures/tags/lord.png) |  | Lord role full tag |
| `\uE004`| ![source](src/assets/nordtal/textures/tags/a.png) |  | Admin role short tag |

### Region flags (`\uE010` - `\uE01F`)
| Char code      | File      | Char | Description |
|----------------|-----------|------|-------------|
| `\uE010`| ![source](src/assets/nordtal/textures/flags/other.png) |  | Other flag |
| `\uE011`| ![source](src/assets/nordtal/textures/flags/germany.png) |  | Germany flag |
| `\uE012`| ![source](src/assets/nordtal/textures/flags/netherlands.png) |  | Netherlands flag |
| `\uE013`| ![source](src/assets/nordtal/textures/flags/unitedkingdom.png) |  | United Kingdom flag |
| `\uE014`| ![source](src/assets/nordtal/textures/flags/unitedstates.png) |  | United States flag |

### Logo assets (`\uE020` - `\uE02F`)
| Char code      | File      | Char | Description |
|----------------|-----------|------|-------------|
| `\uE020`| ![source](src/assets/nordtal/textures/assets/logo.png) |  | Nordtal long logo transparent height 24 |
| `\uE021`| ![source](src/assets/nordtal/textures/assets/logo.png) |  | Nordtal long logo transparent height 32 |
