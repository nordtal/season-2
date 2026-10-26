local minecraft = require("minecraft")
local dkjson = require("dkjson")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

-- Like json.encode, with sorted keys
local function encode(value) return dkjson.encode(value, { keyorder={ "color", "font", "text" } }) end

local star = { text="\u{F0000}", font="nordtal:badges" }

local function glyph(code) return { kind="glyph", entry={ code=code or "F0000" }, font={ font="minecraft:default" } } end

describe("minecraft", function()
  it("writes each preview as the vanilla commands that show the same", function()
    local json = '{"font":"nordtal:badges","text":"\\U000F0000"}'
    assert.same({ "/tellraw @s " .. json }, minecraft.commands({ op="chat", text=star }, encode))
    assert.same({ "/title @s actionbar " .. json }, minecraft.commands({ op="actionbar", text=star }, encode))
    assert.same({ "/title @s title " .. json }, minecraft.commands({ op="title", text=star }, encode))
    assert.same({ "/bossbar remove nordtal:preview", "/bossbar add nordtal:preview " .. json,
                  "/bossbar set nordtal:preview players @s" }, minecraft.commands({ op="bossbar", text=star }, encode))
    assert.same({ "/give @s chest[custom_name=" .. json .. "]" }, minecraft.commands({ op="chest", text=star }, encode))
    assert.same({ "/give @s minecraft:apple" }, minecraft.commands({ op="give", item="minecraft:apple" }, encode))
    assert.same({ "/setblock ^ ^ ^2 minecraft:stone keep" }, minecraft.commands({ op="block", block="minecraft:stone" }, encode))
  end)

  it("writes a text that is a bare string as an object, which Aseprite's json.encode writes", function()
    local function tables(value) return type(value) == "table" and encode(value) or nil end
    assert.same({ '/tellraw @s {"text":"a"}' }, minecraft.commands({ op="chat", text="a" }, tables))
  end)

  it("writes each character beyond ASCII as an SNBT escape, so the command shown is the one copied", function()
    assert.same({ '/tellraw @s {"text":"\\U000F0000\\u00E9a"}' },
      minecraft.commands({ op="chat", text={ text="\u{F0000}éa" } }, encode))
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

  it("offers each preview as commands to copy without Fabric under its label, one too long without a copy, and no install without an instance", function()
    local long = { text=string.rep("x", 300) }
    local rows = minecraft.rows({ liveSync=true, previews={
      { label="chat", requests={ { op="chat", text=star } } },
      { label="long", requests={ { op="chat", text=long } } },
      { label="boss", requests={ { op="bossbar", text={ text="b" } } } },
    } }, tr, encode)
    local command = '/tellraw @s {"font":"nordtal:badges","text":"\\U000F0000"}'
    assert.same({
      { text="minecraft.instance", value="minecraft.no_instance", field={ minecraft="choose" } },
      { text="minecraft.live_sync", value=true, field={ minecraft="live" }, kind="switch" },
      { text="minecraft.previews", value="minecraft.copy" },
      { text="chat", value=command, field={ minecraft="copy", text=command } },
      { text="long", value="minecraft.too_long " .. #("/tellraw @s " .. encode(long)) },
      { text="boss", value="/bossbar remove nordtal:preview",
        field={ minecraft="copy", text="/bossbar remove nordtal:preview" } },
      { value='/bossbar add nordtal:preview {"text":"b"}',
        field={ minecraft="copy", text='/bossbar add nordtal:preview {"text":"b"}' } },
      { value="/bossbar set nordtal:preview players @s",
        field={ minecraft="copy", text="/bossbar set nordtal:preview players @s" } },
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

  it("offers a click that connects again to a bridge that refused the token", function()
    local rows = minecraft.rows({ instance={ instance="/i", fabric=true, bridge="nordtal-bridge-0.63.0.jar" },
                                  bridge="refused", previews={} }, tr, encode)
    assert.same({ text="minecraft.bridge", value="minecraft.bridge_refused", field={ minecraft="retry" } }, rows[5])
  end)

  it("shows the problem describeInstance found with the bridge mod in place of its state", function()
    local rows = minecraft.rows({ instance={ instance="/i", fabric=true, problem="Two jars." }, previews={} }, tr, encode)
    assert.same({ text="minecraft.bridge", value="Two jars." }, rows[5])
  end)

  describe("bridge", function()
    local types = { OPEN=1, TEXT=2, CLOSE=3, ERROR=4 }

    local function connect(decode)
      local sent, socket, changes = {}, nil, 0
      local function WebSocket(args)
        socket = { args=args }
        function socket:connect() self.connected = true end
        function socket:sendText(text) table.insert(sent, (dkjson.decode(text))) end
        function socket:close() self.closed = true end
        return socket
      end
      local bridge = minecraft.bridge{ WebSocket=WebSocket, types=types, encode=encode, decode=decode or dkjson.decode,
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

    it("says when the client is not reachable, and closes", function()
      local bridge, socket = connect()
      socket.args.onreceive(types.ERROR, "", "Connection refused")
      assert.equal("unreachable", bridge.state)
      assert.is_nil(socket.closed)
      bridge.close()
      assert.is_true(socket.closed)
    end)

    it("closes the socket once the client refused the token and stays refused", function()
      local bridge, socket = connect()
      socket.args.onreceive(types.OPEN, "", "")
      socket.args.onreceive(types.TEXT, '{"ok":false,"error":"wrong token"}', "")
      assert.equal("refused", bridge.state)
      assert.is_true(socket.closed)
      socket.args.onreceive(types.CLOSE, "", "")
      socket.args.onreceive(types.ERROR, "", "Connection refused")
      assert.equal("refused", bridge.state)
    end)

    it("reads the replies that Aseprite's json.decode gives as userdata", function()
      -- A file handle is the userdata at hand, with the reply's members as its fields
      local function decode(text)
        local reply = io.tmpfile()
        debug.setmetatable(reply, { __index=dkjson.decode(text) })
        return reply
      end
      local bridge, socket, sent = connect(decode)
      socket.args.onreceive(types.OPEN, "", "")
      socket.args.onreceive(types.TEXT, '{"ok":true}', "")
      assert.equal("connected", bridge.state)
      local replies = {}
      bridge.send({ op="reload" }, function(ok, err) table.insert(replies, { ok, err }) end)
      socket.args.onreceive(types.TEXT, '{"id":1,"ok":false,"error":"no world"}', "")
      assert.same({ { false, "no world" } }, replies)
      assert.equal(1, sent[2].id)
    end)

    it("hands back a send that the socket raised as a failed reply", function()
      local bridge, socket = connect()
      socket.args.onreceive(types.OPEN, "", "")
      socket.args.onreceive(types.TEXT, '{"ok":true}', "")
      function socket:sendText() error("closed", 0) end
      local replies = {}
      bridge.send({ op="reload" }, function(ok, err) table.insert(replies, { ok, err }) end)
      assert.same({ { false, "closed" } }, replies)
      socket.args.onreceive(types.CLOSE, "", "")
      assert.equal(1, #replies)
    end)
  end)
end)
