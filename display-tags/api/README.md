# DisplayTags API

DisplayTags publishes a small `api` module for other plugins to read and change player name tags
without touching DisplayTags' internals. This document covers getting an instance to develop
against, adding the API as a build dependency, and using it from Java.

## Getting a working DisplayTags instance

You need a real DisplayTags install to develop and test against — the API is an interface over the
running plugin, not a standalone library.

**Option A — this repository's dev server.** Clone
[papermc-display-tags](https://github.com/nordtal/papermc-display-tags) and run:

```bash
sh gradlew runServer
```

This starts a local Paper 26.2 server with DisplayTags, PacketEvents, PlaceholderAPI and TAB
already installed. Build your own plugin and drop its jar into `../run/plugins`, then restart the
server to test against a live DisplayTags instance.

**Option B — any Paper 26.2 server.** Build the plugin jar yourself with `sh gradlew build` (the
shaded jar ends up at `build/libs/papermc-display-tags-<version>.jar`), or download it from a
[GitHub release](https://github.com/nordtal/papermc-display-tags/releases). Install it, and
[PacketEvents](https://modrinth.com/plugin/packetevents) v2.13.0+, on a Paper 26.2 server running
Java 25.

Either way, declare DisplayTags as a dependency in your own plugin's `paper-plugin.yml` so it loads
first:

```yaml
dependencies:
  server:
    DisplayTags:
      load: BEFORE
      required: true   # false for a soft dependency
```

## Adding the API as a build dependency

The `api` module is published on [JitPack](https://jitpack.io/#nordtal/papermc-display-tags), built
from this repository. Depend on `com.github.nordtal:papermc-display-tags:VERSION`, where `VERSION`
is a release tag (e.g. `2.0.0`) or a commit hash. Use a provided/compile-only scope — DisplayTags
must be installed on the server at runtime, so never shade the API into your own jar.

### Gradle (Groovy DSL)

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.nordtal:papermc-display-tags:2.0.0'
}
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.nordtal:papermc-display-tags:2.0.0")
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.nordtal</groupId>
        <artifactId>papermc-display-tags</artifactId>
        <version>2.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## Using the API

### Entry point

```java
DisplayTagsPlugin displayTags = DisplayTagsPlugin.get();
NameTagManager nameTagManager = displayTags.getNameTagManager();
```

`DisplayTagsPlugin.get()` looks the plugin up by name and returns `null` if it is not installed or
not enabled — check for that if you declared it as a soft dependency.

### Reading and creating name tags

```java
PlayerNameTag tag = nameTagManager.getByPlayer(player); // null if the player has none yet
if (tag == null) {
    tag = nameTagManager.createNameTag(player);
}
```

- `nameTagManager.getAll()` — every name tag currently registered.
- `nameTagManager.removeNameTag(player)` — removes a player's tag, despawning it for every viewer.

### Changing what a tag looks like

Each `PlayerNameTag` carries a mutable `NameTagData`:

```java
NameTagData data = tag.getData();
data.setLines(List.of("<gold>VIP</gold>", "{player}"));
data.setShowToSelf(false);
data.setVisibilityDistance(48);
data.setBackground("#40000000"); // or "default" / "transparent"
```

After mutating it, push the change out to viewers:

```java
tag.updateForViewers();
```

### Listening to lifecycle events

Four Bukkit events fire on a name tag's lifecycle. `NameTagCreateEvent`/`NameTagRemoveEvent` fire
once, per name tag; `NameTagSpawnEvent`/`NameTagDespawnEvent` fire once per viewer and are
cancellable.

```java
@EventHandler
public void onNameTagSpawn(NameTagSpawnEvent event) {
    if (event.getViewer().hasPermission("myplugin.hide-tags")) {
        event.setCancelled(true);
    }
}
```

| Event | Fires when | Cancellable |
|---|---|---|
| `NameTagCreateEvent` | a tag is created for a player | no |
| `NameTagRemoveEvent` | a tag is removed | no |
| `NameTagSpawnEvent` | the tag's display spawns for one viewer | yes |
| `NameTagDespawnEvent` | the tag's display despawns for one viewer | yes |

### Staying inside the supported surface

Depend on the `api` module only — everything in the main plugin outside the
`eu.nordtal.displaytags.api` package is an internal implementation detail and may change without
notice between releases. The `api` module itself has no PacketEvents or Spec dependency, so it
never pulls those onto your plugin's classpath.
