local tab = require("tab")
local dkjson = require("dkjson")

local clone = "/c"
local star = "/c/resource-pack/src/assets/nordtal/textures/badges/star.png"
local starSource = "/c/resource-pack/art/assets/nordtal/textures/badges/star.aseprite"

local model = dkjson.encode({
  fonts={ { font="minecraft:default", constant="DEFAULT", blocks={
    { kind="block", title="Player badges", range="F0000-F000F", status="keep", glyphs={
      { code="F0000", constant="STAR", name="star", height=7, ascent=6,
        texture={ id="nordtal:badges/star.png", png="src/assets/nordtal/textures/badges/star.png",
                  aseprite="art/assets/nordtal/textures/badges/star.aseprite", asepriteExists=false,
                  width=7, height=7 } },
    } } } } },
  items={}, overrides={},
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
    for _, kind in ipairs({ "tree", "editor", "label", "separator" }) do dlg[kind] = add(kind) end
    function dlg:samerow() table.insert(self.widgets, { kind="samerow" }) return self end
    function dlg:newrow() table.insert(self.widgets, { kind="newrow" }) return self end
    function dlg:show() self.shown = self.shown + 1 return self end
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
  local sprite = { filename=filename, spec={}, palettes={ "palette" } }
  function sprite:saveAs(fn) table.insert(log, { "saveAs", fn }) self.filename = fn end
  return sprite
end

local function setUp(preferences)
  local Dialog, dialogs = fakeDialogs()
  local started, log, open = {}, {}, {}
  local world = { active=nil }
  local api = {
    Dialog=Dialog,
    Process=function(args)
      local p = { args=args }
      function p:start() table.insert(started, self) end
      return p
    end,
    windows=false,
    join=function(...) return table.concat({ ... }, "/") end,
    read=function(path)
      if path == "/c/resource-pack/build/pack-model.json" then return model end
    end,
    decode=dkjson.decode,
    sprites=function() return open end,
    isFile=function(fn) return fn == star end,
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
  }
  local ctl = tab.open{
    api=api,
    modules={ pack=require("pack"), tree=require("tree"), textures=require("textures") },
    bootstrap={ clone=clone, env={} },
    preferences=preferences or {},
    tr=function(id, ...) return table.concat({ id, ... }, " ") end,
  }
  return { ctl=ctl, dialogs=dialogs, started=started, log=log, open=open, world=world }
end

local function kinds(dlg)
  local out = {}
  for _, w in ipairs(dlg.widgets) do
    if w.kind ~= "label" and w.kind ~= "newrow" then table.insert(out, w.kind) end
  end
  return out
end

local function texts(dlg)
  local out = {}
  for _, w in ipairs(dlg.widgets) do
    if w.kind == "label" and w.args.visible ~= false and w.args.text ~= "" then
      table.insert(out, w.args.text)
    end
  end
  return out
end

local function loaded(t)
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
    assert.same({ "tree", "samerow", "editor" }, kinds(t.dialogs[1]))
  end)

  it("puts the tree right of the editor when the setting says so", function()
    local t = setUp({ treeSide="right" })
    assert.same({ "editor", "samerow", "tree" }, kinds(t.dialogs[1]))
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
    assert.same({ "editor", "samerow", "tree" }, kinds(second))
    assert.equal(t.open[1], second.data.editor)
    assert.equal("glyphs", second.handlers.tree.nodes[1].id)
    assert.equal("glyph/minecraft:default/F0000", second.data.tree)
    assert.equal(1, second.shown)
  end)

  it("shows that it reads the pack, then the pack's nodes", function()
    local t = setUp()
    local dlg = t.dialogs[1]
    assert.equal("status.loading", dlg.handlers.status.text)
    loaded(t)
    assert.equal("glyphs", dlg.handlers.tree.nodes[1].id)
    assert.is_false(dlg.handlers.status.visible)
  end)

  it("shows why the pack could not be read", function()
    local t = setUp()
    t.started[1].args.onstderr("FAILURE: Build failed")
    t.started[1].args.onexit(1)
    assert.equal("status.failed\nFAILURE: Build failed", t.dialogs[1].handlers.status.text)
  end)

  it("opens a clicked texture in the embedded editor and shows its properties", function()
    local t = setUp()
    local dlg = loaded(t)
    dlg.handlers.tree.onchange({ node="glyph/minecraft:default/F0000" })
    assert.equal(1, #t.open)
    assert.equal(star, t.open[1].filename)
    assert.equal(t.open[1], dlg.data.editor)
    local shown = texts(dlg)
    assert.is_truthy(table.concat(shown, "\n"):find("properties.row properties.code F0000", 1, true))
    assert.is_truthy(table.concat(shown, "\n"):find("properties.row properties.name star", 1, true))
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
end)
