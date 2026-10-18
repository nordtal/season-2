# BlueMap für map.nordtal.eu

Dieser Bericht prüft, wie weit sich BlueMap an Nordtal anpassen lässt: mit einem Overlay, mit einem Fork der Webapp, mit einem Fork des Plugins oder mit einer eigenen Oberfläche. Er trennt drei Arten von Aussagen:

- **Fakt:** Eine Aussage mit Quelle. Die Quelle ist eine Datei im BlueMap-Quellcode (Stand Tag `v5.29`, Commit `12823c3` auf `master`), eine Wiki-Seite oder eine API-Antwort.
- **Gemessen:** Eine Aussage, die in dieser Session gelaufen ist, mit Messwert.
- **Schätzung:** Eine Aussage ohne Messung. Sie ist als Schätzung markiert.

Die Prototyp-Dateien liegen in `research/bluemap/prototype/`. Der Prototyp ist geschrieben, aber nicht gebaut und nicht gerendert: Die Freigabe der Session hat das Ausführen von fremdem Code (BlueMap-Build mit `npm ci`, Gradle, CLI) abgelehnt. Abschnitt 6 beschreibt, was fehlt und wie es nachzuholen ist.

## 1. Lizenz und Projektstand

### Lizenz

| Repo                                                                 | Lizenz        | Quelle                                             |
| -------------------------------------------------------------------- | ------------- | -------------------------------------------------- |
| `BlueMap-Minecraft/BlueMap` (Plugin, CLI, Webapp in `common/webapp`) | MIT           | `LICENSE` im Repo, Modrinth-API `license.id = MIT` |
| `BlueMap-Minecraft/BlueMapAPI` (eingebunden als Submodul `api`)      | MIT           | `LICENSE` im Repo                                  |
| Eigenes Webapp-Repo                                                  | gibt es nicht | Die Webapp liegt in `common/webapp` im Haupt-Repo. |
| Schrift Quicksand in der Webapp                                      | SIL OFL       | `common/webapp/src/fonts/OFL.txt`                  |
| three.js, Vue, vue-i18n, hammerjs                                    | MIT           | `common/webapp/package.json`, Lizenzen der Pakete  |

**Fakt:** MIT erlaubt Forken, Ändern und öffentliches Ausliefern, auch einer veränderten Webapp. Die einzige Pflicht: Der Copyright-Hinweis (`Copyright (c) Blue <https://www.bluecolored.de>` und `Copyright (c) contributors`) und der MIT-Text bleiben in jeder Kopie und in jedem wesentlichen Teil erhalten.

**Folge für Nordtal:**

- Eine ausgelieferte Webapp, ob verändert oder nicht, enthält eine Datei mit dem MIT-Text von BlueMap (zum Beispiel `LICENSE.bluemap.txt` im Webroot). Die gebauten JS-Dateien behalten ihre Lizenzkommentare.
- Wenn Nordtal Quellcode von BlueMap in `season-2` übernimmt, bekommt `NOTICE` einen Abschnitt wie den zu Spec.
- Wenn die Schrift Quicksand im Fork bleibt, liegt `OFL.txt` mit im Webroot. Ein Fork mit eigener Schrift lässt beides weg.
- Den Namen „BlueMap“ darf Nordtal nicht als eigenen Produktnamen führen. MIT regelt keine Marken. Ein Hinweis „Karte: BlueMap“ mit Link ist üblich und genügt (Schätzung, keine Rechtsberatung).

### Projektstand

| Punkt                  | Wert                                                                                                                                                 | Art  | Quelle                                                                                         |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | ---------------------------------------------------------------------------------------------- |
| Aktuelle Version       | 5.29, erschienen am 10.10.2026                                                                                                                       | Fakt | Git-Tag `v5.29`, Modrinth-API                                                                  |
| Release-Takt           | 5.24 (9.9.), 5.25 (15.9.), 5.26 und 5.27 (16.9.), 5.28 (25.9.), 5.29 (10.10.2026). Davor 5.20 (12.4.), 5.21 und 5.22 (17.6.), 5.23 (6.8.2026).       | Fakt | Datum der Commits hinter den Tags                                                              |
| Aktivität              | 298 Commits in den letzten 12 Monaten, davon 224 von Lukas Rieger (Maintainer) und 51 von Dependabot. Die übrigen kommen von etwa zehn Mitwirkenden. | Fakt | `git shortlog` ab 1.10.2025                                                                    |
| Letzter Commit         | 16.10.2026                                                                                                                                           | Fakt | `git log`                                                                                      |
| Downloads auf Modrinth | 553 959                                                                                                                                              | Fakt | Modrinth-API `project/bluemap`                                                                 |
| Paper-Versionen        | 26.1.1, 26.1.2, 26.2, 26.3                                                                                                                           | Fakt | `implementations/paper/build.gradle.kts` (`supportedMinecraftVersions`), Modrinth `5.29-paper` |
| Plattformen            | Paper, Purpur, Folia, Spigot, Sponge, Fabric, Forge, NeoForge, CLI                                                                                   | Fakt | `implementations/`, Modrinth `loaders`                                                         |
| BlueMapAPI             | 2.8.2, Maven `de.bluecolored:bluemap-api` aus `https://repo.bluecolored.de/releases`                                                                 | Fakt | Git-Tags von BlueMapAPI, `BlueMapAPI.wiki/Home.md`                                             |
| Laufzeit               | Java 25 (`eclipse-temurin:25-jre` im Dockerfile)                                                                                                     | Fakt | `Dockerfile`                                                                                   |
| Docker-Image           | `ghcr.io/bluemap-minecraft/bluemap` (CLI)                                                                                                            | Fakt | `Dockerfile` (`BLUEMAP_COMMAND`)                                                               |

**Kompatibilität:** Das SMP läuft auf Paper 26.2 (`compose.yml`, `SERVER_VERSION: "26.2"`). BlueMap 5.29 nennt 26.2 und 26.3 für Paper. Die Plugin-Version passt also zu beiden Zielversionen. **Fakt.**

**Webapp-Änderungen upstream:** In den letzten 12 Monaten gab es 9 Commits in `common/webapp/src` ohne Abhängigkeits-Updates (30 Dateien, +718/−124 Zeilen). Kein Commit änderte `src/components`, `src/scss` oder `App.vue`. Die Änderungen betrafen Kacheln, Shader, Caching, Server-Sent Events und statisches Hosting in `src/js`. 6 Commits änderten Übersetzungen in `public/lang`. **Fakt** (`git log` ab 18.10.2025).

## 2. Ohne Fork, nur mit Fork

### Erweiterungspunkte ohne Fork

**Fakt** (Quellen: `WebappConfig.java`, `webapp.conf`, `BlueMapApp.js`, `main.js`, Wiki `community/Customisation.md` und `wiki/customization/Markers.md`):

- **`webapp.conf`:** Die Datei setzt Startposition (`start-location`), Kartenansicht, Zoom-Grenzen, Regler-Grenzen, Cookies, `map-data-root`, `live-data-root`, `client-decompression` und zwei Listen: `scripts` und `styles`.
- **`styles`:** Die Webapp hängt jede Datei als `<link rel="stylesheet">` an `<head>`, bevor sie die Karten lädt.
- **`scripts`:** Die Webapp hängt jede Datei als `<script>` an `<body>`, nachdem die Karten geladen sind. Ein Skript erreicht `window.bluemap` (die laufende `BlueMapApp` mit dem reaktiven `appState`, dem `mapViewer` und den Markern) und `window.BlueMap` (61 Exporte: Marker-Klassen, Steuerung, three.js).
- **Events:** `bluemapMapChanged`, `bluemapMapInteraction`, `bluemapCameraMoved`, `bluemapTileLoaded` und weitere. Sie laufen über `bluemap.events`.
- **BlueMapAPI:** `WebApp.registerStyle(url)` und `WebApp.registerScript(url)` tragen Dateien aus einem Plugin ein. `BlueMapMap.getMarkerSets()` liefert die Marker-Sets einer Karte zum Schreiben. `AssetStorage` speichert Icons pro Karte.
- **Marker-Typen:** `poi` (Bild mit Label), `html` (beliebiges HTML), `line`, `shape`, `extrude` (3D-Fläche). Jeder Marker trägt `detail` (HTML im Popup), `classes` (CSS-Klassen), `min-distance`, `max-distance`, `listed` und `sorting`.
- **Übersetzung:** Die Webapp lädt `lang/settings.conf` und `lang/<locale>.conf` aus dem Webroot. Beide Dateien liegen dort als HOCON und lassen sich ersetzen. `de.conf` und `en.conf` sind vorhanden.
- **`index.html`:** Titel, Beschreibung, `theme-color`, `og:`-Tags und Favicon stehen dort. Das Plugin schreibt die Datei nur, wenn sie fehlt (siehe Abschnitt 3).

### Tabelle

| Anpassungswunsch                                           | Ohne Fork     | Wie                                                                                                                         | Grenzen                                                                                                                         |
| ---------------------------------------------------------- | ------------- | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Farben, Schrift, Rundungen                                 | ja            | CSS überschreibt die Variablen `--theme-bg`, `--theme-fg`, `--theme-switch-button-on` und weitere aus `scss/variables.scss` | Die Webapp kennt drei Themes (dunkel, hell, Kontrast). Das CSS muss alle drei überschreiben.                                    |
| Bedienelemente ausblenden                                  | ja            | CSS (`display: none` auf `.day-night-switch`, `.controls-switch`) oder JS über `appState.controls`                          | Die Klassennamen sind kein festes API und können sich bei einem Update ändern.                                                  |
| Elemente umsortieren, Steuerleiste verschieben             | teilweise     | CSS (`position`, `order`, Flexbox)                                                                                          | Die Struktur der Komponenten bleibt. Ein Element lässt sich verschieben, aber nicht in eine andere Komponente setzen.           |
| Eigene Elemente (Logo, Link, Kopfzeile)                    | ja            | JS erzeugt DOM-Elemente in `#app`                                                                                           | Die Elemente sind kein Teil von Vue. Sie reagieren nicht auf `appState`, außer das Skript beobachtet es selbst.                 |
| Eigenes Menü oder eigene Sidebar                           | eingeschränkt | JS baut ein eigenes Panel neben dem Menü                                                                                    | Das eingebaute Menü bleibt. Zwei Menüs nebeneinander wirken uneinheitlich. Schätzung: auf dem Telefon kaum brauchbar.           |
| Startposition, Zoom-Grenzen                                | ja            | `webapp.conf`                                                                                                               | keine                                                                                                                           |
| Marker, Bereiche, Linien                                   | ja            | `marker-sets` in der Map-Config, BlueMapAPI aus einem Plugin oder `live/markers.json`                                       | keine für die fünf Typen                                                                                                        |
| Eigene Marker-Icons                                        | ja            | `icon` ist eine URL. Icons aus dem Resource Pack liegen im Webroot oder im `AssetStorage`.                                  | BlueMap skaliert das Bild nicht. Ein 16×16-Icon braucht eine CSS-Klasse mit `width`, `height` und `image-rendering: pixelated`. |
| HTML-Marker, Popups mit eigenem HTML/CSS                   | ja            | `html` und `detail` nehmen HTML. `classes` setzt CSS-Klassen.                                                               | Kein JS im Marker selbst (innerHTML führt kein `<script>` aus). Verhalten kommt aus einem Skript in `scripts`.                  |
| Eigene Marker-Typen (zum Beispiel Cluster, animierte Wege) | eingeschränkt | Ein Skript erzeugt Objekte aus `window.BlueMap` und hängt sie an `mapViewer.markers`                                        | Die Klassen sind interne Module ohne stabile Schnittstelle.                                                                     |
| Sprachen                                                   | ja            | `lang/settings.conf` und eigene `lang/*.conf` im Webroot                                                                    | Die Texte gelten für die ganze Webapp. Nordtal-Begriffe aus `TermMessages` müssen von Hand in die Dateien.                      |
| Titel, Favicon, `og:`-Tags                                 | ja            | `index.html` im Webroot ändern                                                                                              | Ein Update mit `-g` oder ein gelöschtes `index.html` überschreibt die Datei.                                                    |
| Live-Daten aus einer eigenen API                           | ja            | Ein Skript ruft eine Nordtal-API auf und zeigt das Ergebnis. Oder ein Plugin schreibt die Daten als Marker.                 | Login und Cookies über Subdomains brauchen CORS und `SameSite`-Regeln in Steward.                                               |
| Discord-Login, Spielergruppen                              | eingeschränkt | Ein Skript fragt Steward nach der Sitzung und blendet Marker-Sets ein                                                       | Marker in `markers.json` sind öffentlich. Private Marker brauchen eine eigene, geschützte API.                                  |
| Komplett eigenes Layout                                    | nein          | nur mit Fork                                                                                                                |                                                                                                                                 |
| Eigene Vue-Komponenten im Menü                             | nein          | nur mit Fork                                                                                                                |                                                                                                                                 |
| Glyphs aus dem Resource Pack als Schrift                   | nein          | Glyphs sind Texturen, keine Webschrift                                                                                      | Mit Fork oder Overlay: als `<img>` oder CSS-Hintergrund, nicht als Text.                                                        |

### Was erst mit Fork der Webapp möglich wird

**Fakt** (Quellen: `common/webapp/package.json`, `vite.config.js`, Verzeichnis `src/`):

- **Framework:** Vue 3.5 für die Oberfläche, three.js 0.186 für die 3D-Darstellung, vue-i18n für Texte, hammerjs für Touch.
- **Build:** Vite 6 mit `@vitejs/plugin-vue` und Sass. `base: './'`, also relativ zum Webroot.
- **Struktur:**
  - `src/App.vue` setzt die Oberfläche zusammen: `ControlBar`, `MainMenu`, `ZoomButtons` und `FreeFlightMobileControls`.
  - `src/components/ControlBar/` enthält die Steuerleiste oben: Menü-Knopf, Karten-, Marker- und Spielerknöpfe, Tag/Nacht, Ansicht, Position und Kompass.
  - `src/components/Menu/` enthält die Sidebar (`SideMenu`, `MainMenu`) mit Karten, Marker-Sets und Einstellungen.
  - `src/components/Controls/` enthält Zoom und Free-Flight auf dem Telefon.
  - `src/scss/variables.scss` hält die Theme-Variablen, `global.scss` die Grundstile, `markers.scss` die Darstellung der Marker und Popups.
  - `src/js/` hält die Logik ohne Vue-Komponenten: `BlueMapApp.js` (Einstellungen, Karten, Marker-Dateien, Adresse), `MapViewer.js` (three.js-Szene), `map/` (Kacheln, Loader, Shader), `markers/` (alle Marker-Typen) und `controls/` (Steuerung).
  - Umfang: 2 622 Zeilen in Vue und SCSS, 8 283 Zeilen JS in `src/js`.
- **Kopplung:** `src/js` importiert aus Vue nur `reactive` (in `MapViewer.js`, `Marker.js`, `MarkerSet.js`, `Map.js`, den Controls und `BlueMapApp.js`). Die 3D-Darstellung hängt also nicht an Vue-Komponenten, nur an Vues Reaktivität.

Mit einem Fork kommen dazu:

- Ein eigenes Layout: Kopfzeile, feste Sidebar am Desktop, Bottom-Sheet am Telefon (Steward ist zuerst für das Telefon gebaut).
- Eigene Komponenten: Liste der Ballonstationen, Suche, Spielerprofil, Legende.
- Das Nordtal-Design als Quelle statt als Überschreibung: Farben aus denselben Tokens wie Steward und eine eigene Schrift statt Quicksand.
- Eigene Marker-Darstellung in `markers/` und `markers.scss`, zum Beispiel Cluster, Pixel-Icons in fester Größe und Popups als Komponente.
- Nordtal-Daten in der Oberfläche: Discord-Login über Steward, Spielergruppen, Gebiete, Ballonstationen und Gräber, live über eine eigene API.

## 3. Empfehlung

### Webapp allein tauschen, ohne Plugin-Fork

**Fakt** (Quelle: `common/src/main/java/de/bluecolored/bluemap/common/WebFilesManager.java`, `BlueMapService.createOrUpdateWebApp`, `Plugin.java` Zeile 304, `BlueMapCLI.java`):

- Das Plugin entpackt seine eingebaute `webapp.zip` nur, wenn `index.html` im Webroot fehlt (`filesNeedUpdate()` prüft nur das).
- Das CLI tut es auch mit `-g` (`generate-webapp`).
- Mit `webapp.conf` `enabled: false` schreibt das Plugin gar nichts in den Webroot.
- `settings.json` schreibt das Plugin bei jedem Start neu, solange `update-settings-file: true` gilt.
- Der Release-Build legt die Webapp als eigenes Artefakt `bluemap-<version>-webapp.zip` ab (`common/build.gradle.kts`, Task `release`).

Ein Fork nur der Webapp ist damit vorgesehen. Nordtal baut die Webapp selbst, legt sie in den Webroot (oder lässt Caddy sie aus dem eigenen Image ausliefern) und nutzt das unveränderte Plugin von Modrinth. Das Plugin muss nicht geforkt werden. **Fakt.**

**Bedingung:** Webapp und Plugin müssen dieselbe Version haben. Kachelformat (`.prbm`), `settings.json`, `textures.json` und Live-Endpunkte gehören zu einer Version. Die Webapp prüft das nicht selbst. Ein Plugin-Update ohne passende Webapp kann die Karte brechen. **Schätzung**, auf Basis der Commits zu Kacheln und Server-Sent Events in 5.2x.

### Varianten im Vergleich

Alle Aufwände sind **Schätzungen** für eine Person, die Vue oder React kennt.

| Variante                                                                         | Was sie liefert                                                                             | Aufwand zum Start                                     | Pflege je Upstream-Release                                                  | Risiko                                                                                |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| **A. Overlay** (CSS, JS, Marker, `lang/`)                                        | Nordtal-Farben, Logo, ausgeblendete Elemente, eigene Marker mit Pixel-Icons, deutsche Texte | 2 bis 4 Tage, dazu 2 bis 4 Tage für das Marker-Plugin | 0,5 h: Klassennamen prüfen, Screenshots vergleichen                         | Klassennamen ändern sich unangekündigt                                                |
| **B. Webapp-Fork als Patch-Set**                                                 | Alles aus A, dazu eigenes Layout, eigene Komponenten, Login und Live-Daten                  | A plus 5 bis 10 Tage                                  | 1 bis 3 h: Patches neu anwenden, bauen, Screenshots                         | Konflikte in `App.vue` und `components/`. Upstream änderte dort in 12 Monaten nichts. |
| **C. Plugin-Fork**                                                               | nichts über B hinaus für die Oberfläche                                                     | B plus Build von Plugin und CLI                       | 2 bis 6 h: Gradle, Java, Shading, alle Plattformen                          | Eigenes Plugin-Jar statt Modrinth, steward-agent müsste es aus GHCR holen             |
| **D. Eigene Oberfläche** (React wie Steward, `src/js` von upstream)              | Volle Kontrolle, gleicher Stack wie Steward                                                 | 3 bis 6 Wochen                                        | 2 bis 4 h: `src/js` folgt upstream, die Oberfläche ruft interne Klassen auf | `src/js` ist kein API. Vues `reactive` bleibt als Abhängigkeit.                       |
| **E. Eigene Oberfläche ohne `src/js`** (eigener three.js-Viewer für die Kacheln) | Volle Kontrolle                                                                             | 2 bis 4 Monate                                        | hoch: Kachelformat, Shader, Texturen, Live-Daten nachbauen                  | Das Format ist nicht dokumentiert. Nicht realistisch.                                 |

**Empfehlung:** erst A, dann B, wenn A nicht reicht. C und E nicht. D nur, wenn die Karte Teil von Steward werden soll.

- **Schritt 1 (A):** BlueMap-Plugin unverändert auf dem SMP. Marker kommen aus einem Nordtal-Plugin über die BlueMapAPI. Dazu Overlay-CSS, Overlay-JS, Icons aus dem Resource Pack und `lang/` aus `season-2`. Damit sind die Marker, Popups, Farben und das Logo erledigt. Das sind die meisten Wünsche aus dem Auftrag.
- **Schritt 2 (B), wenn nötig:** Eigenes Layout als Patch-Set auf einen Upstream-Tag. Die Patches ändern `App.vue`, `components/` und `scss/`. `src/js` bleibt unverändert. Das hält die Konfliktfläche dort, wo upstream kaum arbeitet.
- **D statt B**, wenn Steward die Karte einbetten soll: Ein React-Paket importiert `src/js` aus dem gepinnten Upstream-Tag und ersetzt nur die Vue-Komponenten. Das spart den Vue-Stack in der Oberfläche, aber nicht `vue` als Paket.

## 4. Architektur für Nordtal

### Betrieb

| Frage           | Empfehlung                                                                              | Begründung                                                                                                                                                                                                                                                                                   |
| --------------- | --------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Plugin oder CLI | Plugin auf `smp`                                                                        | Nur das Plugin liefert Live-Spieler und Server-Sent Events (`live/players.json`, `live/markers.json`). Marker aus der BlueMapAPI brauchen das Plugin im selben Prozess. Das Plugin rendert in eigenen Threads (README).                                                                      |
| Speicher        | Datei-Storage auf eigenem Volume                                                        | Caddy liefert Dateien statisch aus. SQL-Storage braucht `sql.php` (PHP) oder BlueMaps Webserver, also kein statisches Hosting (Wiki `ExternalWebserversSQL.md`). PostgreSQL ist unterstützt (`PostgreSQLCommandSet.java`), aber die Kacheln würden die Datenbank und ihre Backups aufblähen. |
| Backup          | Volume `bluemap-web` nicht sichern                                                      | Die Kacheln lassen sich aus der Welt neu rendern. Gesichert werden nur die Configs.                                                                                                                                                                                                          |
| Webserver       | Caddy, BlueMaps Webserver nur für `live/`                                               | Wiki `ExternalWebserversFile.md` nennt genau dieses Muster mit Caddy-Beispiel.                                                                                                                                                                                                               |
| Render-Last     | `render-thread-count` in `core.conf` klein halten                                       | Schätzung: das SMP hat 6 GB Heap. BlueMap teilt sich Host und CPU mit den Servern.                                                                                                                                                                                                           |
| Packs           | Nordtal-Resource-Pack, Terralith und Dungeons and Taverns nach `plugins/BlueMap/packs/` | Wiki `ResourcePacks.md`: Ohne die Datapacks fehlen Biome und Strukturen in den Farben. Ohne das Resource Pack fehlen eigene Modelle.                                                                                                                                                         |
| CLI             | als zweite Option, für Testwelten und Agenten                                           | Das CLI rendert eine Welt im eigenen Container ohne Server (`-r`, `-u` beobachtet Änderungen, `-w` startet den Webserver). Für Produktion fehlen dann Live-Spieler.                                                                                                                          |

Das Plugin kommt wie PacketEvents über steward-agent von Modrinth: Projekt-ID `swbUV1cr`, Slug `bluemap`, Loader `paper` (**Fakt**, Modrinth-API). Dazu ein Eintrag `bluemap?` im Label `eu.nordtal.plugins` von `smp` und ein Feld `bluemap-project` in `RunSpec` (**Schätzung**, nach dem Muster von `coreprotect-project`).

### Ablauf

```
             Spieler (Browser)
                    │ https://map.nordtal.eu
                    ▼
                  Caddy ───────────────── /maps/*/live/* ──────► smp:8100 (BlueMap-Webserver)
                    │  statisch: /srv/map (read only)                    │
                    ▼                                                    │
   Volume bluemap-web ◄── schreibt Kacheln ─── BlueMap-Plugin (im smp) ◄─┘
   (index.html, nordtal/*, lang/*, maps/<id>/tiles)  ▲
                                                     │ BlueMapAPI (MarkerSet, POIMarker, ShapeMarker)
                                     Nordtal-Feature im smp-Plugin
                                                     ▲ liest
                                                 Postgres ◄── Steward (Verwaltung der Orte)
```

### Caddy

`prototype/deploy/Caddyfile.map` enthält den Site-Block. Er folgt dem Caddy-Beispiel aus dem BlueMap-Wiki: `.prbm` und `textures.json` als `.gz` mit `Content-Encoding: gzip`, fehlende Kacheln als `204` und `live/` als Reverse Proxy auf `smp:8100`. Die Kacheln kommen damit statisch von Caddy. **Fakt** (Wiki). Der Block wird nicht getestet (siehe Abschnitt 6).

Dafür braucht es:

- `caddy` mountet das Volume des Webroots read only.
- `smp` und `caddy` teilen das Netz `default` (**Fakt**, `compose.yml`).
- `CaddyPolicyTest` und die Header-Regeln von Steward gelten auch für den neuen Block. Eine CSP für die Karte braucht `'unsafe-eval'` nicht (Schätzung). Das Wiki-Beispiel in `webserver.conf` nennt es trotzdem, das ist zu prüfen.
- Der Ordner `smp/plugins/BlueMap` liegt im Volume `mc-smp-plugins`. Der Webroot (`bluemap/web` relativ zum Server) liegt in `mc-smp`. Ein eigenes Volume für den Webroot braucht `webroot` in `webapp.conf` und `webserver.conf` auf einen eigenen Mount.

### Marker-Quelle und Steward

- **Statische Marker:** Für den Anfang reichen `marker-sets` in der Map-Config. Sie werden in `season-2` versioniert.
- **Dynamische Marker:** Ein Feature `map` im Paper-Plugin `smp` hängt sich mit `BlueMapAPI.onEnable` ein. Es liest Ballonstationen (`travel`), Schutzgebiete (`protect`) und Gräber (`grave`) aus Postgres und schreibt sie als `MarkerSet`. Die Abhängigkeit `bluemap-api` ist `compileOnly` und optional (`required: false` in `paper-plugin.yml`), damit das SMP ohne BlueMap startet. **Schätzung.**
- **Verwaltung in Steward:** Steward schreibt Orte in eine Tabelle. Das Plugin liest sie bei jedem Start und auf ein Signal aus `Channel` neu, wie es die Regel „Signals“ in `CONVENTIONS.md` verlangt. **Schätzung.**
- **BlueMapAPI-Marker liegen nur im Speicher.** Mit `write-markers-interval` in `plugin.conf` schreibt das Plugin sie zusätzlich nach `live/markers.json` im Storage (**Fakt**, `plugin.conf`). Dann liefert Caddy auch die Marker statisch aus, falls der Live-Proxy ausfällt.
- **Glyphs als Icons:** Die Texturen sind Pixel-Art (Ballon 16×16, Badges 7×7, Logo 134×33, **Fakt**, PNG-Header). Ein Gradle-Task kopiert die gewählten PNGs aus `resource-pack/src/assets/nordtal/textures/**` in den Webroot. Ihre Namen kommen aus `glyphs.json`. BlueMap skaliert Icons nicht, also setzt die CSS-Klasse `nordtal-icon` Größe und `image-rendering: pixelated`.
- **Login:** Die Karte ist öffentlich. Wenn Marker nur für eingeloggte Spieler gelten sollen, liefert Steward sie über einen eigenen Endpunkt mit Discord-Sitzung, und das Overlay-Skript zeigt sie. Dafür braucht Steward CORS für `map.nordtal.eu` oder einen Pfad unter `STEWARD_HOST`. `WebAuthnKeyTest` nutzt schon den Origin `https://bluemap.nordtal.eu`. Der Name weicht von `map.nordtal.eu` ab (siehe Abschnitt 7).

## 5. Monorepo oder separat

**Empfehlung:** alles in `season-2`, als ein neues Modul `map/`. Kein Fork auf GitHub, kein Submodul, kein eigenes Repo.

| Weg                                                           | Doppelter Code                                                            | Bewertung                                                                                                                                                                                                                           |
| ------------------------------------------------------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Overlay im Monorepo (`map/` mit CSS, JS, `lang/`, Map-Config) | keiner                                                                    | **Empfohlen für Schritt 1.** Die Dateien sind klein und nutzen Tokens, Icons und API-Typen direkt aus dem Repo.                                                                                                                     |
| Gradle-Modul baut die Webapp aus Upstream-Tag plus Patches    | keiner, Upstream wird nicht eingecheckt                                   | **Empfohlen für Schritt 2.** Der Task lädt das Archiv des Tags (`v5.29`) mit fester Prüfsumme, wendet `map/patches/*.patch` an und baut mit npm. Das Ergebnis geht ins Caddy-Image oder in ein eigenes Image `ghcr.io/nordtal/map`. |
| Git-Submodul auf einen Fork                                   | keiner                                                                    | Nicht empfohlen. Ein Fork auf GitHub ist ein zweites Repo mit eigener Pflege. Submodule sind in der Release-Pipeline und für Agenten fehleranfällig.                                                                                |
| Separates Repo mit versionierten Artefakten                   | Tokens und Icons müssten veröffentlicht und dort wieder eingelesen werden | Nicht empfohlen. Es lohnt sich nur, wenn andere Projekte die Karte nutzen.                                                                                                                                                          |
| Upstream-Quellcode eingecheckt (Vendor wie Spec)              | 10 900 Zeilen fremder Code                                                | Nicht empfohlen. Jedes Update ist ein großer Diff, und die Regeln in `CONVENTIONS.md` (Kommentare, Striche) müssten Ausnahmen bekommen.                                                                                             |

**Gemeinsam genutzt ohne Kopie:**

- **Design-Tokens:** `:steward:generateApiTypes` schreibt `brand.gen.css` aus `Tone.BRAND`. Derselbe Task (oder ein zweiter neben ihm) schreibt `map/build/nordtal-theme.css` mit den BlueMap-Variablen (`--theme-bg` und weitere) aus denselben Quellen. Das Overlay oder der Fork importiert die erzeugte Datei. Die Werte in `steward/frontend/src/index.css` stehen heute als CSS-Literale. Eine gemeinsame Quelle verlangt, dass sie in eine Datei oder Klasse wandern, aus der beide Ausgaben entstehen. **Schätzung.**
- **Glyphs und Icons:** Ein Task in `map/` liest `glyphs.json` und kopiert die gewählten Texturen. Wie `pack-host` hängt er vom Build des Resource Packs ab.
- **API-Typen:** Wenn die Karte Steward-Daten liest, erzeugt `generateApiTypes` auch die Typen für die Karte. Bei Variante D ist das ein TypeScript-Import, bei A und B eine JSDoc-Typdatei.
- **Texte:** Ein Task erzeugt `lang/de.conf` und `lang/en.conf` aus dem Upstream-Text, überschrieben mit Begriffen aus `TermMessages`. So bleibt jeder Begriff an einer Stelle.

**Auslieferung:** Ein `v*`-Tag baut in `.github/workflows/release.yml` das Image `caddy` mit `/srv/map-app` (Webapp und Overlay) oder ein eigenes Image. steward-agent rollt es aus wie jedes andere Image. Die Kacheln bleiben im Volume. Die Version von BlueMap steht an einer Stelle, zum Beispiel im Versionskatalog. Ein Test prüft, dass Plugin-Version und Webapp-Tag gleich sind, nach dem Muster von `PlatformTest`. **Schätzung.**

### Bauen und Testen durch Agenten

`prototype/test/run.sh` und `prototype/test/screenshot.mjs` beschreiben die Schleife:

1. `./gradlew :cli:release` im BlueMap-Checkout baut das CLI mit eingebauter Webapp.
2. Das CLI rendert eine kleine Testwelt (`render-mask` 512×512 Blöcke) mit `-r -g`.
3. Das Overlay kommt in den Webroot, danach `-s --markers`.
4. Der Fork-Patch wird angewendet, `npm ci` und `npm run build` laufen, und `dist/` kommt über eine Kopie des Webroots.
5. Ein statischer Server liefert beide Webroots aus (`client-decompression: true`, damit kein Gzip-Header nötig ist).
6. Playwright mit dem vorinstallierten Chromium öffnet beide und speichert je einen Screenshot für Desktop (1280×800) und Telefon (390×844).

Dazu gilt:

- **Testwelt:** Es gibt keine kleine Welt im Repo. Eine Testwelt entsteht reproduzierbar aus Seed `1837371427` (Default in `compose.yml`) mit einem einmaligen Paper-Start und einem kleinen Vorgenerier-Radius. Sie kann auch als ZIP-Fixture eingecheckt werden. Schätzung: 5 bis 20 MB für 512×512 Blöcke.
- **Mojang-Client:** BlueMap lädt für Texturen den Minecraft-Client von Mojang, sobald `accept-download: true` gesetzt ist. Damit gilt die EULA. In CI braucht das Netz Zugang zu `piston-data.mojang.com`, oder der Client liegt im Cache.
- **WebGL ohne GPU:** Chromium braucht SwiftShader (`--use-angle=swiftshader`). Ob three.js 0.186 damit rendert, ist nicht gemessen.

## 6. Prototyp

### Was lief

| Schritt                                       | Ergebnis                                                                                   | Art      |
| --------------------------------------------- | ------------------------------------------------------------------------------------------ | -------- |
| BlueMap klonen (`--depth 50`)                 | 1,1 s                                                                                      | Gemessen |
| BlueMapAPI klonen (`--depth 20`)              | 0,7 s                                                                                      | Gemessen |
| BlueMap-Wiki und BlueMapAPI-Wiki klonen       | erfolgreich                                                                                | Gemessen |
| Modrinth-API lesen                            | erfolgreich                                                                                | Gemessen |
| GitHub-REST-API ohne Token (`api.github.com`) | keine Daten                                                                                | Gemessen |
| `npm ci` und `npm run build` der Webapp       | **nicht ausgeführt**: Die Freigabe der Session lehnte den Befehl ab („Code from External“) | Gemessen |
| `./gradlew` für CLI und Plugin                | nicht versucht, gleicher Grund                                                             |          |
| Test-Map rendern                              | nicht versucht: braucht das CLI und eine Welt                                              |          |
| Screenshots                                   | keine: es gibt keinen Build                                                                |          |

Bauzeiten gibt es deshalb keine. **Schätzung:** `npm ci` 20 bis 60 s, `vite build` 10 bis 30 s, `./gradlew :cli:release` beim ersten Lauf 3 bis 8 min (Gradle-Download, Abhängigkeiten, npm im Gradle-Build). Die Webapp hat 5 Laufzeit- und 5 Entwicklungsabhängigkeiten (`package.json`).

### Dateien

```
research/bluemap/prototype/
├── overlay/              Variante ohne Fork
│   ├── nordtal.css       Steward-Farben, Popups, Pixel-Icons, ausgeblendete Schalter
│   ├── nordtal.js        Logo mit Link unten rechts, Zoom-Knöpfe aus
│   ├── webapp.conf       scripts, styles, Startposition
│   ├── marker-sets.conf  eine Ballonstation (POI mit Icon) und ein Schutzgebiet (Shape)
│   ├── lang-settings.conf  nur Deutsch und Englisch, Deutsch als Standard
│   ├── balloon.png       aus resource-pack/.../item/balloon_envelope.png (16×16)
│   └── logo.png          aus resource-pack/.../assets/logo.png
├── fork/
│   └── 0001-nordtal-header.patch  Patch auf BlueMap master 12823c3
├── deploy/
│   ├── Caddyfile.map     Site-Block für map.nordtal.eu
│   └── compose.map.yml   Skizze für den CLI-Betrieb
├── test/
│   ├── run.sh            Build, Render, beide Varianten, Screenshots
│   ├── screenshot.mjs    Playwright
│   └── nordtal.conf      Map-Config der Testwelt
└── screenshots/          leer
```

**Variante ohne Fork:** `nordtal.css` setzt die Theme-Variablen aller drei Themes auf die Werte aus `steward/frontend/src/index.css`. Die Datei färbt Popups mit Rahmen in `--brand` und blendet Tag/Nacht und Ansichtswahl aus. Über die Klasse `nordtal-icon` zeichnet sie 16×16-Icons doppelt groß und pixelig. `nordtal.js` hängt das Nordtal-Logo als Link an und schaltet die Zoom-Knöpfe ab. `marker-sets.conf` zeigt einen POI-Marker mit Icon aus dem Resource Pack und HTML-Popup sowie ein Gebiet als `shape` in der Brand-Farbe.

**Variante mit Fork:** Der Patch ändert 5 Dateien (+86/−8 Zeilen):

- Neu ist `components/Nordtal/NordtalHeader.vue`, eine Kopfzeile mit Logo, Kartenwahl als Tabs und Spielerzahl.
- `App.vue` bindet die Kopfzeile ein.
- `ControlBar.vue` rückt die Steuerleiste unter die Kopfzeile.
- `variables.scss` setzt das dunkle Theme auf die Steward-Farben und `system-ui` statt Quicksand.

`src/js` bleibt unberührt.

### Nachholen

Mit Freigabe für fremden Code läuft `prototype/test/run.sh` mit `BLUEMAP=<Checkout von v5.29>`, `WORLD=<Weltordner>` und `BLUEMAP_ACCEPT_DOWNLOAD=true`. Das Skript misst die Bauzeiten mit `time` und legt vier PNGs in `screenshots/` ab. Ohne Welt lassen sich zumindest der Webapp-Build und die Oberfläche ohne Kacheln prüfen: der Vite-Dev-Server mit dem Proxy auf die Demo-Karte `https://bluecolored.de/bluemap` aus `vite.config.js`.

## 7. Offene Fragen und Risiken

- **Freigabe für den Build:** Diese Session durfte BlueMap nicht bauen. Für Agenten braucht `season-2` eine Regel, die `npm ci`, Gradle und das CLI für den gepinnten BlueMap-Tag erlaubt, oder einen CI-Job, der das übernimmt.
- **Domain:** `WebAuthnKeyTest` nutzt `https://bluemap.nordtal.eu`, der Auftrag nennt `map.nordtal.eu`. Welcher Name gilt, und muss Steward ihn als WebAuthn-Origin kennen?
- **Versionsgleichheit:** Webapp-Fork und Plugin müssen dieselbe Version haben. steward-agent installiert Plugins in der neuesten Version. Das widerspricht einem gepinnten Webapp-Tag. Entweder pinnt steward-agent BlueMap als Ausnahme, oder der Webapp-Build folgt jeder neuen Plugin-Version automatisch.
- **Plugin oder CLI:** Das Plugin rendert auf dem SMP-Host. Wie viel CPU und Heap das bei einer großen Welt mit Terralith kostet, ist nicht gemessen. Das CLI im eigenen Container trennt die Last, verliert aber Live-Spieler und API-Marker.
- **Weltpfad bei Paper 26.x:** Wo Paper 26.2 Nether und End ablegt und welche Map-Configs BlueMap dafür erzeugt, ist nicht geprüft.
- **Datenschutz:** Live-Positionen der Spieler sind öffentlich. `WebApp.setPlayerVisibility` und `plugin.conf` (`hide-*`-Optionen) steuern das. Ob Nordtal Spieler zeigt, ist eine Entscheidung, keine technische Frage.
- **Klassennamen im Overlay:** `.control-bar`, `.day-night-switch`, `.bm-marker-poi-icon` und die anderen sind kein API. Ein Screenshot-Vergleich bei jedem Update fängt Brüche ab.
- **`index.html` im Overlay:** Ein Update mit `-g` überschreibt Titel und `og:`-Tags. Im Fork gehören sie in den Patch.
- **WebGL in CI:** Ob Chromium mit SwiftShader die Karte rendert, ist nicht gemessen. Ohne WebGL zeigt die Webapp nur eine Fehlerseite.
- **Mojang-Download:** BlueMap braucht den Minecraft-Client für Texturen. In CI braucht das Netzzugang zu Mojang oder einen Cache.
- **Wording-Regeln:** Overlay-Dateien und Patches in `season-2` fallen unter `noDashPunctuation` und die Kommentarregeln. Upstream-Code nicht, solange er nicht eingecheckt ist.

## Quellen

- BlueMap: <https://github.com/BlueMap-Minecraft/BlueMap> (Tag `v5.29`, `master` `12823c3`). Die wichtigsten Dateien: `LICENSE`, `common/webapp/package.json`, `common/webapp/vite.config.js`, `common/webapp/src/js/BlueMapApp.js`, `common/src/main/java/de/bluecolored/bluemap/common/WebFilesManager.java`, `common/src/main/java/de/bluecolored/bluemap/common/BlueMapService.java`, `common/src/main/java/de/bluecolored/bluemap/common/config/WebappConfig.java`, `common/src/main/resources/de/bluecolored/bluemap/config/*.conf`, `implementations/paper/build.gradle.kts`, `implementations/cli/src/main/java/de/bluecolored/bluemap/cli/BlueMapCLI.java`, `core/src/main/java/de/bluecolored/bluemap/core/storage/sql/commandset/PostgreSQLCommandSet.java` und `Dockerfile`.
- BlueMapAPI: <https://github.com/BlueMap-Minecraft/BlueMapAPI> (Tag `v2.8.2`), `WebApp.java`, `BlueMapMap.java`, `markers/*.java`.
- BlueMapAPI-Wiki: <https://github.com/BlueMap-Minecraft/BlueMapAPI/wiki>.
- BlueMap-Wiki: <https://github.com/BlueMap-Minecraft/BlueMapWiki>, veröffentlicht unter <https://bluemap.bluecolored.de/wiki/>. Genutzt: `wiki/webserver/ExternalWebserversFile.md`, `wiki/webserver/ExternalWebserversSQL.md`, `wiki/customization/Markers.md`, `wiki/customization/ResourcePacks.md`, `wiki/getting-started/ServerNetworks.md`, `community/Customisation.md`, `community/LogIn.md`.
- Modrinth: <https://api.modrinth.com/v2/project/bluemap> und `.../version?loaders=["paper"]`.
- season-2: `compose.yml`, `deploy/caddy/Dockerfile`, `deploy/minecraft/entrypoint.sh`, `steward-agent/.../config/RunSpec.java`, `steward/frontend/src/index.css`, `steward/frontend/src/brand.gen.css`, `resource-pack/glyphs.json`, `smp/README.md`, `smp/src/main/resources/paper-plugin.yml`, `CONVENTIONS.md`.
