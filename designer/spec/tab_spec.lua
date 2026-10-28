local tab = require("tab")
local dkjson = require("dkjson")
local fake = require("spec.fake_dialog")

local clone = "/c"
local star = "/c/resource-pack/src/assets/nordtal/textures/badges/star.png"
local starSource = "/c/resource-pack/art/assets/nordtal/textures/badges/star.aseprite"

local model = dkjson.encode({
  fonts={ { font="minecraft:default", constant="DEFAULT", blocks={
    { kind="block", title="Player badges", range="F0000-F000F", glyphs={
      { code="F0000", constant="STAR", name="star", effectiveStatus="keep", height=7, ascent=6,
        texture={ id="nordtal:badges/star.png", png="src/assets/nordtal/textures/badges/star.png",
                  aseprite="art/assets/nordtal/textures/badges/star.aseprite", asepriteExists=false,
                  width=7, height=7 } },
    } } } } },
  items={},
  overrides={ { category="gui/sprites/container", entries={
    { texture={ id="minecraft:gui/sprites/container/slot.png", png="src/slot.png",
                aseprite="art/slot.aseprite", asepriteExists=false, width=24, height=24 } },
    { texture={ id="minecraft:gui/sprites/container/out.png", png="src/../../../Library/out.png",
                aseprite="art/out.aseprite", asepriteExists=false } },
  } } },
})

-- The model with an animation of two frames in the order 1, 0 on the
-- slot override, whose source exists
local function animatedModel()
  local m = dkjson.decode(model)
  local slot = m.overrides[1].entries[1]
  slot.texture.asepriteExists, slot.texture.height = true, 48
  slot.animation = { frametime=2, frames={ 1, 0 } }
  slot.frames = { { x=0, y=0, width=24, height=24, duration=100 }, { x=0, y=24, width=24, height=24, duration=100 } }
  return dkjson.encode(m)
end


local function fakeSprite(filename, log)
  local sprite = { filename=filename, spec={}, palettes={ "palette" }, width=7, height=7, frames={ { duration=0.1 } } }
  function sprite:saveAs(fn) table.insert(log, { "saveAs", fn }) self.filename = fn end
  function sprite:resize(w, h) table.insert(log, { "resize", w, h }) self.width, self.height = w, h end
  function sprite:close() table.insert(log, { "close", self.filename }) end
  return sprite
end

local function floats(value)
  if type(value) == "number" then return value + 0.0 end
  if type(value) ~= "table" then return value end
  local copy = {}
  for k, v in pairs(value) do copy[k] = floats(v) end
  return copy
end

local function setUp(preferences, modelText)
  local Dialog, dialogs = fake()
  local started, log, open, timers, sockets, instanceRuns = {}, {}, {}, {}, {}, {}
  local world = { active=nil }
  local api = {
    Dialog=Dialog,
    WebSocket=function(args)
      local socket = { args=args, sent={} }
      function socket:connect() self.connected = true end
      function socket:sendText(text) table.insert(self.sent, (dkjson.decode(text))) end
      function socket:close() self.closed = true end
      table.insert(sockets, socket)
      return socket
    end,
    messages={ OPEN=1, TEXT=2, CLOSE=3, ERROR=4 },
    chooseFolder=function(t)
      table.insert(log, { "chooseFolder", t.title, t.path })
      return world.folder
    end,
    setClipboard=function(text) table.insert(log, { "clipboard", text }) end,
    tip=function(text) table.insert(log, { "tip", text }) end,
    Color=function(t) return t end,
    Timer=function(args)
      local timer = { args=args }
      function timer:start() self.running = true table.insert(timers, self) end
      function timer:stop() self.running = false end
      return timer
    end,
    Process=function(args)
      local p = { args=args, isRunning=false }
      function p:start()
        if world.failStart then error(world.failStart) end
        -- describeInstance answers at once with world.instance
        if args.cmd[5] == ":resource-pack:describeInstance" then
          table.insert(instanceRuns, self)
          -- With world.hold the read runs until the test ends it
          if world.hold then
            self.isRunning = true
            table.insert(started, self)
            return
          end
          args.onstdout(world.instance or '{"instance":null}')
          args.onexit(0)
          return
        end
        self.isRunning = true
        table.insert(started, self)
      end
      function p:stop() self.isRunning = false self.stopped = true end
      -- Like Process:wait(), hands the output of world.hook to the callbacks
      function p:wait()
        local hook = world.hook or { code=0 }
        for _, line in ipairs(hook.err or {}) do self.args.onstderr(line) end
        self.isRunning = false
        return hook.code
      end
      return p
    end,
    windows=false,
    Rectangle=function(x, y, width, height) return { x=x, y=y, width=width, height=height } end,
    screen=function() return 450, 400 end,
    -- Like app.fs.joinPath and normalizePath
    join=function(...)
      local names = {}
      for name in table.concat({ ... }, "/"):gmatch("[^/]+") do
        if name == ".." then table.remove(names) elseif name ~= "." then table.insert(names, name) end
      end
      return (select(1, ...):sub(1, 1) == "/" and "/" or "") .. table.concat(names, "/")
    end,
    read=function(path)
      if path == "/c/resource-pack/build/pack-model.json" then return world.model or modelText or model end
      if path == "/c/resource-pack/build/vanilla-assets.json" then return world.vanilla end
      return (world.reads or {})[path]
    end,
    -- Aseprite's json.decode gives every number as a float
    decode=function(text) return floats(dkjson.decode(text)) end,
    encode=dkjson.encode,
    launch=function(url) table.insert(log, { "launch", url }) end,
    sprites=function() return open end,
    isFile=function(fn) return fn == star or fn == "/c/resource-pack/src/slot.png" or (world.files or {})[fn] == true end,
    open=function(fn)
      local sprite = fakeSprite(fn, log)
      table.insert(open, sprite)
      return sprite
    end,
    filePath=function(fn) return (fn:match("^(.*)/[^/]*$")) end,
    makeAllDirectories=function() end,
    Image=function(t)
      if t and t.fromFile then table.insert(log, { "image", t.fromFile }) end
      local image = { width=7, height=7 }
      function image:drawSprite() end
      function image:saveAs(t) table.insert(log, { "png", t.filename }) end
      return image
    end,
    activeSprite=function() return world.active end,
    alert=function(t) table.insert(log, { "alert", t.text }) return world.confirm or 2 end,
    write=function(path, text) table.insert(log, { "write", path, text }) end,
    getenv=function() return "/bin" end,
    fitScreen=function() world.fitted = (world.fitted or 0) + 1 end,
  }
  local ctl = tab.open{
    api=api,
    modules={ pack=require("pack"), tree=require("tree"), textures=require("textures"),
               panel=require("panel"), properties=require("properties"), items=require("items"),
               wizard=require("wizard"), drafts=require("drafts"), conflicts=require("conflicts"),
               minecraft=require("minecraft") },
    bootstrap={ clone=clone, env={ JAVA_HOME="/jdk" }, git="/usr/bin/git", gh="/gh" },
    preferences=preferences or {},
    tr=function(id, ...) return table.concat({ id, ... }, " ") end,
  }
  return { ctl=ctl, dialogs=dialogs, started=started, log=log, open=open, world=world, timers=timers, sockets=sockets,
           instanceRuns=instanceRuns }
end

local function kinds(dlg)
  local out = {}
  for _, w in ipairs(dlg.widgets) do
    if w.kind ~= "label" and w.kind ~= "newrow" then table.insert(out, w.kind) end
  end
  return out
end

-- Paints the canvas id on a fake context 1000 wide and returns the
-- lines it draws, as "key", "  value", "<status> text" or "<tone> text"
-- for a colour of the tab's own, and where it drew each text
local function paint(dlg, id, height, width)
  local out, at, color = {}, {}, nil
  -- The theme's text and face are colours that the tab mixes, each with a name to mark it by
  local theme = { color={ text={ name="text", red=0, green=0, blue=0 }, separator_label="key",
                          face={ name="face", red=255, green=255, blue=255 }, status_bar_text="status" },
                  dimension={ mini_scrollbar_size=6 } }
  local function name() return type(color) == "table" and (color.name or "tone") or tostring(color) end
  local gc = { width=width or 1000, height=height or 1000, theme=theme }
  setmetatable(gc, { __newindex=function(t, k, v) if k == "color" then color = v else rawset(t, k, v) end end })
  function gc:measureText(text) return { width=utf8.len(text), height=1 } end
  function gc:fillRect() table.insert(out, "fill " .. name()) end
  function gc:fillText(text, x, y)
    local mark = name() == "key" and "" or name() == "text" and "  " or "<" .. name() .. "> "
    table.insert(out, mark .. text)
    at[text] = at[text] or { x=x, y=y, color=color }
  end
  function gc:drawThemeRect(part, rect)
    if id == "bar" then table.insert(out, part) end
    if part:match("^mini_scrollbar") then at[part] = rect end
  end
  function gc:drawThemeImage(part) table.insert(out, part) end
  dlg.handlers[id].onpaint({ context=gc })
  return out, at
end

-- The lines the properties panel draws, as "key" or "  value"
local function texts(dlg, height)
  return paint(dlg, "properties", height)
end

-- The lines the Minecraft bar draws on a canvas width wide
local function bar(dlg, width)
  return paint(dlg, "bar", nil, width)
end

-- The status in the bar, or nil while there is none
local function status(dlg)
  for _, line in ipairs((bar(dlg))) do
    local text = line:match("^<status> (.*)$")
    if text then return text end
  end
end

-- Clicks the properties panel or else the bar on a text it draws
local function click(dlg, text, height)
  local _, drawn = texts(dlg, height)
  local id = "properties"
  if not drawn[text] then
    _, drawn = bar(dlg)
    id = "bar"
  end
  local at = drawn[text]
  assert(at, text .. " is not drawn")
  dlg.handlers[id].onmousedown({ x=at.x, y=at.y })
end

-- The -P options and the task of a started Gradle wrapper
local function run(p)
  return table.concat(p.args.cmd, " ", 5)
end

local function loaded(t)
  t.started[1].isRunning = false
  t.started[1].args.onexit(0)
  return t.dialogs[#t.dialogs]
end

-- Ends the sync that runs after the first read, with the lines it prints
local function synced(t, ...)
  local p = t.started[#t.started]
  assert.equal(":resource-pack:syncReviews -Pgh=/gh", run(p))
  for _, line in ipairs({ ... }) do p.args.onstdout(line) end
  p.isRunning = false
  p.args.onexit(0)
end

-- The types that readItem prints, which a test of build-logic checks
-- against the task
local itemTypes = (function()
  local file = assert(io.open("spec/item-types.json"))
  local types = dkjson.decode(file:read("a"))
  file:close()
  return types
end)()

-- A pack with one item, and the line readItem prints for it
local lampModel = dkjson.encode({ fonts={}, overrides={}, items={ { category="nordtal", entries={
  { id="nordtal:lamp", model="minecraft:select", file="src/assets/nordtal/items/lamp.json" } } } } })

local lampRead = dkjson.encode({
  item="nordtal:lamp", source="pack", file="/c/resource-pack/src/assets/nordtal/items/lamp.json", problems={},
  definition={ model={ type="minecraft:select", property="minecraft:display_context",
    cases={ { when="gui", model={ type="minecraft:model", model="nordtal:item/star" } } } } },
  types=itemTypes,
  models={ { model="nordtal:item/star", textures={ { key="layer0", texture={ id="nordtal:badges/star.png",
    png="src/assets/nordtal/textures/badges/star.png", aseprite="art/assets/nordtal/textures/badges/star.aseprite",
    width=7, height=7 } } } } },
})

-- Selects the lamp and answers its readItem
local function lamp(t)
  local dlg = loaded(t)
  dlg.handlers.tree.onchange({ node="item/nordtal:lamp" })
  local p = t.started[#t.started]
  p.args.onstdout(lampRead)
  p.isRunning = false
  p.args.onexit(0)
  return dlg
end

-- The pack with the star as a draft and a language file as another
local draftModel = (function()
  local m = dkjson.decode(model)
  m.fonts[1].blocks[1].glyphs[1].texture.draft = m.fonts[1].blocks[1].glyphs[1].texture.id
  m.drafts = { { id="nordtal:badges/star.png", label="star", state="changed", files={} },
               { id="src/assets/minecraft/lang/en_us.json", label="en_us.json", state="added", files={} } }
  m.reviews = {}
  return dkjson.encode(m)
end)()

describe("the Nordtal tab", function()
  it("opens as a tab that cannot be closed", function()
    local t = setUp()
    local dlg = t.dialogs[1]
    assert.is_true(dlg.args.tab)
    assert.is_false(dlg.args.closable)
    assert.equal(1, dlg.shown)
  end)

  it("puts the Minecraft bar above the editor, and the tree above a splitter and the properties in the side panel", function()
    local t = setUp()
    assert.same({ "canvas", "editor", "panel", "tree", "splitter", "canvas" }, kinds(t.dialogs[1]))
    assert.equal("bar", t.dialogs[1].widgets[1].args.id)
    assert.equal("properties", t.dialogs[1].widgets[#t.dialogs[1].widgets].args.id)
    -- A canvas grows with the window unless told otherwise, and the bar keeps one line
    assert.is_false(t.dialogs[1].widgets[1].args.vexpand)
    for _, w in ipairs(t.dialogs[1].widgets) do assert.not_equal("label", w.kind) end
  end)

  it("gives the side panel a width of its own, which the tree and the properties fill", function()
    local t = setUp()
    local handlers = t.dialogs[1].handlers
    assert.same({ id="nordtal_pack", side="left", width=150 }, handlers.nordtal_pack)
    for _, id in ipairs({ "tree", "properties" }) do
      assert.is_nil(handlers[id].width)
      assert.is_nil(handlers[id].hexpand)
    end
  end)

  it("docks the side panel on the right when the setting says so", function()
    local t = setUp({ treeSide="right" })
    assert.same({ "canvas", "editor", "panel", "tree", "splitter", "canvas" }, kinds(t.dialogs[1]))
    assert.equal("right", t.dialogs[1].handlers.nordtal_pack.side)
  end)

  it("moves the side panel to the other side in the same tab, which keeps the sprite", function()
    local preferences = {}
    local t = setUp(preferences)
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local count = #t.dialogs
    t.ctl.setSide("right")
    assert.equal("right", preferences.treeSide)
    assert.same({ id="nordtal_pack", side="right" }, dlg.modified[#dlg.modified])
    assert.equal(count, #t.dialogs)
    assert.is_false(dlg.closed)
    assert.equal(t.open[1], dlg.data.editor)
  end)

  it("builds the tab again with the side panel on the side it was moved to", function()
    local t = setUp()
    local first = loaded(t)
    first.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.ctl.setSide("right")
    paint(first, "bar", 10)
    t.timers[#t.timers].args.ontick()
    local second = t.dialogs[#t.dialogs]
    assert.is_true(first.closed)
    assert.equal("right", second.handlers.nordtal_pack.side)
    assert.equal(t.open[1], second.data.editor)
    assert.equal("glyphs", second.handlers.tree.nodes[1].id)
    assert.equal("glyph/minecraft:default/F0000", second.data.tree)
    assert.equal(1, second.shown)
  end)

  it("empties the editor when the selected node has no texture", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    dlg.handlers.tree.onchange({ node="glyphs" })
    assert.is_nil(dlg.data.editor)
  end)

  it("leaves out what an entry does not have", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local shown = table.concat(texts(dlg), "\n")
    assert.is_falsy(shown:find("false", 1, true))
    assert.is_falsy(shown:find("properties.scaling", 1, true))
    assert.is_falsy(shown:find("properties.animation", 1, true))
  end)

  it("asks for an entry while none is selected", function()
    local t = setUp()
    local dlg = loaded(t)
    assert.same({ "fill face", "  properties.none" }, (texts(dlg)))
  end)

  it("shows that it reads the pack, then the pack's nodes", function()
    local t = setUp()
    local dlg = t.dialogs[1]
    assert.equal("status.loading", status(dlg))
    loaded(t)
    assert.equal("glyphs", dlg.handlers.tree.nodes[1].id)
    assert.is_nil(status(dlg))
  end)

  -- The bar draws one line, so the reason follows the text on it
  it("shows why the pack could not be read", function()
    local t = setUp()
    t.started[1].args.onstderr("FAILURE: Build failed")
    t.started[1].args.onstderr("glyphs.json > nordtal:gui: overlaps")
    t.started[1].args.onexit(1)
    assert.equal("status.failed FAILURE: Build failed glyphs.json > nordtal:gui: overlaps", status(t.dialogs[1]))
  end)

  it("opens a clicked texture in the embedded editor and shows its properties", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    assert.equal(1, #t.open)
    assert.equal(star, t.open[1].filename)
    assert.equal(t.open[1], dlg.data.editor)
    assert.is_true((dlg.repainted or 0) > 0)
    assert.equal(1, t.world.fitted)
    local shown = table.concat(texts(dlg), "\n")
    assert.equal("fill face", texts(dlg)[1])
    assert.is_truthy(shown:find("properties.code\n  F0000\n", 1, true))
    assert.is_truthy(shown:find("properties.name\n  star\n", 1, true))
    assert.is_truthy(shown:find("properties.height\n  7\n", 1, true))
    assert.is_truthy(shown:find("properties.size\n  properties.pixels 7 7\n", 1, true))
  end)

  it("writes the source and the PNG when the embedded sprite is saved", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.world.active = t.open[1]
    local stopped = false
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() stopped = true end })
    assert.is_true(stopped)
    assert.same({ { "saveAs", starSource }, { "png", star } }, t.log)

    -- The second save finds the sprite by its source
    t.log[1], t.log[2] = nil, nil
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.same({ { "saveAs", starSource }, { "png", star } }, t.log)
  end)

  it("leaves the save of any other sprite to Aseprite", function()
    local t = setUp()
    loaded(t)
    t.world.active = fakeSprite("/elsewhere/a.aseprite", t.log)
    local stopped = false
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() stopped = true end })
    assert.is_false(stopped)
    assert.same({}, t.log)
  end)

  it("shows the status that applies to a glyph, which the model sends", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    assert.is_truthy(table.concat(texts(dlg), "\n"):find("properties.status\n  keep\n", 1, true))
  end)

  it("refuses a texture whose files lie outside the resource pack", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/out.png" })
    assert.equal(0, #t.open)
    assert.equal("status.outside /Library/out.png", status(dlg))
  end)

  it("clears the reason of the last entry when another one is selected", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/out.png" })
    dlg.handlers.tree.onchange({ node="glyphs" })
    assert.is_nil(status(dlg))
  end)

  it("empties the editor when the clicked texture cannot be opened", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/out.png" })
    assert.is_nil(dlg.data.editor)
  end)

  it("keeps showing that it reads the pack when the sprite leaves the editor", function()
    local t = setUp()
    local dlg = t.dialogs[1]
    dlg.handlers.editor.onchange()
    assert.equal("status.loading", status(dlg))
  end)

  it("shows why the pack's nodes could not be built", function()
    local t = setUp(nil, dkjson.encode({ fonts={ { font="nordtal:gui", blocks={
      { kind="sheet", title="Row text", texture={ id="nordtal:a.png", png="src/a.png", aseprite="art/a.aseprite" },
        cells={ { row=0, column=0, code="ZZ" } } } } } }, items={}, overrides={} }))
    local dlg = loaded(t)
    assert.equal("status.failed nordtal:gui > Row text: ZZ is no code point", status(dlg))
  end)

  it("stops reading the pack when it closes and leaves the closed dialog alone", function()
    local t = setUp()
    local dlg = t.dialogs[1]
    t.ctl.close()
    assert.is_true(t.started[1].stopped)
    local changes = #dlg.modified
    t.started[1].args.onexit(143)
    assert.equal(changes, #dlg.modified)
  end)

  it("clears the selection when the sprite leaves the editor without the tab", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    dlg.data.editor = nil
    dlg.handlers.editor.onchange()
    assert.equal("", dlg.data.tree)
    assert.same({ "fill face", "  properties.none" }, (texts(dlg)))
    -- A click on the same entry opens it again
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    assert.equal(t.open[1], dlg.data.editor)
  end)

  it("ignores the editor of a dialog the tab replaced", function()
    local t = setUp()
    local first = loaded(t)
    first.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    paint(first, "bar", 10)
    t.timers[#t.timers].args.ontick()
    local second = t.dialogs[#t.dialogs]
    first.handlers.editor.onchange()
    assert.equal("glyph/minecraft:default/F0000", second.data.tree)
  end)
  it("writes a value that a form asks for with its task, then reads the pack again", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local form
    t.dialogs.answer = function(d) form = d d.data = { ok=true, value=9 } end
    click(dlg, "7")
    assert.equal("properties.height", form.args.title)
    assert.same({ kind="number", args={ id="value", label="properties.height", text="7", decimals=0 } }, form.widgets[1])
    assert.equal(":resource-pack:editGlyph -Pglyph=star -Pheight=9", run(t.started[2]))
    assert.equal("status.writing", status(dlg))
    t.started[2].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[3]))
  end)

  it("draws what a refused run changed at once, with one tick of a timer", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { ok=true, value="big-star" } end
    click(dlg, "star")
    local before = #t.timers
    t.started[2].args.onexit(1)
    assert.equal(before + 1, #t.timers)
    local timer = t.timers[#t.timers]
    assert.is_true(timer.running)
    timer.args.ontick()
    assert.is_false(timer.running)
  end)

  it("writes nothing when the form is cancelled", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { cancel=true, value=9 } end
    click(dlg, "star")
    assert.equal(1, #t.started)
  end)

  it("shows each place that still uses a glyph whose rename is refused", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { ok=true, value="big-star" } end
    click(dlg, "star")
    assert.equal(":resource-pack:renameGlyph -Pglyph=star -Pto=big-star", run(t.started[2]))
    for _, line in ipairs({ "* What went wrong:", "Execution failed for task ':resource-pack:renameGlyph'.",
                            "> star is still used in 1 place:", "      smp/src/Badges.java:12", "", "* Try:" }) do
      t.started[2].args.onstderr(line)
    end
    t.started[2].args.onexit(1)
    local lines = texts(dlg)
    -- The refusal comes first, where the panel never cuts it off
    assert.same({ "fill face", "properties.refused" }, { lines[1], lines[2] })
    local shown = table.concat(lines, "\n")
    assert.is_truthy(shown:find("properties.refused\n  star is still used in 1 place:\n  smp/src/Badges.java:12", 1, true))
    assert.is_nil(status(dlg))
    assert.equal(2, #t.started)
    -- Another entry shows no refusal of this one
    dlg.handlers.tree.onchange({ node="glyphs" })
    assert.is_falsy(table.concat(texts(dlg), "\n"):find("properties.refused", 1, true))
  end)

  it("resizes the sprite to a new pixel size and scales the display height with its save", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { ok=true, width=14, height=14 } end
    click(dlg, "properties.pixels 7 7")
    assert.same({ { "resize", 14, 14 }, { "saveAs", starSource }, { "png", star } }, t.log)
    assert.equal(":resource-pack:resizeGlyph -Pcoupled=true -Pfrom=7 -Pglyph=star", run(t.started[2]))
    -- A second save before the pack is read again scales nothing twice
    t.world.active = t.open[1]
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    t.started[2].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[3]))
    assert.equal(3, #t.started)
  end)

  it("asks before a pixel size that loses or doubles rows, and resizes only once confirmed", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { ok=true, width=9, height=9 } end
    click(dlg, "properties.pixels 7 7")
    assert.same({ { "alert", { "properties.resize_confirm 7 7 9 9", "properties.resize_ratio", "properties.resize_lossy" } } }, t.log)
    t.world.confirm = 1
    click(dlg, "properties.pixels 7 7")
    assert.same({ "resize", 9, 9 }, t.log[3])
  end)

  it("compares a new pixel size with the open sprite, not with the size the model gives", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    -- The model gives 7 by 7, the open sprite is a strip of three frames
    t.open[1].height = 21
    t.dialogs.answer = function(d) d.data = { ok=true, width=14, height=14 } end
    click(dlg, "properties.pixels 7 7")
    assert.same({ { "alert", { "properties.resize_confirm 7 21 14 14", "properties.resize_ratio", "properties.resize_lossy" } } }, t.log)
  end)

  it("keeps the old pixel height after a refused resize, shows the refusal and runs it again on the next save", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local sprite = t.open[1]
    sprite.height = 14
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:resizeGlyph -Pcoupled=true -Pfrom=7 -Pglyph=star", run(t.started[2]))
    -- The refusal reaches the status line whatever entry is selected
    dlg.handlers.tree.onchange({ node="glyphs" })
    for _, line in ipairs({ "* What went wrong:", "> star has no block", "* Try:" }) do
      t.started[2].args.onstderr(line)
    end
    t.started[2].args.onexit(1)
    assert.equal("status.refused star has no block", status(dlg))
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:resizeGlyph -Pcoupled=true -Pfrom=7 -Pglyph=star", run(t.started[3]))
  end)

  it("shows why Gradle could not start and runs the next task", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { ok=true, value="a star" } end
    t.world.failStart = "cannot start java"
    click(dlg, "star")
    assert.is_truthy(status(dlg):find("^status.not_started .*cannot start java"))
    t.world.failStart = nil
    click(dlg, "star")
    assert.equal(":resource-pack:renameGlyph -Pglyph=star -Pto=a star", run(t.started[2]))
  end)

  it("shows that a change is being written when a value is clicked meanwhile", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local forms = 0
    t.dialogs.answer = function(d) forms = forms + 1 d.data = { ok=true, value="a star" } end
    click(dlg, "star")
    click(dlg, "7")
    assert.equal(1, forms)
    assert.equal("status.busy", status(dlg))
    assert.equal(2, #t.started)
  end)

  it("runs a second save of an override after the task of the first, never beside it", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local sprite = t.open[1]
    sprite.frames = { { duration=0.1 }, { duration=0.25 } }
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    sprite.frames = { { duration=0.1 }, { duration=0.2 } }
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(2, #t.started)
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    assert.equal(3, #t.started)
    assert.equal(":resource-pack:animateTexture -Pframes=100,200 -Pfrom=2 -Ptexture=minecraft:gui/sprites/container/slot.png",
      run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[4]))
  end)

  it("keeps the coupling switch in the preferences, and a decoupled resize keeps the display height", function()
    local preferences = {}
    local t = setUp(preferences)
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.coupled")
    assert.is_false(preferences.coupled)
    t.open[1].height = 14
    t.world.active = t.open[1]
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:resizeGlyph -Pcoupled=false -Pfrom=7 -Pglyph=star", run(t.started[2]))
  end)

  it("scrolls a panel taller than its canvas with the wheel, down to its last row", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local _, at = texts(dlg, 20)
    assert.equal(4, at["properties.font"].y)
    dlg.handlers.properties.onwheel({ deltaY=1000 })
    _, at = texts(dlg, 20)
    assert.is_true(at["properties.font"].y < 0)
    -- The last row, the button of the previews, is 15 high with its text 6 below its top, and ends at the margin
    assert.equal(20 - 4 - 15 + 6, at["minecraft.render"].y)
    click(dlg, "properties.delete", 20)
    assert.same({ "alert", "properties.delete_confirm star" }, t.log[#t.log])
    dlg.handlers.properties.onwheel({ deltaY=-1000 })
    _, at = texts(dlg, 20)
    assert.equal(4, at["properties.font"].y)
    -- Another entry starts at the top
    dlg.handlers.properties.onwheel({ deltaY=1000 })
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local out
    out, at = texts(dlg, 20)
    assert.equal(4, at[out[2]].y)
  end)

  it("shows a scroll bar along the right edge of a panel taller than its canvas, and none once it fits", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local _, at = texts(dlg, 20)
    assert.same({ x=994, y=0, width=6, height=20 }, at.mini_scrollbar_bg)
    local top = at.mini_scrollbar_thumb
    assert.same({ 994, 0, 6 }, { top.x, top.y, top.width })
    assert.is_true(top.height >= 12 and top.height < 20)
    -- At the last row the thumb ends at the bar's end
    dlg.handlers.properties.onwheel({ deltaY=1000 })
    _, at = texts(dlg, 20)
    assert.equal(20, at.mini_scrollbar_thumb.y + at.mini_scrollbar_thumb.height)
    _, at = texts(dlg, 1000)
    assert.is_nil(at.mini_scrollbar_bg)
    assert.is_nil(at.mini_scrollbar_thumb)
  end)

  it("deletes a glyph once the designer confirms it, and empties the editor", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.delete")
    assert.equal(1, #t.started)
    t.world.confirm = 1
    click(dlg, "properties.delete")
    assert.equal(":resource-pack:deleteGlyph -Pglyph=star", run(t.started[2]))
    t.started[2].args.onexit(0)
    assert.is_nil(dlg.data.editor)
    assert.same({ "close", star }, t.log[#t.log])
    assert.equal("", dlg.data.tree)
    assert.equal(":resource-pack:describePack", run(t.started[3]))
  end)

  it("closes the deleted glyph's sprite and keeps an entry the designer selected while the task ran", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.world.confirm = 1
    click(dlg, "properties.delete")
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local slot = dlg.data.editor
    t.started[2].args.onexit(0)
    assert.same({ "close", star }, t.log[#t.log])
    assert.equal(slot, dlg.data.editor)
    assert.is_truthy(slot)
  end)

  it("shows and resizes the size of one frame of an animated texture when opened, edited and saved", function()
    local t = setUp(nil, animatedModel())
    t.world.files = { ["/c/resource-pack/art/slot.aseprite"]=true }
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    -- The source holds one frame of the PNG per frame of the sprite
    local sprite = t.open[1]
    sprite.width, sprite.height = 24, 24
    sprite.frames = { { duration=0.1 }, { duration=0.1 } }
    t.dialogs.answer = function(d) d.data = { ok=true, width=48, height=48 } end
    click(dlg, "properties.pixels 24 24")
    assert.same({ { "resize", 48, 48 }, { "saveAs", "/c/resource-pack/art/slot.aseprite" },
                  { "png", "/c/resource-pack/src/slot.png" } }, t.log)
    assert.equal(":resource-pack:animateTexture -Pframes=100,100 -Pfrom=2 "
      .. "-Ptexture=minecraft:gui/sprites/container/slot.png", run(t.started[2]))
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    assert.is_truthy(table.concat(texts(dlg), "\n"):find("properties.pixels 48 48", 1, true))
    -- The pack read again holds the PNG 48 by 96 with two frames 48 by 48
    local m = dkjson.decode(animatedModel())
    local slot = m.overrides[1].entries[1]
    slot.texture.width, slot.texture.height = 48, 96
    slot.frames = { { x=0, y=0, width=48, height=48, duration=100 }, { x=0, y=48, width=48, height=48, duration=100 } }
    t.world.model = dkjson.encode(m)
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.is_truthy(table.concat(texts(dlg), "\n"):find("properties.pixels 48 48", 1, true))
  end)

  it("writes every frame of an override and its animation on its save", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local sprite = t.open[1]
    sprite.frames = { { duration=0.1 }, { duration=0.25 } }
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:animateTexture -Pframes=100,250 -Ptexture=minecraft:gui/sprites/container/slot.png",
      run(t.started[2]))
  end)

  it("passes the frame count the sprite was opened with, and after a save the count it saved", function()
    local t = setUp(nil, animatedModel())
    t.world.files = { ["/c/resource-pack/art/slot.aseprite"]=true }
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local sprite = t.open[1]
    sprite.frames = { { duration=0.1 }, { duration=0.1 }, { duration=0.25 } }
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:animateTexture -Pframes=100,100,250 -Pfrom=2 "
      .. "-Ptexture=minecraft:gui/sprites/container/slot.png", run(t.started[2]))
    sprite.frames = { { duration=0.1 }, { duration=0.1 } }
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    assert.equal(":resource-pack:animateTexture -Pframes=100,100 -Pfrom=3 "
      .. "-Ptexture=minecraft:gui/sprites/container/slot.png", run(t.started[3]))
  end)

  it("shows every notice of the animation of a save on the status line, the unplayed frames in one", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local sprite = t.open[1]
    sprite.frames = { { duration=0.1 }, { duration=0.25 } }
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    t.started[2].args.onstdout("dropped width")
    t.started[2].args.onstdout("/c/resource-pack/src/assets/minecraft/textures/gui/sprites/container/slot.png.mcmeta")
    t.started[2].args.onstdout("unplayed 1")
    t.started[2].args.onstdout("unplayed 3")
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.equal("status.dropped_width status.unplayed_many 2, 4", status(dlg))
  end)

  it("drops the notice of a save when the next task is refused before the pack was read again", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local slot = t.open[1]
    slot.frames = { { duration=0.1 }, { duration=0.25 } }
    t.world.active = slot
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local glyph = t.open[2]
    glyph.height = 14
    t.world.active = glyph
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    t.started[2].args.onstdout("dropped width")
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    assert.equal(":resource-pack:resizeGlyph -Pcoupled=true -Pfrom=7 -Pglyph=star", run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(1)
    t.ctl.reload()
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    assert.is_nil(status(dlg))
  end)

  it("runs one task at a time", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.dialogs.answer = function(d) d.data = { ok=true, value="a star" } end
    click(dlg, "properties.coupled")
    t.open[1].height = 14
    t.world.active = t.open[1]
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(2, #t.started)
  end)
  -- The widgets of a dialog as "kind id", and a label as its text
  local function layout(d)
    local out = {}
    for _, w in ipairs(d.widgets) do
      if w.kind == "label" then
        if w.args.visible ~= false then table.insert(out, "label " .. tostring(w.args.text)) end
      elseif w.kind ~= "newrow" and w.kind ~= "separator" then
        table.insert(out, w.kind .. " " .. tostring(w.args.id))
      end
    end
    return out
  end

  local function wizard(t)
    loaded(t)
    t.ctl.create()
    return t.dialogs[#t.dialogs]
  end

  it("asks for the kind of a new texture first, then the fields of a glyph, in a dialog that does not wait", function()
    local t = setUp()
    local w = wizard(t)
    assert.equal("new.dialog", w.args.title)
    assert.same({ "combobox kind", "combobox font", "combobox block", "entry namespace", "entry path", "entry name",
                  "entry constant", "entry width", "entry height", "entry ascent", "button create", "button cancel" },
      layout(w))
    assert.same({ "new.kind_glyph", "new.kind_block", "new.kind_sheet", "new.kind_item", "new.kind_vanilla_item",
                  "new.kind_override" },
      w.handlers.kind.options)
    assert.same({ "minecraft:default" }, w.handlers.font.options)
    assert.same({ "Player badges" }, w.handlers.block.options)
    assert.equal("nordtal", w.handlers.namespace.text)
    assert.same({ wait=false }, w.showArgs)
  end)

  it("shows the fields of the kind chosen and keeps what was typed", function()
    local t = setUp()
    local w = wizard(t)
    w.values.namespace, w.values.path = "nordtal", "wand"
    w.values.kind = "new.kind_item"
    w.handlers.kind.onchange()
    local item = t.dialogs[#t.dialogs]
    assert.is_true(w.closed)
    assert.same({ "combobox kind", "entry namespace", "entry path", "entry width", "entry height", "button create",
                  "button cancel" }, layout(item))
    assert.equal("wand", item.handlers.path.text)
    assert.equal("16", item.handlers.width.text)
  end)

  it("creates a glyph, reads the pack again and opens the new texture in the editor", function()
    local t = setUp()
    local tabDialog = t.dialogs[1]
    local w = wizard(t)
    w.values = { kind="new.kind_glyph", font="minecraft:default", block="Player badges", namespace="nordtal",
                 path="badges/star", name="star", constant="", width="7", height="7", ascent="" }
    w.handlers.create.onclick()
    assert.equal(":resource-pack:createGlyph -Pblock=Player badges -Pfont=minecraft:default -Pheight=7 -Pname=star "
      .. "-Ptexture=nordtal:badges/star.png -Pwidth=7", run(t.started[2]))
    t.started[2].args.onstdout(star)
    t.started[2].args.onexit(0)
    assert.is_true(w.closed)
    assert.equal(":resource-pack:describePack", run(t.started[3]))
    t.started[3].args.onexit(0)
    assert.equal("glyph/minecraft:default/F0000", tabDialog.data.tree)
    assert.equal(star, tabDialog.data.editor.filename)
  end)

  it("shows a refusal under the field it names and keeps the dialog and its values", function()
    local t = setUp()
    local w = wizard(t)
    w.values = { kind="new.kind_glyph", font="minecraft:default", block="Player badges", namespace="nordtal",
                 path="badges/moon", name="moon", width="5000", height="7" }
    w.handlers.create.onclick()
    t.started[2].args.onstdout("refused width")
    t.started[2].args.onstderr("* What went wrong:")
    t.started[2].args.onstderr("> A texture is 1 to 4096 pixels wide and high, not 5000 by 7.")
    t.started[2].args.onstderr("* Try:")
    t.started[2].args.onexit(1)
    local again = t.dialogs[#t.dialogs]
    assert.is_true(w.closed)
    local shown = layout(again)
    local at
    for i, line in ipairs(shown) do if line == "entry width" then at = i end end
    assert.equal("label A texture is 1 to 4096 pixels wide and high, not", shown[at + 1])
    assert.equal("label 5000 by 7.", shown[at + 2])
    assert.equal("entry height", shown[at + 3])
    assert.equal("5000", again.handlers.width.text)
    assert.equal(2, #t.started)
  end)

  it("asks for a field that was left empty in words of its own, not in the task's", function()
    local t = setUp()
    local w = wizard(t)
    w.values.namespace, w.values.path = "nordtal", ""
    w.handlers.create.onclick()
    assert.is_falsy(run(t.started[2]):find("-Ptexture", 1, true))
    t.started[2].args.onstdout("refused texture")
    t.started[2].args.onstderr("* What went wrong:")
    t.started[2].args.onstderr("> Set -Ptexture.")
    t.started[2].args.onexit(1)
    local shown = layout(t.dialogs[#t.dialogs])
    local at
    for i, line in ipairs(shown) do if line == "entry path" then at = i end end
    assert.equal("label new.required", shown[at + 1])
  end)

  it("asks for a texture when none of the client jar was chosen, without running a task", function()
    local t = setUp()
    t.world.vanilla = dkjson.encode({ version="26.2", textures={ { id="minecraft:block/stone.png" } } })
    local w = wizard(t)
    w.values.kind = "new.kind_override"
    w.handlers.kind.onchange()
    t.started[2].args.onexit(0)
    t.dialogs[#t.dialogs].handlers.create.onclick()
    assert.is_nil(t.started[3])
    local shown = layout(t.dialogs[#t.dialogs])
    assert.same({ "label new.chosen_none", "label new.required_texture" }, { shown[#shown - 3], shown[#shown - 2] })
  end)

  it("stays closed when a run or the listing ends after a cancel or a close", function()
    local t = setUp()
    local w = wizard(t)
    w.handlers.create.onclick()
    w.handlers.cancel.onclick()
    local count = #t.dialogs
    t.started[2].args.onstdout("refused width")
    t.started[2].args.onexit(1)
    assert.equal(count, #t.dialogs)

    t.ctl.create()
    local again = t.dialogs[#t.dialogs]
    again.values.kind = "new.kind_override"
    again.handlers.kind.onchange()
    local browser = t.dialogs[#t.dialogs]
    -- The close that building the dialog again causes does not count
    again.args.onclose()
    browser.args.onclose()
    count = #t.dialogs
    t.started[#t.started].args.onexit(0)
    assert.equal(count, #t.dialogs)
  end)

  it("shows a refusal that names no field of the form below the fields", function()
    local t = setUp()
    local w = wizard(t)
    w.handlers.create.onclick()
    t.started[2].args.onstderr("* What went wrong:")
    t.started[2].args.onstderr("> Gradle broke.")
    t.started[2].args.onexit(1)
    local shown = layout(t.dialogs[#t.dialogs])
    assert.equal("label Gradle broke.", shown[#shown - 2])
  end)

  it("keeps a dialog built again where it was, and inside the window when it grew", function()
    local t = setUp()
    t.dialogs.bounds = function() return { x=60, y=50, width=300, height=200 } end
    local w = wizard(t)
    assert.same({ x=60, y=50, width=300, height=200 }, w.bounds)
    w.bounds = { x=120, y=150, width=300, height=200 }
    t.dialogs.bounds = function() return { x=60, y=50, width=380, height=300 } end
    w.values.kind = "new.kind_item"
    w.handlers.kind.onchange()
    assert.same({ x=70, y=100, width=380, height=300 }, t.dialogs[#t.dialogs].bounds)
    t.dialogs.bounds = function() return { x=0, y=0, width=500, height=420 } end
    t.dialogs[#t.dialogs].values.kind = "new.kind_glyph"
    t.dialogs[#t.dialogs].handlers.kind.onchange()
    assert.same({ x=0, y=0, width=500, height=420 }, t.dialogs[#t.dialogs].bounds)
  end)

  it("offers the blocks of the font chosen", function()
    local t = setUp(nil, dkjson.encode({ fonts={
      { font="minecraft:default", blocks={ { kind="block", title="Player badges", glyphs={} } } },
      { font="nordtal:hud", blocks={ { kind="block", title="Bars", glyphs={} }, { kind="sheet", title="Rows" } } },
    }, items={}, overrides={} }))
    local w = wizard(t)
    w.values.font = "nordtal:hud"
    w.handlers.font.onchange()
    assert.same({ id="block", options={ "Bars" }, option="Bars" }, w.modified[#w.modified])
  end)

  it("reads the client jar's textures once, filters them as typed and copies the one chosen", function()
    local t = setUp()
    t.world.vanilla = dkjson.encode({ version="26.2", textures={
      { id="minecraft:block/stone.png", width=16, height=16 }, { id="minecraft:block/dirt.png", width=16, height=16 } } })
    local w = wizard(t)
    w.values.kind = "new.kind_override"
    w.handlers.kind.onchange()
    assert.equal(":resource-pack:listVanillaAssets", run(t.started[2]))
    assert.same({ "combobox kind", "label new.reading", "button create", "button cancel" }, layout(t.dialogs[#t.dialogs]))
    t.started[2].args.onexit(0)
    local browser = t.dialogs[#t.dialogs]
    assert.same({ "combobox kind", "entry search", "tree choices", "label new.count 2", "label new.chosen_none",
                  "button create", "button cancel" }, layout(browser))
    browser.values.search = "sto"
    browser.handlers.search.onchange()
    assert.same("minecraft:block/stone.png", browser.handlers.choices.nodes[1].children[1].id)
    browser.handlers.choices.onchange({ node="minecraft:block/stone.png" })
    browser.handlers.create.onclick()
    assert.equal(":resource-pack:copyVanillaTexture -Ptexture=minecraft:block/stone.png", run(t.started[3]))
    t.started[3].args.onexit(0)
    t.ctl.create()
    t.dialogs[#t.dialogs].values.kind = "new.kind_override"
    t.dialogs[#t.dialogs].handlers.kind.onchange()
    assert.equal(":resource-pack:describePack", run(t.started[4]))
    assert.is_nil(t.started[5])
  end)

  it("offers the client jar's items that the pack does not override and starts the chosen one as an override", function()
    local t = setUp()
    local tabDialog = t.dialogs[1]
    t.world.model = dkjson.encode({ fonts={}, overrides={}, items={
      { category="minecraft", entries={ { id="minecraft:apple" } } } } })
    loaded(t)
    t.world.vanilla = dkjson.encode({ version="26.2", textures={ { id="minecraft:block/stone.png" } },
                                      items={ "minecraft:apple", "minecraft:compass", "minecraft:stone" } })
    t.ctl.create()
    local w = t.dialogs[#t.dialogs]
    w.values.kind = "new.kind_vanilla_item"
    w.handlers.kind.onchange()
    assert.equal(":resource-pack:listVanillaAssets", run(t.started[2]))
    t.started[2].args.onexit(0)
    local browser = t.dialogs[#t.dialogs]
    assert.same({ "combobox kind", "entry search", "tree choices", "label new.count_items 2", "label new.chosen_none_item",
                  "button create", "button cancel" }, layout(browser))
    assert.same({ "minecraft:compass", "minecraft:stone" },
      { browser.handlers.choices.nodes[1].id, browser.handlers.choices.nodes[2].id })
    browser.handlers.create.onclick()
    assert.is_nil(t.started[3])
    local shown = layout(t.dialogs[#t.dialogs])
    assert.equal("label new.required_item", shown[#shown - 2])

    browser = t.dialogs[#t.dialogs]
    browser.handlers.choices.onchange({ node="minecraft:compass" })
    assert.equal("new.chosen_item minecraft:compass", browser.handlers.chosen1.text)
    browser.handlers.create.onclick()
    assert.equal(":resource-pack:editItem -Pitem=minecraft:compass", run(t.started[3]))
    local file = "src/assets/minecraft/items/compass.json"
    t.world.model = dkjson.encode({ fonts={}, overrides={}, reviews={}, items={
      { category="minecraft", entries={ { id="minecraft:apple" },
        { id="minecraft:compass", model="minecraft:select", file=file, draft=file } } } },
      drafts={ { id=file, label="compass.json", state="added", files={ file } } } })
    t.started[3].args.onexit(0)
    assert.is_true(browser.closed)
    assert.equal(":resource-pack:describePack", run(t.started[4]))
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    assert.equal("item/minecraft:compass", tabDialog.data.tree)
    local read = t.started[#t.started]
    assert.equal(":resource-pack:readItem -Pitem=minecraft:compass", run(read))
    read.args.onstdout(dkjson.encode({ item="minecraft:compass", source="pack", file="/c/resource-pack/" .. file,
      problems={}, definition={ model={ type="minecraft:select", property="minecraft:context_dimension", cases={} } },
      types=itemTypes, models={} }))
    read.isRunning = false
    read.args.onexit(0)
    local items = tabDialog.handlers.tree.nodes[2]
    assert.same({ "items", "minecraft:compass", "tab_modified_icon_normal" },
      { items.id, items.children[1].children[2].text, items.children[1].children[2].icon })

    t.world.confirm = 1
    click(tabDialog, "properties.discard")
    local discard = t.started[#t.started]
    assert.equal(":resource-pack:discardDraft -Pdraft=" .. file, run(discard))
    t.world.model = dkjson.encode({ fonts={}, overrides={}, items={
      { category="minecraft", entries={ { id="minecraft:apple" } } } } })
    discard.isRunning = false
    discard.args.onexit(0)
    local reread = t.started[#t.started]
    assert.equal(":resource-pack:describePack", run(reread))
    reread.isRunning = false
    reread.args.onexit(0)
    local count = #t.started
    t.ctl.create()
    w = t.dialogs[#t.dialogs]
    w.values.kind = "new.kind_vanilla_item"
    w.handlers.kind.onchange()
    assert.equal(count, #t.started)
    browser = t.dialogs[#t.dialogs]
    assert.same({ "minecraft:compass", "minecraft:stone" },
      { browser.handlers.choices.nodes[1].id, browser.handlers.choices.nodes[2].id })
  end)

  it("shows why the client jar could not be read in each browser", function()
    local t = setUp()
    local w = wizard(t)
    w.values.kind = "new.kind_override"
    w.handlers.kind.onchange()
    t.started[2].args.onstderr("* What went wrong:")
    t.started[2].args.onstderr("> no client jar")
    t.started[2].isRunning = false
    t.started[2].args.onexit(1)
    assert.same({ "combobox kind", "label no client jar", "button create", "button cancel" }, layout(t.dialogs[#t.dialogs]))
    local browser = t.dialogs[#t.dialogs]
    browser.values.kind = "new.kind_vanilla_item"
    browser.handlers.kind.onchange()
    assert.is_nil(t.started[3])
    assert.same({ "combobox kind", "label no client jar", "button create", "button cancel" }, layout(t.dialogs[#t.dialogs]))
  end)

  it("reads the client jar once for the textures and the items", function()
    local t = setUp()
    t.world.vanilla = dkjson.encode({ version="26.2", textures={}, items={} })
    local w = wizard(t)
    w.values.kind = "new.kind_override"
    w.handlers.kind.onchange()
    t.started[2].args.onexit(0)
    local browser = t.dialogs[#t.dialogs]
    browser.values.kind = "new.kind_vanilla_item"
    browser.handlers.kind.onchange()
    assert.is_nil(t.started[3])
    assert.same({ "combobox kind", "entry search", "tree choices", "label new.count_items 0", "label new.chosen_none_item",
                  "button create", "button cancel" }, layout(t.dialogs[#t.dialogs]))
  end)

  it("reads a selected item's definition and shows its cases below the item's node", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    assert.equal(":resource-pack:readItem -Pitem=nordtal:lamp", run(t.started[2]))
    local changed = dlg.nodesModified[#dlg.nodesModified]
    assert.equal("item/nordtal:lamp", changed.node)
    assert.equal("item/nordtal:lamp#model/cases/0", changed.children[1].id)
    local shown = table.concat(texts(dlg), "\n")
    assert.is_truthy(shown:find("items.type\n  select\nproperty\n  display_context", 1, true))
  end)

  it("keeps a definition's nodes when the tree moves to the other side", function()
    local t = setUp(nil, lampModel)
    lamp(t)
    t.ctl.setSide("right")
    local lampNode = t.dialogs[#t.dialogs].handlers.tree.nodes[2].children[1].children[1]
    assert.equal("item/nordtal:lamp#model/cases/0", lampNode.children[1].id)
  end)

  it("drops a definition that was read for an item no longer selected", function()
    local twoItems = dkjson.encode({ fonts={}, overrides={}, items={ { category="nordtal", entries={
      { id="nordtal:lamp", model="minecraft:select", file="src/assets/nordtal/items/lamp.json" },
      { id="nordtal:bell", model="minecraft:select", file="src/assets/nordtal/items/bell.json" } } } } })
    local t = setUp(nil, twoItems)
    local dlg = lamp(t)
    local before = #dlg.nodesModified
    dlg.handlers.tree.onchange({ node="item/nordtal:bell" })
    local bell = t.started[#t.started]
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp" })
    bell.args.onstdout((lampRead:gsub("nordtal:lamp", "nordtal:bell")))
    bell.isRunning = false
    bell.args.onexit(0)
    assert.equal(before, #dlg.nodesModified)
  end)

  it("opens the texture of a case's model in the editor", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp#model/cases/0" })
    assert.equal(star, t.open[1].filename)
    assert.equal(t.open[1], dlg.data.editor)
    assert.is_truthy(table.concat(texts(dlg), "\n"):find("#layer0\n  nordtal:badges/star.png", 1, true))
  end)

  it("writes a field of a case, reads the definition and the pack again and keeps the case selected", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp#model/cases/0" })
    t.dialogs.answer = function(d) d.data = { ok=true, value="\"ground\"" } end
    click(dlg, "\"gui\"")
    local edit = t.started[#t.started]
    assert.equal(':resource-pack:editItem -Pat=model/cases/0 -Pfields={"when":"ground"} -Pitem=nordtal:lamp', run(edit))
    edit.isRunning = false
    edit.args.onexit(0)
    local read = t.started[#t.started]
    assert.equal(":resource-pack:readItem -Pitem=nordtal:lamp", run(read))
    read.args.onstdout(lampRead)
    read.isRunning = false
    read.args.onexit(0)
    assert.equal("item/nordtal:lamp#model/cases/0", dlg.data.tree)
    local describe = t.started[#t.started]
    assert.equal(":resource-pack:describePack", run(describe))
    describe.args.onexit(0)
    assert.equal("item/nordtal:lamp#model/cases/0", dlg.handlers.tree.nodes[2].children[1].children[1].children[1].id)
    assert.is_truthy(table.concat(texts(dlg), "\n"):find("when", 1, true))
  end)

  it("shows why an edit of a definition was refused", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp#model/cases/0" })
    t.dialogs.answer = function(d) d.data = { ok=true, value="nordtal:item/none" } end
    click(dlg, "nordtal:item/star")
    local edit = t.started[#t.started]
    for _, line in ipairs({ "* What went wrong:", "> The item nordtal:lamp breaks 1 rule(s):",
                            "      model is in neither the pack nor the client jar", "* Try:" }) do
      edit.args.onstderr(line)
    end
    edit.args.onexit(1)
    assert.is_truthy(table.concat(texts(dlg), "\n"):find("properties.refused\n  The item nordtal:lamp breaks 1 rule(s):",
      1, true))
  end)

  it("asks for the fields that a refused edit still needs and runs it again with them", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp#model/cases/0" })
    local asked = {}
    t.dialogs.answer = function(d)
      local id = d.widgets[1].args.id
      table.insert(asked, id)
      d.data = id == "type" and { ok=true, type="minecraft:special" } or { ok=true, ["need:base"]="minecraft:item/bell" }
    end
    click(dlg, "model")
    local edit = t.started[#t.started]
    assert.equal(':resource-pack:editItem -Pat=model/cases/0/model -Pfields={"type":"minecraft:special"} -Pitem=nordtal:lamp',
      run(edit))
    edit.args.onstdout("needs base id")
    for _, line in ipairs({ "* What went wrong:", "> Set base.", "* Try:" }) do edit.args.onstderr(line) end
    edit.isRunning = false
    edit.args.onexit(1)
    assert.same({ "type", "need:base" }, asked)
    local again = t.started[#t.started]
    assert.equal(':resource-pack:editItem -Pat=model/cases/0/model -Pfields={"type":"minecraft:special","base":"minecraft:item/bell"} -Pitem=nordtal:lamp',
      run(again))
    again.isRunning = false
    again.args.onexit(0)
    assert.equal(":resource-pack:readItem -Pitem=nordtal:lamp", run(t.started[#t.started]))
  end)

  it("writes every frame of a definition's texture and its animation on its save", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp#model/cases/0" })
    local sprite = t.open[1]
    sprite.frames = { { duration=0.1 }, { duration=0.25 } }
    t.world.active = sprite
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:animateTexture -Pframes=100,250 -Ptexture=nordtal:badges/star.png",
      run(t.started[#t.started]))
  end)

  it("imports a Blockbench model into a case in one run and then shows it there", function()
    local t = setUp(nil, lampModel)
    local dlg = lamp(t)
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp#model/cases/0" })
    local form
    t.dialogs.answer = function(d) form = d d.data = { ok=true, file="/tmp/lamp.bbmodel", id="nordtal:item/lamp" } end
    click(dlg, "items.import")
    assert.equal("file", form.widgets[1].kind)
    local import = t.started[#t.started]
    assert.equal(":resource-pack:importBlockbench -Pat=model/cases/0/model -Pid=nordtal:item/lamp -Pitem=nordtal:lamp -Pmodel=/tmp/lamp.bbmodel",
      run(import))
    import.isRunning = false
    import.args.onexit(0)
    assert.equal(":resource-pack:readItem -Pitem=nordtal:lamp", run(t.started[#t.started]))
  end)

  it("discards the selected draft once the designer confirms it, and closes its sprite", function()
    local t = setUp(nil, draftModel)
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.discard")
    assert.equal(2, #t.started)
    assert.same({ "alert", "properties.discard_confirm star" }, t.log[#t.log])
    t.world.confirm = 1
    click(dlg, "properties.discard")
    assert.equal(":resource-pack:discardDraft -Pdraft=nordtal:badges/star.png", run(t.started[3]))
    t.started[3].args.onexit(0)
    assert.is_nil(dlg.data.editor)
    assert.same({ "close", star }, t.log[#t.log])
    assert.equal(":resource-pack:describePack", run(t.started[4]))
  end)

  it("offers no discard for an entry that is no draft", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    assert.is_falsy(table.concat(texts(dlg), "\n"):find("properties.discard", 1, true))
  end)

  it("publishes the chosen drafts once the commit hook accepts the title, then reads the pack", function()
    local t = setUp(nil, draftModel)
    loaded(t)
    synced(t)
    local form
    t.dialogs.answer = function(d)
      form = d
      d.data = { draft1=true, draft2=false, title="feat(resource-pack): the star is white", publish=true }
    end
    t.ctl.publish()
    local message = "/c/resource-pack/build/publish-message.txt"
    assert.same({ "write", message, "feat(resource-pack): the star is white\n" }, t.log[#t.log])
    assert.same({ "/usr/bin/git", "hook", "run", "commit-msg", "--", message }, t.started[3].args.cmd)
    assert.equal(clone, t.started[3].args.cwd)
    assert.equal(":resource-pack:publishDrafts -Pdrafts=nordtal:badges/star.png -Pgh=/gh -Ptitle=feat(resource-pack): the star is white",
      run(t.started[4]))
    assert.equal("check", form.widgets[1].kind)
    t.started[4].args.onstdout("https://github.com/nordtal/season-2/pull/42")
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[5]))
    t.started[5].isRunning = false
    t.started[5].args.onexit(0)
    assert.equal("status.published https://github.com/nordtal/season-2/pull/42", status(t.dialogs[1]))
  end)

  it("reads the pack again after a publish that failed, since it may have moved code points, and says why", function()
    local t = setUp(nil, draftModel)
    loaded(t)
    synced(t)
    t.dialogs.answer = function(d)
      d.data = { draft1=true, draft2=false, title="feat(resource-pack): the star is white", publish=true }
    end
    t.ctl.publish()
    t.started[4].args.onstderr("* What went wrong:")
    t.started[4].args.onstderr("> the star is too bright")
    t.started[4].isRunning = false
    t.started[4].args.onexit(1)
    assert.equal(":resource-pack:describePack", run(t.started[5]))
    t.started[5].isRunning = false
    t.started[5].args.onexit(0)
    assert.equal("status.refused the star is too bright", status(t.dialogs[1]))
  end)

  it("keeps the publish dialog open with the reason the commit hook gives, and sends nothing", function()
    local t = setUp(nil, draftModel)
    loaded(t)
    synced(t)
    t.world.hook = { code=1, err={ "commit-msg: \"white star\" is not type(scope): description" } }
    local answers = 0
    t.dialogs.answer = function(d)
      answers = answers + 1
      d.data = answers == 1 and { draft1=true, draft2=true, title="white star", publish=true } or { publish=false, cancel=true }
    end
    t.ctl.publish()
    assert.equal(2, answers)
    assert.equal(3, #t.started)
    local refusal = t.dialogs[#t.dialogs].handlers
    assert.same({ "commit-msg: \"white star\" is not type(scope):", "description" }, { refusal.refusal1.text, refusal.refusal2.text })
  end)

  it("discards the drafts chosen in the publish dialog one after the other", function()
    local t = setUp(nil, draftModel)
    loaded(t)
    synced(t)
    t.world.confirm = 1
    t.dialogs.answer = function(d) d.data = { draft1=true, draft2=true, title="x", discard=true } end
    t.ctl.publish()
    assert.equal(":resource-pack:discardDraft -Pdraft=nordtal:badges/star.png", run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.equal(":resource-pack:discardDraft -Pdraft=src/assets/minecraft/lang/en_us.json", run(t.started[4]))
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[5]))
  end)

  it("says that there is nothing to publish while the pack has no drafts", function()
    local t = setUp()
    loaded(t)
    t.ctl.publish()
    assert.same({ "alert", "publish.no_drafts" }, t.log[#t.log])
    assert.equal(1, #t.started)
  end)

  it("asks GitHub for its pull requests after the first read and every five minutes, and reads the pack after a merge", function()
    local m = dkjson.decode(draftModel)
    m.reviews = { { number=42, url="https://github.com/nordtal/season-2/pull/42" } }
    local t = setUp(nil, dkjson.encode(m))
    loaded(t)
    assert.equal(":resource-pack:syncReviews -Pgh=/gh", run(t.started[2]))
    t.started[2].args.onstdout("merged 42")
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.equal("status.merged 42", status(t.dialogs[1]))
    local every
    for _, timer in ipairs(t.timers) do
      if timer.args.interval == 300 then every = timer end
    end
    assert.is_true(every.running)
    every.args.ontick()
    assert.equal(":resource-pack:syncReviews -Pgh=/gh", run(t.started[4]))
    t.ctl.close()
    assert.is_false(every.running)
  end)

  it("reads the pack again after a sync put back the changes of a pull that stopped halfway", function()
    local m = dkjson.decode(draftModel)
    m.reviews = { { number=42, url="https://github.com/nordtal/season-2/pull/42" } }
    local t = setUp(nil, dkjson.encode(m))
    loaded(t)
    t.started[2].args.onstdout("restored")
    t.started[2].isRunning = false
    t.started[2].args.onexit(0)
    assert.equal(":resource-pack:describePack", run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.equal("status.restored", status(t.dialogs[1]))
  end)

  it("asks GitHub after the first read while the pack has drafts, and reads the pack again when main moved or a review changed", function()
    local t = setUp(nil, draftModel)
    loaded(t)
    synced(t, "moved")
    assert.equal(":resource-pack:describePack", run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.is_nil(status(t.dialogs[1]))
    for _, timer in ipairs(t.timers) do
      if timer.args.interval == 300 then timer.args.ontick() end
    end
    synced(t, "reviewed 42")
    t.started[5].isRunning = false
    t.started[5].args.onexit(0)
    assert.equal("status.reviewed 42", status(t.dialogs[1]))
  end)

  it("only says so when GitHub did not answer for a pull request", function()
    local m = dkjson.decode(draftModel)
    m.reviews = { { number=42, url="https://github.com/nordtal/season-2/pull/42" } }
    local t = setUp(nil, dkjson.encode(m))
    loaded(t)
    synced(t, "unanswered 42")
    assert.equal(2, #t.started)
    assert.equal("status.unanswered 42", status(t.dialogs[1]))
  end)

  it("compares both versions of a conflict, resolves it as the designer chooses and opens the texture it kept", function()
    local m = dkjson.decode(draftModel)
    m.drafts[1].conflict = { files={ "src/assets/nordtal/textures/badges/star.png" },
                             fields={ { name="about", clone="\"a\"", main="\"b\"" } } }
    local t = setUp(nil, dkjson.encode(m))
    local main = "/c/resource-pack/build/conflict/src/assets/nordtal/textures/badges/star.png"
    t.world.files = { [main]=true }
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local shown = table.concat(texts(dlg), "\n")
    assert.truthy(shown:find("properties.conflict\n  src/assets/nordtal/textures/badges/star.png, about", 1, true))
    click(dlg, "properties.resolve")
    assert.equal(":resource-pack:readConflict -Pdraft=nordtal:badges/star.png", run(t.started[3]))
    local compare
    t.dialogs.answer = function(d)
      compare = d
      d.data = { keep_clone=false, keep_main=true, clone1=true, main1=false, ok=true }
    end
    t.started[3].args.onstdout(main)
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.truthy(compare.handlers.compare)
    assert.same({ { "image", star }, { "image", main } }, { t.log[#t.log - 1], t.log[#t.log] })
    assert.equal(":resource-pack:resolveConflict -Pdraft=nordtal:badges/star.png -Pfields={\"about\":\"clone\"} -Pkeep=main",
      run(t.started[4]))
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    assert.same({ "close", star }, t.log[#t.log])
    assert.is_nil(dlg.data.editor)
    t.started[5].isRunning = false
    t.started[5].args.onexit(0)
    assert.equal("glyph/minecraft:default/F0000", dlg.data.tree)
    assert.equal(t.open[#t.open], dlg.data.editor)
  end)

  it("keeps the texture of another entry open that the designer chose while a conflict was read", function()
    local m = dkjson.decode(draftModel)
    m.drafts[1].conflict = { files={ "src/assets/nordtal/textures/badges/star.png" }, fields={} }
    local t = setUp(nil, dkjson.encode(m))
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.resolve")
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/slot.png" })
    local other = dlg.data.editor
    t.dialogs.answer = function(d) d.data = { keep_clone=false, keep_main=true, ok=true } end
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    assert.truthy(other)
    assert.equal(other, dlg.data.editor)
    for _, entry in ipairs(t.log) do
      assert.are_not.same({ "close", other.filename }, entry)
    end
  end)

  it("shows the pull request of a change in review with its comments, and opens it on GitHub", function()
    local m = dkjson.decode(model)
    m.fonts[1].blocks[1].glyphs[1].texture.review = 42
    m.reviews = { { number=42, url="https://github.com/nordtal/season-2/pull/42", decision="APPROVED", checks="success",
                    comments={ { author="ana", body="Nice." } } } }
    local t = setUp(nil, dkjson.encode(m))
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    local shown = table.concat(texts(dlg), "\n")
    assert.truthy(shown:find("properties.review\n  review.approved 42\nproperties.checks\n  checks.success\nana\n  Nice.", 1, true))
    click(dlg, "properties.github")
    assert.same({ "launch", "https://github.com/nordtal/season-2/pull/42" }, t.log[#t.log])
  end)

  it("opens only an address on GitHub, and nothing once the pull request is gone", function()
    local m = dkjson.decode(model)
    m.fonts[1].blocks[1].glyphs[1].texture.review = 42
    m.reviews = { { number=42, url="file:///etc/passwd" } }
    local t = setUp(nil, dkjson.encode(m))
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.github")
    m.reviews = {}
    t.ctl.edit("github")
    for _, entry in ipairs(t.log) do
      assert.are_not.equal("launch", entry[1])
    end
  end)

  it("goes on with the next run when what a run's result calls raises an error", function()
    local m = dkjson.decode(draftModel)
    m.drafts[1].conflict = { files={}, fields={ { name="about", clone="\"a\"", main="\"b\"" } } }
    local t = setUp(nil, dkjson.encode(m))
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.resolve")
    t.dialogs.answer = function() error("broken dialog") end
    t.started[3].isRunning = false
    assert.has_error(function() t.started[3].args.onexit(0) end)
    t.ctl.reload()
    assert.equal(":resource-pack:describePack", run(t.started[4]))
  end)

  it("says where the clone's version went once a resolve kept main's", function()
    local m = dkjson.decode(draftModel)
    m.drafts[1].conflict = { files={ "src/assets/nordtal/textures/badges/star.png" }, fields={} }
    local t = setUp(nil, dkjson.encode(m))
    local dlg = loaded(t)
    synced(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    click(dlg, "properties.resolve")
    t.dialogs.answer = function(d) d.data = { keep_clone=false, keep_main=true, ok=true } end
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    t.started[4].isRunning = false
    t.started[4].args.onexit(0)
    t.started[5].isRunning = false
    t.started[5].args.onexit(0)
    assert.equal("status.yours /c/resource-pack/build/conflict/yours", status(dlg))
  end)
end)

describe("the Minecraft bar of the Nordtal tab", function()
  local fabric = '{"instance":"/mc/fabric","fabric":true,"bridge":"nordtal-bridge-0.63.0.jar","port":29415,'
    .. '"token":"0123456789abcdef0123456789abcdef"}'
  local plain = '{"instance":"/mc/plain","fabric":false,"bridge":null,"port":null,"token":null}'
  local previews = "/c/pack-rendering/build/glyph-previews.json"

  -- Opens the tab on world.instance and connects its bridge
  local function connected(instance)
    local t = setUp()
    t.world.instance = instance or fabric
    local dlg = loaded(t)
    local socket = t.sockets[1]
    if socket then
      socket.args.onreceive(1, "", "")
      socket.args.onreceive(2, '{"ok":true}', "")
    end
    return t, dlg, socket
  end

  -- Ends the run that started last, with the lines it printed
  local function finish(t, code, ...)
    local p = t.started[#t.started]
    for _, line in ipairs({ ... }) do
      if code == 0 then p.args.onstdout(line) else p.args.onstderr(line) end
    end
    p.isRunning = false
    p.args.onexit(code)
    return p
  end

  -- Selects the star and renders its previews
  local function rendered(t, dlg)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.world.reads = { [previews]=dkjson.encode({ font="minecraft:default", code="F0000", glyph={ text="\u{F0000}" },
                                                 menu={ text="menu" }, hud=dkjson.null, board={ text="board" } }) }
    click(dlg, "minecraft.render")
    assert.equal(":pack-rendering:renderGlyphPreviews -Pcode=F0000 -Pfont=minecraft:default", run(t.started[#t.started]))
    finish(t, 0, previews)
  end

  it("shows only the status before the instance is read, and no instance to choose", function()
    local t = setUp()
    assert.same({ "fill face", "<status> status.loading" }, (bar(t.dialogs[1])))
  end)

  it("reads the chosen instance once the pack is read and shows it in the bar, with live sync on", function()
    local t = setUp()
    t.world.instance = plain
    local dlg = loaded(t)
    assert.equal(1, #t.instanceRuns)
    local lines, at = bar(dlg)
    assert.same({ "fill face", "sunken_normal", "  plain", "check_selected", "  minecraft.live_sync", "button_normal",
                  "  minecraft.install_pack", "<tone> minecraft.state_no_fabric" }, lines)
    -- The grey of No Fabric is two thirds of the theme's text and one third of its face, which reads on both themes
    assert.same({ r=85, g=85, b=85 }, at["minecraft.state_no_fabric"].color)
    assert.equal(0, #t.sockets)
  end)

  it("asks for an instance when none is chosen, and when the instance could not be read", function()
    local t = setUp()
    local dlg = loaded(t)
    assert.same({ "fill face", "sunken_normal", "  minecraft.no_instance", "check_selected", "  minecraft.live_sync" },
      (bar(dlg)))
    t = setUp()
    t.world.instance = "no JSON"
    dlg = loaded(t)
    assert.same({ "fill face", "sunken_normal", "  minecraft.no_instance" }, { table.unpack((bar(dlg)), 1, 3) })
    assert.is_truthy(status(dlg):find("^minecraft.failed"))
  end)

  it("shows the long text of the bridge's state as a tip while the pointer rests on its word", function()
    local t, dlg = connected()
    local _, at = bar(dlg)
    local word = at["minecraft.state_connected"]
    dlg.handlers.bar.onmousemove({ x=word.x, y=word.y })
    assert.same({ "tip", "minecraft.bridge_connected" }, t.log[#t.log])
    local count = #t.log
    dlg.handlers.bar.onmousemove({ x=word.x + 1, y=word.y })
    assert.equal(count, #t.log)
    dlg.handlers.bar.onmousemove({ x=at["minecraft.install_pack"].x, y=word.y })
    dlg.handlers.bar.onmousemove({ x=word.x, y=word.y })
    assert.equal(count + 1, #t.log)
  end)

  it("chooses an instance in the system's folder chooser, checks it with the chooser task and reads it again", function()
    local t = setUp()
    local dlg = loaded(t)
    t.world.folder = "/mc/fabric"
    click(dlg, "minecraft.no_instance")
    assert.same({ "chooseFolder", "minecraft.choose" }, t.log[#t.log])
    assert.equal(":resource-pack:chooseMinecraftInstance -PminecraftInstance=/mc/fabric", run(t.started[#t.started]))
    t.world.instance = fabric
    finish(t, 0)
    assert.equal(2, #t.instanceRuns)
    assert.equal("ws://127.0.0.1:29415", t.sockets[1].args.url)
    assert.is_truthy(table.concat(bar(dlg), "\n"):find("  fabric", 1, true))

    -- A folder the chooser refuses shows its reason
    t.world.folder = "/mc/empty"
    click(dlg, "fabric")
    finish(t, 1, "/mc/empty holds no file of Minecraft or of a launcher, so it is not a Minecraft instance.", "",
      "FAILURE: Build failed with an exception.", "", "* What went wrong:", "Execution failed for task 'x'.",
      "> Process 'command '/jdk/bin/java'' finished with non-zero exit value 1", "", "* Try:")
    assert.equal("status.refused /mc/empty holds no file of Minecraft or of a launcher, so it is not a Minecraft instance.",
      status(dlg))
    assert.equal(2, #t.instanceRuns)
  end)

  it("installs the pack on each save while live sync is on and reloads it in the connected client", function()
    local t, dlg, socket = connected()
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.world.active = t.open[1]
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(":resource-pack:installPack", run(t.started[#t.started]))
    local count = #t.started
    finish(t, 0)
    assert.same({ op="reload", id=1 }, socket.sent[2])
    socket.args.onreceive(2, '{"id":1,"ok":true}', "")
    assert.equal("minecraft.installed", status(dlg))

    click(dlg, "minecraft.live_sync")
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(count, #t.started)
  end)

  it("keeps live sync off once switched off, and installs the pack on a click", function()
    local preferences = { liveSync=false }
    local t = setUp(preferences)
    t.world.instance = plain
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.world.active = t.open[1]
    t.ctl.onBeforeCommand({ name="SaveFile", stopPropagation=function() end })
    assert.equal(1, #t.started)
    click(dlg, "minecraft.live_sync")
    assert.is_true(preferences.liveSync)
    click(dlg, "minecraft.install_pack")
    assert.equal(":resource-pack:installPack", run(t.started[2]))
    finish(t, 0)
    assert.equal("minecraft.installed_f3t", status(dlg))
  end)

  it("installs the bridge mod with the bundled gh and reads the instance again", function()
    local t = setUp()
    t.world.instance = '{"instance":"/mc/fabric","fabric":true}'
    local dlg = loaded(t)
    assert.is_truthy(table.concat(bar(dlg), "\n"):find("<tone> minecraft.state_none", 1, true))
    click(dlg, "minecraft.install_bridge")
    assert.equal(":resource-pack:installBridgeMod -Pgh=/gh", run(t.started[#t.started]))
    t.world.instance = fabric
    finish(t, 0, "/mc/fabric/mods/nordtal-bridge-0.63.0.jar")
    assert.equal(2, #t.instanceRuns)
    assert.equal(1, #t.sockets)
    assert.equal("minecraft.bridge_installed", status(dlg))
    local shown = table.concat(bar(dlg), "\n")
    assert.is_truthy(shown:find("  minecraft.update_bridge", 1, true))
    assert.is_truthy(shown:find("<tone> minecraft.state_connecting", 1, true))
  end)

  it("draws a change of the bridge's state at once, with one tick of a timer", function()
    local t = setUp()
    t.world.instance = fabric
    loaded(t)
    local before = #t.timers
    t.sockets[1].args.onreceive(1, "", "")
    t.sockets[1].args.onreceive(2, '{"ok":false,"error":"wrong token"}', "")
    assert.is_true(#t.timers > before)
    assert.is_true(t.timers[#t.timers].running)
  end)

  it("connects again to a bridge that refused the token only on a click or a new token", function()
    local t = setUp()
    t.world.instance = fabric
    local dlg = loaded(t)
    t.sockets[1].args.onreceive(1, "", "")
    t.sockets[1].args.onreceive(2, '{"ok":false,"error":"wrong token"}', "")
    assert.is_true(t.sockets[1].closed)
    t.ctl.readInstance()
    assert.equal(1, #t.sockets)

    click(dlg, "minecraft.state_refused")
    assert.equal(3, #t.instanceRuns)
    assert.equal(2, #t.sockets)
    assert.is_true(t.sockets[2].connected)

    t.world.instance = fabric:gsub("0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210")
    t.ctl.readInstance()
    assert.equal(3, #t.sockets)
    t.sockets[3].args.onreceive(1, "", "")
    assert.same({ op="hello", token="fedcba9876543210fedcba9876543210" }, t.sockets[3].sent[1])
  end)

  -- Opens the tab on an instance whose bridge refused the token
  local function refusedBridge()
    local t = setUp()
    t.world.instance = fabric
    local dlg = loaded(t)
    t.sockets[1].args.onreceive(1, "", "")
    t.sockets[1].args.onreceive(2, '{"ok":false,"error":"wrong token"}', "")
    return t, dlg
  end

  it("says Checking and Connecting while a click on Refused reads the instance, ignores more clicks and shows Refused when the read fails", function()
    local t, dlg = refusedBridge()
    t.world.hold = true
    local painted = dlg.repainted or 0
    click(dlg, "minecraft.state_refused")
    assert.is_true(dlg.repainted > painted)
    assert.equal("minecraft.checking", status(dlg))
    local shown = table.concat(bar(dlg), "\n")
    assert.is_truthy(shown:find("<tone> minecraft.state_connecting", 1, true))
    assert.is_falsy(shown:find("minecraft.state_refused", 1, true))
    assert.equal(2, #t.instanceRuns)
    click(dlg, "minecraft.state_connecting")
    t.ctl.edit({ minecraft="retry" })
    assert.equal(2, #t.instanceRuns)

    finish(t, 1, "The instance cannot be read.")
    shown = table.concat(bar(dlg), "\n")
    assert.is_truthy(shown:find("button_normal\n<tone> minecraft.state_refused", 1, true))
    assert.is_truthy(status(dlg):find("^minecraft.failed"))
    assert.equal(1, #t.sockets)
    -- A read of the same token afterwards still does not connect again
    t.world.hold = false
    t.ctl.readInstance()
    assert.equal(1, #t.sockets)
  end)

  it("connects again once the read that a click on Refused started succeeded, and clears the status", function()
    local t, dlg = refusedBridge()
    t.world.hold = true
    click(dlg, "minecraft.state_refused")
    finish(t, 0, fabric)
    assert.equal(2, #t.sockets)
    assert.is_nil(status(dlg))
    assert.is_truthy(table.concat(bar(dlg), "\n"):find("<tone> minecraft.state_connecting", 1, true))
  end)

  it("shows the tip of an item again once the pointer moved over the properties panel and back", function()
    local t, dlg = connected()
    local _, at = bar(dlg)
    local word = at["minecraft.state_connected"]
    dlg.handlers.bar.onmousemove({ x=word.x, y=word.y })
    local count = #t.log
    dlg.handlers.properties.onmousemove({ x=10, y=10 })
    dlg.handlers.bar.onmousemove({ x=word.x, y=word.y })
    assert.equal(count + 1, #t.log)
    assert.same({ "tip", "minecraft.bridge_connected" }, t.log[#t.log])
  end)

  it("draws the status after a divider, and shows a status that the bar cuts as a tip once", function()
    local t, dlg = connected()
    click(dlg, "minecraft.install_pack")
    local timers = #t.timers
    local lines = bar(dlg, 200)
    assert.same({ "fill tone", "<status> minecraft.insta..." }, { lines[#lines - 1], lines[#lines] })
    -- The tip shows after the paint, with one tick of a timer
    assert.equal(timers + 1, #t.timers)
    t.timers[#t.timers].args.ontick()
    assert.same({ "tip", "minecraft.installing" }, t.log[#t.log])
    bar(dlg, 200)
    assert.equal(timers + 1, #t.timers)
    -- A status the bar shows whole has no tip
    finish(t, 0)
    t.sockets[1].args.onreceive(2, '{"id":1,"ok":true}', "")
    local count, before = #t.log, #t.timers
    bar(dlg)
    for i = before + 1, #t.timers do t.timers[i].args.ontick() end
    assert.equal(count, #t.log)
  end)

  it("builds the tab again once when a paint measures the bar higher than its canvas, with the measured height", function()
    local t = setUp()
    local first = t.dialogs[1]
    assert.equal(32, first.widgets[1].args.height)
    local timers = #t.timers
    paint(first, "bar", 10)
    assert.equal(timers + 1, #t.timers)
    t.timers[#t.timers].args.ontick()
    assert.equal(2, #t.dialogs)
    assert.is_true(first.closed)
    -- The fake's bar is 15 high, and the canvas adds a margin of 4 above and below it
    assert.equal(23, t.dialogs[2].widgets[1].args.height)
    paint(t.dialogs[2], "bar", 10)
    assert.equal(timers + 1, #t.timers)
    assert.equal(2, #t.dialogs)
  end)

  it("draws a bridge that refused the token as a button", function()
    local t = setUp()
    t.world.instance = fabric
    local dlg = loaded(t)
    t.sockets[1].args.onreceive(1, "", "")
    t.sockets[1].args.onreceive(2, '{"ok":false,"error":"wrong token"}', "")
    local lines = bar(dlg)
    assert.same({ "button_normal", "<tone> minecraft.state_refused" }, { lines[#lines - 1], lines[#lines] })
  end)

  it("drops a refused bridge on a click once its mod is gone", function()
    local t = setUp()
    t.world.instance = fabric
    local dlg = loaded(t)
    t.sockets[1].args.onreceive(1, "", "")
    t.sockets[1].args.onreceive(2, '{"ok":false,"error":"wrong token"}', "")
    t.world.instance = '{"instance":"/mc/fabric","fabric":true,"bridge":null,"port":null,"token":null}'
    click(dlg, "minecraft.state_refused")
    local shown = table.concat(bar(dlg), "\n")
    assert.is_falsy(shown:find("minecraft.state_refused", 1, true))
    assert.is_truthy(shown:find("minecraft.state_none", 1, true))
  end)

  it("renders a glyph's previews and shows one in the client", function()
    local t, dlg, socket = connected()
    rendered(t, dlg)
    local shown = table.concat(texts(dlg), "\n")
    assert.is_truthy(shown:find("minecraft.previews\n  minecraft.send\n  minecraft.chat", 1, true))
    assert.is_truthy(shown:find("  minecraft.board", 1, true))
    assert.is_falsy(shown:find("minecraft.render", 1, true))
    click(dlg, "minecraft.menu")
    assert.same({ op="chest", text={ text="menu" }, rows=3, id=1 }, socket.sent[2])
    socket.args.onreceive(2, '{"id":1,"ok":false,"error":"no world"}', "")
    assert.equal("minecraft.not_shown no world", status(dlg))
    -- A click while the client is gone says so
    socket.args.onreceive(3, "", "")
    click(dlg, "minecraft.chat")
    assert.equal("minecraft.not_connected", status(dlg))
  end)

  it("offers the commands of each preview to copy without Fabric", function()
    local t, dlg = connected(plain)
    rendered(t, dlg)
    local command = '/tellraw @s {"text":"\\U000F0000"}'
    click(dlg, command)
    assert.same({ "clipboard", command }, t.log[#t.log])
    assert.equal("minecraft.copied", status(dlg))
  end)

  it("gives each state of the selected item", function()
    local t = setUp(nil, lampModel)
    t.world.instance = fabric
    local dlg = loaded(t)
    local socket = t.sockets[1]
    socket.args.onreceive(1, "", "")
    socket.args.onreceive(2, '{"ok":true}', "")
    dlg.handlers.tree.onchange({ node="item/nordtal:lamp" })
    local read = dkjson.decode(lampRead)
    read.states = { { label="", give='minecraft:paper[item_model="nordtal:lamp"]' } }
    finish(t, 0, dkjson.encode(read))
    click(dlg, "minecraft.give_plain")
    assert.same({ op="give", item='minecraft:paper[item_model="nordtal:lamp"]', id=1 }, socket.sent[2])
  end)
end)
