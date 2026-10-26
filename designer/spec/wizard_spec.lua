local wizard = require("wizard")

local model = {
  fonts={
    { font="minecraft:default", blocks={
      { kind="block", title="Player badges", glyphs={} },
      { kind="sheet", title="Icons", cells={} },
      { kind="block", title="Ranks", glyphs={} },
    } },
    { font="nordtal:hud", blocks={} },
  },
}

describe("the new-texture wizard", function()
  it("offers every font and the blocks of a font that take glyphs of their own", function()
    assert.same({ "minecraft:default", "nordtal:hud" }, wizard.fonts(model))
    assert.same({ "Player badges", "Ranks" }, wizard.blocks(model, "minecraft:default"))
    assert.same({}, wizard.blocks(model, "nordtal:hud"))
    assert.same({}, wizard.blocks(model, "nordtal:none"))
  end)

  it("asks each kind for the options of its task", function()
    assert.same({ "font", "block", "namespace", "path", "name", "constant", "width", "height", "ascent" }, wizard.fields.glyph)
    assert.same({ "font", "title", "size", "height", "ascent" }, wizard.fields.block)
    assert.same({ "font", "title", "namespace", "path", "constants", "width", "height", "ascent" }, wizard.fields.sheet)
    assert.same({ "namespace", "path", "width", "height" }, wizard.fields.item)
  end)

  it("turns the values into a task and its options, without the empty ones", function()
    assert.same({ "createGlyph", { font="minecraft:default", block="Ranks", texture="nordtal:ranks/gold.png", name="gold",
                                   width="7", height="7" } },
      wizard.job("glyph", { font="minecraft:default", block="Ranks", namespace="nordtal", path=" ranks/gold ", name="gold",
                            constant="", width="7", height="7", ascent="" }))
    assert.same({ "createGlyphBlock", { font="nordtal:hud", title="Arrows", size="16" } },
      wizard.job("block", { font="nordtal:hud", title="Arrows", size="16", height="", ascent="" }))
    assert.same({ "createGlyphSheet", { font="nordtal:hud", title="Arrows", texture="nordtal:hud/arrows.png",
                                        constants="LEFT,RIGHT", width="6", height="5", ascent="-4" } },
      wizard.job("sheet", { font="nordtal:hud", title="Arrows", namespace="nordtal", path="hud/arrows.png",
                            constants="LEFT,RIGHT", width="6", height="5", ascent="-4" }))
    assert.same({ "createItem", { item="nordtal:wand", width="16" } },
      wizard.job("item", { namespace="nordtal", path="wand", width="16", height="" }))
    assert.same({ "copyVanillaTexture", { texture="minecraft:block/stone.png" } },
      wizard.job("override", { texture="minecraft:block/stone.png", search="sto" }))
    assert.same({ "editItem", { item="minecraft:compass" } },
      wizard.job("vanilla_item", { item="minecraft:compass", search="comp" }))
  end)

  it("leaves out an id whose path is empty, so the task asks for it", function()
    assert.same({ "createItem", {} }, wizard.job("item", { namespace="", path="" }))
    assert.same({ "createItem", {} }, wizard.job("item", { namespace="nordtal", path=" " }))
  end)

  it("reads the field that a refusal names, and shows the id's refusal at its path", function()
    assert.equal("width", wizard.refused({ "refused width" }))
    assert.equal("namespace", wizard.refused({ "/c/x.png", "refused namespace" }))
    assert.equal("path", wizard.refused({ "refused texture" }))
    assert.equal("path", wizard.refused({ "refused item" }))
    assert.is_nil(wizard.refused({ "/c/x.png" }))
  end)

  it("knows a refusal of an option that the form left empty", function()
    assert.is_true(wizard.absent({ "refused title" }, { "createGlyphBlock", { font="nordtal:hud" } }))
    assert.is_false(wizard.absent({ "refused title" }, { "createGlyphBlock", { title="A b" } }))
    assert.is_false(wizard.absent({ "/c/x.png" }, { "createGlyphBlock", {} }))
    -- The id is one option, so a refusal of its namespace or path is the task's
    local job = wizard.job("glyph", { namespace="Nordtal", path="badges/sun" })
    assert.is_false(wizard.absent({ "refused namespace" }, job))
    assert.is_false(wizard.absent({ "refused path" }, job))
    assert.is_true(wizard.absent({ "refused path" }, wizard.job("glyph", { namespace="nordtal", path="" })))
  end)

  it("finds the node of what it created", function()
    local index = {
      ["glyph/minecraft:default/F0001"]={ kind="glyph", texture={ id="nordtal:ranks/gold.png" } },
      ["block/minecraft:default/3"]={ kind="block", entry={ title="Ranks" }, font={ font="minecraft:default" } },
      ["block/nordtal:hud/1"]={ kind="block", entry={ title="Ranks" }, font={ font="nordtal:hud" } },
      ["sheet/nordtal:hud/2"]={ kind="sheet", texture={ id="nordtal:hud/arrows.png" } },
      ["cell/nordtal:hud/FE041"]={ kind="cell", texture={ id="nordtal:hud/arrows.png" } },
      ["item/nordtal:wand"]={ kind="item", entry={ id="nordtal:wand" } },
      ["override/minecraft:block/stone.png"]={ kind="override", texture={ id="minecraft:block/stone.png" } },
    }
    assert.equal("glyph/minecraft:default/F0001",
      wizard.target(index, { "createGlyph", { texture="nordtal:ranks/gold.png" } }))
    assert.equal("block/nordtal:hud/1", wizard.target(index, { "createGlyphBlock", { font="nordtal:hud", title="Ranks" } }))
    assert.equal("sheet/nordtal:hud/2", wizard.target(index, { "createGlyphSheet", { texture="nordtal:hud/arrows.png" } }))
    assert.equal("item/nordtal:wand", wizard.target(index, { "createItem", { item="nordtal:wand" } }))
    assert.equal("override/minecraft:block/stone.png",
      wizard.target(index, { "copyVanillaTexture", { texture="minecraft:block/stone.png" } }))
    assert.equal("item/nordtal:wand", wizard.target(index, { "editItem", { item="nordtal:wand" } }))
    assert.is_nil(wizard.target(index, { "createItem", { item="nordtal:lamp" } }))
  end)

  local textures = {
    "minecraft:block/stone.png", "minecraft:block/stone_bricks.png", "minecraft:block/dirt.png",
    "minecraft:item/stone_sword.png", "minecraft:gui/sprites/hud/heart/full.png",
  }

  it("lists the client jar's textures by folder, with the words of the search in any order", function()
    local nodes, count = wizard.matches(textures, "", 10)
    assert.equal(5, count)
    assert.same({ "block", "gui/sprites/hud/heart", "item" }, { nodes[1].text, nodes[2].text, nodes[3].text })
    assert.same({ "dirt", "stone", "stone_bricks" },
      { nodes[1].children[1].text, nodes[1].children[2].text, nodes[1].children[3].text })
    assert.equal("minecraft:block/dirt.png", nodes[1].children[1].id)
    assert.is_falsy(nodes[1].expanded)

    nodes, count = wizard.matches(textures, "STONE sword", 10)
    assert.equal(1, count)
    assert.same("item", nodes[1].text)
    assert.same("minecraft:item/stone_sword.png", nodes[1].children[1].id)
    assert.is_true(nodes[1].expanded)
  end)

  it("lists an id without a folder at the top, after the folders", function()
    local nodes, count = wizard.matches({ "minecraft:compass", "minecraft:apple", "minecraft:block/dirt.png" }, "", 10)
    assert.equal(3, count)
    assert.same({ { id="folder/block", text="block" }, { id="minecraft:apple", text="apple" },
                  { id="minecraft:compass", text="compass" } },
      { { id=nodes[1].id, text=nodes[1].text }, nodes[2], nodes[3] })
  end)

  it("shows at most limit textures and counts every match", function()
    local nodes, count = wizard.matches(textures, "stone", 2)
    assert.equal(3, count)
    assert.equal(1, #nodes)
    assert.equal(2, #nodes[1].children)
  end)

  it("knows a folder's node from a choice's", function()
    local nodes = wizard.matches(textures, "", 10)
    assert.is_false(wizard.isChoice(nodes[1].id))
    assert.is_true(wizard.isChoice(nodes[1].children[1].id))
  end)

  it("offers the client jar's textures, and its items that the pack does not override", function()
    local listing = { textures={ { id="minecraft:block/stone.png" } },
                      items={ "minecraft:apple", "minecraft:compass", "minecraft:stone" } }
    local pack = { items={ { category="minecraft", entries={ { id="minecraft:compass" } } },
                           { category="nordtal", entries={ { id="nordtal:wand" } } } } }
    assert.same({ "minecraft:block/stone.png" }, wizard.choices("override", listing, pack))
    assert.same({ "minecraft:apple", "minecraft:stone" }, wizard.choices("vanilla_item", listing, pack))
    assert.same({}, wizard.choices("vanilla_item", { textures={} }, {}))
  end)

  it("breaks a reason into lines of at most width characters, at spaces", function()
    assert.same({ "The path of", "nordtal:X.png may", "hold only a-z." }, wizard.wrap("The path of nordtal:X.png may hold only a-z.", 17))
    assert.same({ "averyveryverylongword", "x" }, wizard.wrap("averyveryverylongword x", 10))
  end)
end)
