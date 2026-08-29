# DisplayTags

<a href="https://github.com/imskeptical/DisplayTags/wiki" target="_blank">
  <img alt="generic" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/documentation/generic_vector.svg">
</a>
<a href="https://github.com/imskeptical/DisplayTags" target="_blank">
  <img alt="github" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg">
</a>
<a href="https://modrinth.com/project/voqEPXf8" target="_blank">
  <img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg">
</a>
<hr />

A lightweight plugin for Minecraft for replacing boring old vanilla Minecraft player nametags with fast & customizable text displays!

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
DisplayTags publishes a small `api` module containing the nametag manager, the nametag data model, and `NameTagCreate`, `NameTagRemove`, `NameTagSpawn` and `NameTagDespawn` events. Depend on that module only — everything outside it is internal and may change without notice.

### Using the API
The `api` module is published on [JitPack](https://jitpack.io/#nordtal/papermc-display-tags). Add the repository and depend on the `api` module using a release tag or commit hash as the version:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.nordtal.papermc-display-tags:api:VERSION'
}
```

![DisplayTags Example](https://imagedelivery.net/W9K_l6ndK9x4x8m3rurakg/ebf31098-5459-46e7-e9a7-ac07cf1c0500/original)
