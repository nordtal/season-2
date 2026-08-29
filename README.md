# papermc-display-tags

<a href="api/README.md" target="_blank">
  <img alt="generic" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/documentation/generic_vector.svg">
</a>
<a href="https://github.com/nordtal/papermc-display-tags" target="_blank">
  <img alt="github" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg">
</a>
<hr />

**DisplayTags** is a lightweight plugin for Minecraft for replacing boring old vanilla Minecraft player nametags with fast & customizable text displays!

## Features
- 🔗 **Custom** Player Nametags
- 🔥 **Lightweight & Fast** (powered with packets)
- ✏️ **Full** Customizability!
- ☀️ Supports [**MiniMessage Formatting**](https://webui.advntr.dev/)
- 🔠 Supports [**PlaceholderAPI**](https://github.com/PlaceholderAPI/PlaceholderAPI/releases)
- 🤝 Plays nicely with [**TAB**](https://modrinth.com/plugin/tab-was-taken) — if TAB is installed, it keeps control of the vanilla nametags
- 🧩 Ships a **developer API** with nametag events for other plugins

## Installation
❗ **NOTE** This plugin is powered by [PacketEvents](https://modrinth.com/plugin/packetevents). It is a **required dependency**. Please install it along with **DisplayTags**, otherwise it will not work!

- Requires **Paper 26.2** (**Spigot** is **not** supported)
- Requires **Java 25** (this is what Paper 26.2 itself requires)
- Requires [**PacketEvents v2.13.0**](https://modrinth.com/plugin/packetevents) or newer

Still on Minecraft 1.21.x? Use **DisplayTags 1.1.5** instead — this release does not run on older servers.

## Optional integrations
- [**PlaceholderAPI**](https://github.com/PlaceholderAPI/PlaceholderAPI/releases) — use any placeholder inside your nametag lines.
  ⚠️ PlaceholderAPI's authors currently mark their Paper 26.2 support as **experimental**, because Paper's new version scheme broke their version parsing. DisplayTags hooks into it fine, but placeholder behaviour on 26.2 is theirs to guarantee, not ours.
- [**TAB**](https://modrinth.com/plugin/tab-was-taken) — when TAB is present, DisplayTags stops sending its own scoreboard team packets and lets TAB hide the vanilla nametags.

## For developers
See the [**API reference**](api/README.md) for how to get a DisplayTags instance to develop against, add the API as a dependency (Gradle Groovy, Gradle Kotlin and Maven), and use it from your own plugin.

![DisplayTags Example](https://imagedelivery.net/W9K_l6ndK9x4x8m3rurakg/ebf31098-5459-46e7-e9a7-ac07cf1c0500/original)
