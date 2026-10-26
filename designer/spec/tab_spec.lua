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
    local dlg = setmetatable({ args=args, widgets={}, modified={}, values={}, shown=0, closed=false, handlers={} }, {
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
                            "button" }) do
      dlg[kind] = add(kind)
    end
    function dlg:samerow() table.insert(self.widgets, { kind="samerow" }) return self end
    function dlg:newrow() table.insert(self.widgets, { kind="newrow" }) return self end
    function dlg:repaint() self.repainted = (self.repainted or 0) + 1 end
    -- A form waits for the answer the test gives
    function dlg:show()
      self.shown = self.shown + 1
      if not (args and args.tab) and dialogs.answer then dialogs.answer(self) end
      return self
    end
    function dlg:close() self.closed = true end
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
      function p:start() self.isRunning = true table.insert(started, self) end
      function p:stop() self.isRunning = false self.stopped = true end
      return p
    end,
    windows=false,
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
    end,
    -- Aseprite's json.decode gives every number as a float
    decode=function(text) return floats(dkjson.decode(text)) end,
    sprites=function() return open end,
    isFile=function(fn) return fn == star or fn == "/c/resource-pack/src/slot.png" end,
    open=function(fn)
      local sprite = fakeSprite(fn, log)
      table.insert(open, sprite)
      return sprite
    end,
    filePath=function(fn) return (fn:match("^(.*)/[^/]*$")) end,
    makeAllDirectories=function() end,
    Image=function()
      local image = {}
      function image:drawSprite() end
      function image:saveAs(t) table.insert(log, { "png", t.filename }) end
      return image
    end,
    activeSprite=function() return world.active end,
    alert=function(t) table.insert(log, { "alert", t.text }) return world.confirm or 2 end,
    fitScreen=function() world.fitted = (world.fitted or 0) + 1 end,
  }
  local ctl = tab.open{
    api=api,
    modules={ pack=require("pack"), tree=require("tree"), textures=require("textures"),
               panel=require("panel"), properties=require("properties") },
    bootstrap={ clone=clone, env={} },
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

-- The -P options and the task of a started gradlew
local function run(p)
  return table.concat(p.args.cmd, " ", 4)
end

local function loaded(t)
  t.started[1].isRunning = false
  t.started[1].args.onexit(0)
  return t.dialogs[#t.dialogs]
end

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
end)
