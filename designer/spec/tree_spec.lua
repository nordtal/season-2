local tree = require("tree")
local drafts = require("drafts")

local function tr(id) return "<" .. id .. ">" end

local function texture(path)
  return { id="nordtal:" .. path, png="src/assets/nordtal/textures/" .. path,
           aseprite="art/assets/nordtal/textures/" .. path:gsub("%.png$", ".aseprite"),
           asepriteExists=false, width=8, height=8 }
end

local model = {
  fonts = {
    { font="minecraft:default", constant="DEFAULT", blocks={
      { kind="block", title="Player badges", range="F0000-F000F", status="keep", glyphs={
        { code="F0000", constant="STAR", name="star", status=nil, height=7, ascent=6, texture=texture("badges/star.png") },
        { code="F0001", constant="MOON", name="moon", height=7, ascent=6, texture=texture("badges/moon.png") },
      } },
    } },
    { font="nordtal:gui", blocks={
      { kind="sheet", title="Row text", range="F1000-F100F", height=5, ascent=-10,
        texture=texture("ui/gui/row_text.png"),
        cells={ { row=0, column=0, code="41" }, { row=0, column=1, code="F1001", constant="BAR" } } },
    } },
  },
  items = {
    { category="nordtal", entries={
      { id="nordtal:wand", file="src/assets/nordtal/items/wand.json", model="minecraft:model", texture=texture("item/wand.png") },
      { id="nordtal:lamp", file="src/assets/nordtal/items/lamp.json", model="minecraft:select", texture=nil },
    } },
  },
  overrides = {
    { category="gui/sprites/boss_bar", entries={
      { texture={ id="minecraft:gui/sprites/boss_bar/white_background.png",
                  png="src/assets/minecraft/textures/gui/sprites/boss_bar/white_background.png" } },
    } },
  },
}

local function texts(nodes)
  local out = {}
  for _, node in ipairs(nodes) do
    local entry = { node.text }
    if node.children then entry[2] = texts(node.children) end
    table.insert(out, entry)
  end
  return out
end

describe("tree", function()
  it("lists glyphs by font, block and glyph, a sheet as one entry with cells, then items and overrides by category", function()
    local nodes = tree.build(model, tr, drafts.id)
    assert.same({
      { "<tree.glyphs>", {
        { "minecraft:default", { { "Player badges", { { "star" }, { "moon" } } } } },
        { "nordtal:gui", { { "Row text", { { "A" }, { "BAR" } } } } },
      } },
      { "<tree.items>", { { "nordtal", { { "nordtal:wand" }, { "nordtal:lamp" } } } } },
      { "<tree.overrides>", { { "gui/sprites/boss_bar", { { "white_background" } } } } },
    }, texts(nodes))
  end)

  it("gives every node an id of its own", function()
    local seen, duplicates = {}, {}
    local function walk(nodes)
      for _, node in ipairs(nodes) do
        if seen[node.id] then table.insert(duplicates, node.id) end
        seen[node.id] = true
        walk(node.children or {})
      end
    end
    walk(tree.build(model, tr, drafts.id))
    assert.same({}, duplicates)
  end)

  it("shows a glyph's code point as its badge and opens the top level", function()
    local nodes = tree.build(model, tr, drafts.id)
    local star = nodes[1].children[1].children[1].children[1]
    assert.equal("F0000", star.badge)
    assert.is_true(nodes[1].expanded)
    assert.is_true(nodes[2].expanded)
  end)

  it("indexes each node with what it shows and the texture a click opens", function()
    local nodes, index = tree.build(model, tr, drafts.id)
    local font = nodes[1].children[2]
    local sheet = font.children[1]
    local cell = sheet.children[2]
    assert.equal("sheet", index[sheet.id].kind)
    assert.equal("nordtal:ui/gui/row_text.png", index[sheet.id].texture.id)
    assert.equal("cell", index[cell.id].kind)
    assert.equal("nordtal:ui/gui/row_text.png", index[cell.id].texture.id)
    assert.equal("BAR", index[cell.id].entry.constant)
    assert.equal("nordtal:gui", index[cell.id].font.font)

    local lamp = nodes[2].children[1].children[2]
    assert.equal("item", index[lamp.id].kind)
    assert.is_nil(index[lamp.id].texture)
    assert.is_nil(index[font.id].texture)
  end)

  it("marks the node of a draft texture with the icon of a modified tab", function()
    local drafted = texture("badges/moon.png")
    drafted.draft = drafted.id
    local sheet = texture("ui/gui/row_text.png")
    sheet.draft = sheet.id
    model.fonts[1].blocks[1].glyphs[2].texture = drafted
    model.fonts[2].blocks[1].texture = sheet
    local nodes = tree.build(model, tr, drafts.id)
    model.fonts[1].blocks[1].glyphs[2].texture = texture("badges/moon.png")
    model.fonts[2].blocks[1].texture = texture("ui/gui/row_text.png")
    local block = nodes[1].children[1].children[1]
    local rows = nodes[1].children[2].children[1]
    assert.same({ nil, "tab_modified_icon_normal" }, { block.children[1].icon, block.children[2].icon })
    assert.equal("tab_modified_icon_normal", rows.icon)
    assert.is_nil(rows.children[1].icon)
    assert.is_nil(nodes[2].children[1].children[1].icon)
  end)

  it("marks a block or a font whose own fields are a draft", function()
    model.fonts[1].draft = "font minecraft:default"
    model.fonts[1].blocks[1].draft = "block minecraft:default F0000-F000F"
    local nodes = tree.build(model, tr, drafts.id)
    model.fonts[1].draft, model.fonts[1].blocks[1].draft = nil, nil
    local font = nodes[1].children[1]
    assert.equal("tab_modified_icon_normal", font.icon)
    assert.equal("tab_modified_icon_normal", font.children[1].icon)
    assert.is_nil(font.children[1].children[1].icon)
    assert.is_nil(nodes[1].children[2].icon)
  end)

  it("gives each node the count of the drafts inside it, so a collapsed one shows them", function()
    local drafted = texture("badges/moon.png")
    drafted.draft = drafted.id
    model.fonts[1].blocks[1].glyphs[2].texture = drafted
    model.fonts[1].blocks[1].draft = "block minecraft:default F0000-F000F"
    local nodes = tree.build(model, tr, drafts.id)
    model.fonts[1].blocks[1].glyphs[2].texture = texture("badges/moon.png")
    model.fonts[1].blocks[1].draft = nil
    local font = nodes[1].children[1]
    assert.equal("2", font.badge)
    assert.equal("1", font.children[1].badge)
    assert.equal("2", nodes[1].badge)
    assert.is_nil(nodes[1].children[2].badge)
    assert.is_nil(nodes[2].badge)
  end)

  it("names a cell whose code is no code point", function()
    local bad = { fonts={ { font="nordtal:gui", blocks={
      { kind="sheet", title="Row text", texture=texture("a.png"), cells={ { row=0, column=0, code="ZZ" } } },
    } } } }
    assert.has_error(function() tree.build(bad, tr, drafts.id) end, "nordtal:gui > Row text: ZZ is no code point")
  end)
end)
