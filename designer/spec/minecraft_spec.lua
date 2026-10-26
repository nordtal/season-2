local minecraft = require("minecraft")
local dkjson = require("dkjson")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

-- Like json.encode, with sorted keys
local function encode(value) return dkjson.encode(value, { keyorder={ "color", "font", "text" } }) end

local star = { text="\u{F0000}", font="nordtal:badges" }

local function glyph(code) return { kind="glyph", entry={ code=code or "F0000" }, font={ font="minecraft:default" } } end

describe("minecraft", function()
  it("counts the length of a command as Minecraft does, a character beyond the BMP as two", function()
    assert.equal(1, minecraft.length("a"))
    assert.equal(1, minecraft.length("é"))
    assert.equal(2, minecraft.length("\u{F0000}"))
    assert.equal(256, minecraft.LIMIT)
  end)

  it("writes each preview as the vanilla commands that show the same", function()
    local json = encode(star)
    assert.same({ "/tellraw @s " .. json }, minecraft.commands({ op="chat", text=star }, encode))
    assert.same({ "/title @s actionbar " .. json }, minecraft.commands({ op="actionbar", text=star }, encode))
    assert.same({ "/title @s title " .. json }, minecraft.commands({ op="title", text=star }, encode))
    assert.same({ "/bossbar remove nordtal:preview", "/bossbar add nordtal:preview " .. json,
                  "/bossbar set nordtal:preview players @s" }, minecraft.commands({ op="bossbar", text=star }, encode))
    assert.same({ "/give @s chest[custom_name=" .. json .. "]" }, minecraft.commands({ op="chest", text=star }, encode))
    assert.same({ "/give @s minecraft:apple" }, minecraft.commands({ op="give", item="minecraft:apple" }, encode))
    assert.same({ "/setblock ^ ^ ^2 minecraft:stone" }, minecraft.commands({ op="block", block="minecraft:stone" }, encode))
  end)

  it("writes a text that is a bare string as an object, which Aseprite's json.encode writes", function()
    local function tables(value) return type(value) == "table" and encode(value) or nil end
    assert.same({ '/tellraw @s {"text":"\u{F0000}"}' }, minecraft.commands({ op="chat", text="\u{F0000}" }, tables))
  end)

  it("asks for a glyph's previews to be rendered first, then shows it alone and in each place that draws it", function()
    local list, render = minecraft.previews(glyph(), nil, nil, tr)
    assert.same({}, list)
    assert.is_true(render)

    local menu, board = { text="menu" }, { text="board" }
    list, render = minecraft.previews(glyph(), { glyph=star, menu=menu, board=board }, nil, tr)
    assert.is_false(render)
    assert.same({
      { label="minecraft.chat", requests={ { op="chat", text=star } } },
      { label="minecraft.actionbar", requests={ { op="actionbar", text=star } } },
      { label="minecraft.title", requests={ { op="title", text=star } } },
      { label="minecraft.bossbar", requests={ { op="bossbar", text=star } } },
      { label="minecraft.chest", requests={ { op="chest", text=star } } },
      { label="minecraft.menu", requests={ { op="chest", text=menu, rows=3 } } },
      { label="minecraft.board", requests={ { op="chat", text=board } } },
    }, list)
  end)

  it("gives each state of an item, and places or gives the block or item of an override", function()
    local definition = { item="nordtal:lamp", states={ { label="strings[0]=lit", give="a" }, { label="", give="b" } } }
    assert.same({
      { label="minecraft.give strings[0]=lit", requests={ { op="give", item="a" } } },
      { label="minecraft.give minecraft.plain", requests={ { op="give", item="b" } } },
    }, (minecraft.previews({ kind="item", entry={ id="nordtal:lamp" } }, nil, definition, tr)))
    assert.same({}, (minecraft.previews({ kind="item", entry={ id="nordtal:other" } }, nil, definition, tr)))

    assert.same({ { label="minecraft.place minecraft:stone", requests={ { op="block", block="minecraft:stone" } } } },
      (minecraft.previews({ kind="override", entry={ block="minecraft:stone" } }, nil, nil, tr)))
    assert.same({ { label="minecraft.give minecraft:apple", requests={ { op="give", item="minecraft:apple" } } } },
      (minecraft.previews({ kind="override", entry={ item="minecraft:apple" } }, nil, nil, tr)))
    assert.same({}, (minecraft.previews({ kind="override", entry={} }, nil, nil, tr)))
    assert.same({}, (minecraft.previews(nil, nil, nil, tr)))
  end)

  it("offers each preview as commands to copy without Fabric, and one too long for the chat without a copy", function()
    local long = { text=string.rep("x", 300) }
    local rows = minecraft.rows({ liveSync=true, previews={
      { label="chat", requests={ { op="chat", text=star } } },
      { label="long", requests={ { op="chat", text=long } } },
    } }, tr, encode)
    local command = "/tellraw @s " .. encode(star)
    assert.same({
      { text="minecraft.instance", value="minecraft.no_instance", field={ minecraft="choose" } },
      { text="minecraft.live_sync", value=true, field={ minecraft="live" }, kind="switch" },
      { text="minecraft.install_pack", field={ minecraft="pack" }, kind="button" },
      { text="minecraft.previews", value="minecraft.copy" },
      { value="chat" },
      { value=command, field={ minecraft="copy", text=command } },
      { value="long" },
      { value="minecraft.too_long " .. minecraft.length("/tellraw @s " .. encode(long)) },
    }, rows)
  end)

  it("shows the bridge with Fabric, and each preview as a button that sends it", function()
    local requests = { { op="give", item="minecraft:apple" } }
    local rows = minecraft.rows({ instance={ instance="/i", fabric=true }, liveSync=false, bridge="connected",
                                  previews={ { label="apple", requests=requests } }, render=false }, tr, encode)
    assert.same({
      { text="minecraft.instance", value="/i", field={ minecraft="choose" } },
      { text="minecraft.live_sync", value=false, field={ minecraft="live" }, kind="switch" },
      { text="minecraft.install_pack", field={ minecraft="pack" }, kind="button" },
      { text="minecraft.install_bridge", field={ minecraft="bridge" }, kind="button" },
      { text="minecraft.bridge", value="minecraft.bridge_connected" },
      { text="minecraft.previews", value="minecraft.send" },
      { text="apple", field={ minecraft="preview", requests=requests }, kind="button" },
    }, rows)

    rows = minecraft.rows({ instance={ instance="/i", fabric=false }, liveSync=true, previews={}, render=true }, tr, encode)
    assert.same({ text="minecraft.bridge", value="minecraft.no_fabric" }, rows[4])
    assert.same({ text="minecraft.render", field={ minecraft="render" }, kind="button" }, rows[5])
    assert.equal(5, #rows)

    rows = minecraft.rows({ instance={ instance="/i", fabric=true }, previews={} }, tr, encode)
    assert.same({ text="minecraft.bridge", value="minecraft.bridge_none" }, rows[5])
  end)

  describe("bridge", function()
    local types = { OPEN=1, TEXT=2, CLOSE=3, ERROR=4 }

    local function connect()
      local sent, socket, changes = {}, nil, 0
      local function WebSocket(args)
        socket = { args=args }
        function socket:connect() self.connected = true end
        function socket:sendText(text) table.insert(sent, (dkjson.decode(text))) end
        function socket:close() self.closed = true end
        return socket
      end
      local bridge = minecraft.bridge{ WebSocket=WebSocket, types=types, encode=encode, decode=dkjson.decode,
                                       port=29415, token="0123456789abcdef", changed=function() changes = changes + 1 end }
      return bridge, socket, sent, function() return changes end
    end

    it("connects to the port on the loopback address and says hello with the token", function()
      local bridge, socket, sent = connect()
      assert.equal("ws://127.0.0.1:29415", socket.args.url)
      assert.is_true(socket.connected)
      assert.equal("connecting", bridge.state)
      socket.args.onreceive(types.OPEN, "", "")
      assert.same({ { op="hello", token="0123456789abcdef" } }, sent)
      socket.args.onreceive(types.TEXT, '{"ok":true}', "")
      assert.equal("connected", bridge.state)
    end)

    it("sends a request with an id once connected and hands its reply back", function()
      local bridge, socket, sent, changes = connect()
      local replies = {}
      bridge.send({ op="reload" }, function(ok, err) table.insert(replies, { ok, err }) end)
      assert.same({ { false, nil } }, replies)
      socket.args.onreceive(types.OPEN, "", "")
      socket.args.onreceive(types.TEXT, '{"ok":true}', "")
      bridge.send({ op="give", item="minecraft:apple" }, function(ok, err) table.insert(replies, { ok, err }) end)
      assert.same({ op="give", item="minecraft:apple", id=1 }, sent[2])
      socket.args.onreceive(types.TEXT, '{"id":1,"ok":false,"error":"give needs creative mode."}', "")
      assert.same({ false, "give needs creative mode." }, replies[2])
      assert.is_true(changes() >= 2)
    end)

    it("says when the client is not reachable or refused the token, and closes", function()
      local bridge, socket = connect()
      socket.args.onreceive(types.ERROR, "", "Connection refused")
      assert.equal("unreachable", bridge.state)
      socket.args.onreceive(types.OPEN, "", "")
      socket.args.onreceive(types.TEXT, '{"ok":false,"error":"wrong token"}', "")
      socket.args.onreceive(types.CLOSE, "", "")
      assert.equal("refused", bridge.state)
      bridge.close()
      assert.is_true(socket.closed)
    end)
  end)
end)
