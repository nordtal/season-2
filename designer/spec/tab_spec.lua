local tab = require("tab")
local dkjson = require("dkjson")

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

-- A Dialog that records the widgets it was given and every modify
local function fakeDialogs()
  local dialogs = {}
  local function Dialog(args)
    -- Like Dialog, an assignment to data sets the values it names
    local dlg = setmetatable({ args=args, widgets={}, modified={}, values={}, shown=0, closed=false, handlers={},
                              sizeHint={ width=300, height=200 } }, {
      __index=function(t, k) if k == "data" then return rawget(t, "values") end end,
      __newindex=function(t, k, v)
        if k ~= "data" then return rawset(t, k, v) end
        for name, value in pairs(v) do t.values[name] = value end
      end,
    })
    local function add(kind)
      return function(self, t)
        t = t or {}
        table.insert(self.widgets, { kind=kind, args=t })
        if t.id then self.handlers[t.id] = t end
        if kind == "editor" then self.data[t.id] = t.sprite end
        return self
      end
    end
    for _, kind in ipairs({ "tree", "editor", "label", "separator", "canvas", "entry", "number", "combobox", "check",
                            "button", "file", "radio" }) do
      dlg[kind] = add(kind)
    end
    function dlg:samerow() table.insert(self.widgets, { kind="samerow" }) return self end
    function dlg:newrow() table.insert(self.widgets, { kind="newrow" }) return self end
    function dlg:repaint() self.repainted = (self.repainted or 0) + 1 end
    -- A form waits for the answer the test gives
    function dlg:show(showArgs)
      self.shown = self.shown + 1
      self.showArgs = showArgs
      -- Where the app put the dialog, and how large it made it
      if dialogs.bounds then self.bounds = dialogs.bounds(self) end
      if not (args and args.tab) and dialogs.answer then dialogs.answer(self) end
      return self
    end
    function dlg:close() self.closed = true end
    function dlg:modifyNode(t) self.nodesModified = self.nodesModified or {} table.insert(self.nodesModified, t) end
    function dlg:modify(t)
      table.insert(self.modified, t)
      if t.id and self.handlers[t.id] then
        for k, v in pairs(t) do self.handlers[t.id][k] = v end
        if t.sprite ~= nil then self.data[t.id] = t.sprite or nil end
      end
    end
    table.insert(dialogs, dlg)
    return dlg
  end
  return Dialog, dialogs
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
  local Dialog, dialogs = fakeDialogs()
  local started, log, open, timers = {}, {}, {}, {}
  local world = { active=nil }
  local api = {
    Dialog=Dialog,
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
      if path == "/c/resource-pack/build/pack-model.json" then return modelText or model end
      if path == "/c/resource-pack/build/vanilla-textures.json" then return world.vanilla end
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
               wizard=require("wizard"), drafts=require("drafts"), conflicts=require("conflicts") },
    bootstrap={ clone=clone, env={ JAVA_HOME="/jdk" }, git="/usr/bin/git", gh="/gh" },
    preferences=preferences or {},
    tr=function(id, ...) return table.concat({ id, ... }, " ") end,
  }
  return { ctl=ctl, dialogs=dialogs, started=started, log=log, open=open, world=world, timers=timers }
end

local function kinds(dlg)
  local out = {}
  for _, w in ipairs(dlg.widgets) do
    if w.kind ~= "label" and w.kind ~= "newrow" then table.insert(out, w.kind) end
  end
  return out
end

-- The lines the properties panel draws, as "key" or "  value"
local function texts(dlg, height)
  local out, at, color = {}, {}, nil
  local theme = { color={ text="text", separator_label="key", face="face" } }
  local gc = { width=1000, height=height or 1000, theme=theme }
  setmetatable(gc, { __newindex=function(t, k, v) if k == "color" then color = v else rawset(t, k, v) end end })
  function gc:measureText(text) return { width=utf8.len(text), height=1 } end
  function gc:fillRect() table.insert(out, "fill " .. color) end
  function gc:fillText(text, x, y)
    table.insert(out, (color == "key" and "" or "  ") .. text)
    at[text] = at[text] or { x=x, y=y }
  end
  function gc:drawThemeRect() end
  function gc:drawThemeImage() end
  dlg.handlers.properties.onpaint({ context=gc })
  return out, at
end

-- Clicks the properties panel on a text it draws
local function click(dlg, text, height)
  local _, drawn = texts(dlg, height)
  local at = drawn[text]
  assert(at, text .. " is not drawn")
  dlg.handlers.properties.onmousedown({ x=at.x, y=at.y })
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

  it("puts the tree left of the editor by default", function()
    local t = setUp()
    assert.same({ "tree", "samerow", "editor", "samerow", "canvas" }, kinds(t.dialogs[1]))
  end)

  it("gives the tree and the panel a width of their own, so the editor takes the spare room", function()
    local t = setUp()
    local widths = {}
    for _, w in ipairs(t.dialogs[1].widgets) do
      if w.kind == "tree" or w.kind == "canvas" or w.kind == "editor" then
        table.insert(widths, { w.kind, w.args.width, w.args.hexpand })
      end
    end
    assert.same({ { "tree", 150, false }, { "editor" }, { "canvas", 120, false } }, widths)
  end)

  it("puts the tree right of the editor when the setting says so", function()
    local t = setUp({ treeSide="right" })
    assert.same({ "canvas", "samerow", "editor", "samerow", "tree" }, kinds(t.dialogs[1]))
  end)

  it("moves the tree to the other side and keeps the sprite and the nodes", function()
    local preferences = {}
    local t = setUp(preferences)
    local first = loaded(t)
    first.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.ctl.setSide("right")
    assert.equal("right", preferences.treeSide)
    assert.is_true(first.closed)
    local second = t.dialogs[#t.dialogs]
    assert.same({ "canvas", "samerow", "editor", "samerow", "tree" }, kinds(second))
    assert.equal(t.open[1], second.data.editor)
    assert.equal("glyphs", second.handlers.tree.nodes[1].id)
    assert.equal("glyph/minecraft:default/F0000", second.data.tree)
    assert.equal(1, second.shown)
    assert.equal(2, t.world.fitted)
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
    assert.same({ "fill face", "  properties.none" }, texts(dlg))
  end)

  it("shows that it reads the pack, then the pack's nodes", function()
    local t = setUp()
    local dlg = t.dialogs[1]
    assert.equal("status.loading", dlg.handlers.status.text)
    loaded(t)
    assert.equal("glyphs", dlg.handlers.tree.nodes[1].id)
    assert.is_false(dlg.handlers.status.visible)
  end)

  -- A label draws one line, so the reason follows the text on it
  it("shows why the pack could not be read", function()
    local t = setUp()
    t.started[1].args.onstderr("FAILURE: Build failed")
    t.started[1].args.onstderr("glyphs.json > nordtal:gui: overlaps")
    t.started[1].args.onexit(1)
    assert.equal("status.failed FAILURE: Build failed glyphs.json > nordtal:gui: overlaps", t.dialogs[1].handlers.status.text)
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
    assert.equal("status.outside /Library/out.png", dlg.handlers.status.text)
  end)

  it("clears the reason of the last entry when another one is selected", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="override/minecraft:gui/sprites/container/out.png" })
    dlg.handlers.tree.onchange({ node="glyphs" })
    assert.is_false(dlg.handlers.status.visible)
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
    assert.equal("status.loading", dlg.handlers.status.text)
    assert.is_not_false(dlg.handlers.status.visible)
  end)

  it("shows why the pack's nodes could not be built", function()
    local t = setUp(nil, dkjson.encode({ fonts={ { font="nordtal:gui", blocks={
      { kind="sheet", title="Row text", texture={ id="nordtal:a.png", png="src/a.png", aseprite="art/a.aseprite" },
        cells={ { row=0, column=0, code="ZZ" } } } } } }, items={}, overrides={} }))
    local dlg = loaded(t)
    assert.equal("status.failed nordtal:gui > Row text: ZZ is no code point", dlg.handlers.status.text)
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
    assert.same({ "fill face", "  properties.none" }, texts(dlg))
    -- A click on the same entry opens it again
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    assert.equal(t.open[1], dlg.data.editor)
  end)

  it("ignores the editor of a dialog the tab replaced", function()
    local t = setUp()
    local first = loaded(t)
    first.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    t.ctl.setSide("right")
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
    assert.equal("status.writing", dlg.handlers.status.text)
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
    assert.is_false(dlg.handlers.status.visible)
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
    assert.equal("status.refused star has no block", dlg.handlers.status.text)
    assert.is_true(dlg.handlers.status.visible)
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
    assert.is_truthy(dlg.handlers.status.text:find("^status.not_started .*cannot start java"))
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
    assert.equal("status.busy", dlg.handlers.status.text)
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
    assert.equal(":resource-pack:animateTexture -Pframes=100,200 -Ptexture=minecraft:gui/sprites/container/slot.png",
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
    -- The button is 9 high with its text 3 below its top, and ends at the margin
    assert.equal(20 - 4 - 9 + 3, at["properties.delete"].y)
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
        table.insert(out, "label " .. tostring(w.args.text))
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
    assert.same({ "new.kind_glyph", "new.kind_block", "new.kind_sheet", "new.kind_item", "new.kind_override" },
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
    assert.equal(":resource-pack:listVanillaTextures", run(t.started[2]))
    assert.same({ "combobox kind", "label new.reading", "button create", "button cancel" }, layout(t.dialogs[#t.dialogs]))
    t.started[2].args.onexit(0)
    local browser = t.dialogs[#t.dialogs]
    assert.same({ "combobox kind", "entry search", "tree textures", "label new.count 2", "label new.chosen_none",
                  "button create", "button cancel" }, layout(browser))
    browser.values.search = "sto"
    browser.handlers.search.onchange()
    assert.same("minecraft:block/stone.png", browser.handlers.textures.nodes[1].children[1].id)
    browser.handlers.textures.onchange({ node="minecraft:block/stone.png" })
    browser.handlers.create.onclick()
    assert.equal(":resource-pack:copyVanillaTexture -Ptexture=minecraft:block/stone.png", run(t.started[3]))
    t.started[3].args.onexit(0)
    t.ctl.create()
    t.dialogs[#t.dialogs].values.kind = "new.kind_override"
    t.dialogs[#t.dialogs].handlers.kind.onchange()
    assert.equal(":resource-pack:describePack", run(t.started[4]))
    assert.is_nil(t.started[5])
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
    assert.equal("status.published https://github.com/nordtal/season-2/pull/42", t.dialogs[1].handlers.status.text)
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
    assert.equal("status.refused the star is too bright", t.dialogs[1].handlers.status.text)
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
    assert.equal("status.merged 42", t.dialogs[1].handlers.status.text)
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
    assert.equal("status.restored", t.dialogs[1].handlers.status.text)
  end)

  it("asks GitHub after the first read while the pack has drafts, and reads the pack again when main moved or a review changed", function()
    local t = setUp(nil, draftModel)
    loaded(t)
    synced(t, "moved")
    assert.equal(":resource-pack:describePack", run(t.started[3]))
    t.started[3].isRunning = false
    t.started[3].args.onexit(0)
    assert.is_false(t.dialogs[1].handlers.status.visible)
    for _, timer in ipairs(t.timers) do
      if timer.args.interval == 300 then timer.args.ontick() end
    end
    synced(t, "reviewed 42")
    t.started[5].isRunning = false
    t.started[5].args.onexit(0)
    assert.equal("status.reviewed 42", t.dialogs[1].handlers.status.text)
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
    assert.equal("status.yours /c/resource-pack/build/conflict/yours", dlg.handlers.status.text)
  end)
end)
