# bridge-mod

A Fabric mod that runs only in the client. The Nordtal Designer reaches it over a WebSocket on
`127.0.0.1` to reload the resource packs and to show a texture in chat, the actionbar, the title, a
boss bar, a chest title, the hand or a block placed in the client only. It works in singleplayer and
on servers, and it needs Fabric Loader but not the Fabric API. Every release attaches it as
`nordtal-bridge-<version>.jar`.

| File                  | What it does                                                               |
| --------------------- | -------------------------------------------------------------------------- |
| `BridgeMod`           | The client entry point: reads the settings file and starts the server.     |
| `BridgeSettings`      | Reads port and token from the settings file.                               |
| `BridgeServer`        | The WebSocket server, on Netty from the client's own libraries.            |
| `Session`             | One connection: the hello with the token, then one reply per request.      |
| `Request`             | Reads and checks a request's JSON.                                         |
| `ClientActions`       | Carries a request out on the client thread.                                |

## Settings

The app writes `config/nordtal-bridge.properties` into the instance before the client starts:

```properties
port=29415
token=<at least 16 characters>
```

Without the file the mod stays off and logs that once. The port is read when the client starts, and
the token again for every connection, so the app can change the token while the client runs.

## Protocol

Every frame is one JSON object as text. The first frame of a connection is the hello:

```json
{ "op": "hello", "token": "<the token of the file>" }
```

The mod answers each frame with one reply that echoes the request's `id` when it has one:

```json
{ "id": 7, "ok": true }
{ "id": 7, "ok": false, "error": "give needs creative mode." }
```

A wrong token, or any frame before the hello, gets a reply with `"ok": false` and then a close with
status 1008. A reply comes once the client has carried the request out; for `reload` that is once the
packs are reloaded. A text component (`text`, `subtitle`) is the JSON that `/tellraw` takes.

| `op`        | Fields                                                     | What the client does                                          |
| ----------- | ---------------------------------------------------------- | ------------------------------------------------------------- |
| `reload`    |                                                            | Reloads every resource pack.                                  |
| `chat`      | `text`                                                     | Adds the component to the chat.                               |
| `actionbar` | `text`                                                     | Shows the component above the hotbar.                         |
| `title`     | `text`, `subtitle` optional                                | Shows the title with the default times.                       |
| `bossbar`   | `text` (absent removes), `color`, `overlay`, `progress`    | Shows the mod's one boss bar; `white`, `progress` and 1 by default. |
| `chest`     | `text`, `rows` from 1 to 6, 3 by default                   | Opens an empty chest under the title, in the client only.     |
| `give`      | `item` as `/give` writes it, `count` from 1 to 99          | Puts the item into the selected hotbar slot, in creative mode only. |
| `block`     | `block` as `/setblock` writes it, `pos` as `[x, y, z]`     | Places the block state in the client only; without `pos`, on the face under the crosshair. |
| `clear`     |                                                            | Removes the boss bar and the title, and puts back every block it placed. |

`color` is one of `pink`, `blue`, `red`, `green`, `yellow`, `purple` and `white`, and `overlay` one
of `progress`, `notched_6`, `notched_10`, `notched_12` and `notched_20`. A server sends its own
state of a block again when the chunk reloads, which ends a client block.

## Testing

`sh gradlew :bridge-mod:test` runs the protocol, the settings and the server against the JDK's
WebSocket client, without Minecraft. In a client, write the settings file into
`bridge-mod/run/config/`, start `sh gradlew :bridge-mod:runClient`, and send frames with any
WebSocket client, such as `websocat ws://127.0.0.1:29415/`.
