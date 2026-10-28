local properties = require("properties")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

local lists = { statuses={ "keep", "final candidate", "placeholder" }, scalings={ "stretch", "tile", "nine_slice", "new" } }

local function glyph(fields)
  local entry = { code="F0000", constant="STAR", name="star", status=nil, effectiveStatus="keep",
                  height=7, ascent=6, about="A star" }
  for k, v in pairs(fields or {}) do entry[k] = v end
  entry.texture = { id="nordtal:badges/star.png", width=7, height=7 }
  return { kind="glyph", entry=entry, texture=entry.texture, font={ font="minecraft:default" },
           block={ kind="block", title="Player badges", status="keep" } }
end

local function cell(fields)
  local entry = { code="E000", constant="CORNER", effectiveStatus="keep", height=9, ascent=8 }
  for k, v in pairs(fields or {}) do entry[k] = v end
  local texture = { id="nordtal:gui/board.png", width=36, height=9 }
  return { kind="cell", entry=entry, texture=texture, font={ font="nordtal:gui" },
           block={ kind="sheet", title="Board", texture=texture } }
end

local function override(fields)
  local entry = { texture={ id="minecraft:gui/sprites/widget/button.png", width=200, height=20 } }
  for k, v in pairs(fields or {}) do entry[k] = v end
  return { kind="override", entry=entry, texture=entry.texture }
end

-- The rows as "key=value", "key=value*" where it can be edited
local function shown(info, coupled)
  local out = {}
  for _, row in ipairs(properties.rows(info, tr, { statuses=lists.statuses, coupled=coupled })) do
    table.insert(out, row.key .. "=" .. tostring(row.value) .. (row.field and "*" or ""))
  end
  return out
end

describe("the properties of an entry", function()
  it("shows font, block and code point of a glyph read-only and edits the rest", function()
    assert.same({
      "font=minecraft:default", "block=Player badges", "code=F0000",
      "name=star*", "constant=STAR", "status=keep*", "height=7*", "ascent=6*",
      "size=properties.pixels 7 7*", "coupled=true*", "about=A star*",
      "texture=nordtal:badges/star.png", "source=properties.no_source", "delete=properties.delete*",
    }, shown(glyph(), true))
  end)

  it("renames a glyph without a name by its constant", function()
    local rows = shown(glyph({ name=false, constant="HALF_MOON" }), true)
    assert.equal("constant=HALF_MOON*", rows[4])
  end)

  it("shows the switch off once the designer decoupled the sizes", function()
    assert.is_truthy(table.concat(shown(glyph(), false), "\n"):find("coupled=false*", 1, true))
  end)

  it("shows an empty about of a glyph that has none, so the designer can write one", function()
    assert.is_truthy(table.concat(shown(glyph({ about=false }), true), "\n"):find("about=*", 1, true))
  end)

  it("keeps the height and ascent of a cell with its sheet and shows the size of the sheet read-only", function()
    assert.same({
      "font=nordtal:gui", "block=Board", "code=E000", "constant=CORNER*", "status=keep*",
      "height=9", "ascent=8", "about=*", "texture=nordtal:gui/board.png", "size=properties.pixels 36 9",
      "source=properties.no_source", "delete=properties.delete*",
    }, shown(cell(), true))
  end)

  it("edits nothing of a cell of ordinary characters, which has no constant", function()
    for _, row in ipairs(shown(cell({ constant=false }), true)) do
      assert.is_falsy(row:find("*", 1, true))
    end
  end)

  it("edits the size, shows the frames and edits the scaling of a GUI sprite", function()
    local info = override({ guiSprite=true, mcmeta="src/button.png.mcmeta",
                            frames={ { x=0, y=0, width=200, height=10, duration=100 },
                                     { x=0, y=10, width=200, height=10, duration=100 } },
                            scaling={ type="nine_slice", width=200, height=20, border=3 } })
    assert.same({
      "mcmeta=src/button.png.mcmeta", "animation=properties.frames 2", "scaling=nine_slice*",
      "texture=minecraft:gui/sprites/widget/button.png", "size=properties.pixels 200 10*",
      "source=properties.no_source",
    }, shown(info, true))
  end)

  it("shows a GUI sprite without a scaling as stretched and a texture elsewhere without one", function()
    assert.is_truthy(table.concat(shown(override({ guiSprite=true }), true), "\n"):find("scaling=stretch*", 1, true))
    assert.is_falsy(table.concat(shown(override({ guiSprite=false }), true), "\n"):find("scaling", 1, true))
  end)
end)

describe("an edit of a property", function()
  it("renames a glyph by its name", function()
    assert.same({ "renameGlyph", { glyph="star", to="big-star" } },
      properties.jobs(glyph(), "name", { value="big-star" }))
  end)

  it("renames a cell by its constant", function()
    assert.same({ "renameGlyph", { glyph="CORNER", to="EDGE" } },
      properties.jobs(cell(), "constant", { value="EDGE" }))
  end)

  it("changes nothing when the value stays", function()
    assert.is_nil(properties.jobs(glyph(), "name", { value="star" }))
    assert.is_nil(properties.jobs(glyph(), "height", { value=7 }))
  end)

  it("writes the status, and the block's status for the first choice", function()
    local form = properties.form(glyph({ status="placeholder" }), "status", tr, lists)
    assert.same({ "properties.from_block keep", "keep", "final candidate", "placeholder" }, form.widgets[1].options)
    assert.equal("placeholder", form.widgets[1].option)
    assert.same({ "editGlyph", { glyph="star", status="" } },
      properties.jobs(glyph({ status="placeholder" }), "status", { value="properties.from_block keep" }, tr))
    assert.same({ "editGlyph", { glyph="star", status="final candidate" } },
      properties.jobs(glyph(), "status", { value="final candidate" }, tr))
  end)

  it("writes the display height, the ascent and the about of a glyph", function()
    assert.same({ "editGlyph", { glyph="star", height=9 } }, properties.jobs(glyph(), "height", { value=9 }))
    assert.same({ "editGlyph", { glyph="star", ascent=5 } }, properties.jobs(glyph(), "ascent", { value=5 }))
    assert.same({ "editGlyph", { glyph="star", about="" } }, properties.jobs(glyph(), "about", { value="" }))
  end)

  it("asks for a number as a whole number", function()
    local form = properties.form(glyph(), "height", tr, lists)
    assert.same({ kind="number", id="value", label="properties.height", text="7", decimals=0 }, form.widgets[1])
  end)

  it("asks for the pixel size and marks one that is no whole multiple of the old as lossy", function()
    local form = properties.form(glyph(), "size", tr, lists)
    assert.same({ "width", "height" }, { form.widgets[1].id, form.widgets[2].id })
    local old = { width=7, height=7 }
    assert.same({ resize={ width=14, height=21 } }, properties.jobs(glyph(), "size", { width=14, height=21 }, tr, old))
    assert.same({ resize={ width=9, height=9, lossy=true } }, properties.jobs(glyph(), "size", { width=9, height=9 }, tr, old))
    assert.same({ resize={ width=14, height=6, lossy=true } }, properties.jobs(glyph(), "size", { width=14, height=6 }, tr, old))
    assert.is_nil(properties.jobs(glyph(), "size", { width=7, height=7 }, tr, old))
  end)

  it("asks for the size of one frame of an animated texture, not the size of its PNG", function()
    -- A PNG 32 by 64 that holds four frames 32 by 16 in a column
    local frames = {}
    for i = 0, 3 do table.insert(frames, { x=0, y=i * 16, width=32, height=16, duration=50 }) end
    local info = override({ animation={ frametime=1 }, frames=frames })
    info.texture.width, info.texture.height = 32, 64
    local form = properties.form(info, "size", tr, lists)
    assert.same({ "32", "16" }, { form.widgets[1].text, form.widgets[2].text })
  end)

  it("deletes a glyph by its name", function()
    assert.same({ "deleteGlyph", { glyph="star" } }, properties.jobs(glyph(), "delete", {}))
  end)

  it("writes a nine-slice scaling with its border as four numbers", function()
    local info = override({ guiSprite=true, scaling={ type="nine_slice", width=200, height=20, border=3 } })
    local form = properties.form(info, "scaling", tr, lists)
    local values = {}
    assert.same(lists.scalings, form.widgets[1].options)
    for _, w in ipairs(form.widgets) do values[w.id] = w.option or w.text or w.selected end
    assert.same({ type="nine_slice", width="200", height="20", left="3", top="3", right="3", bottom="3",
                  stretchInner=false }, values)
    assert.same({ "scaleTexture", { texture="minecraft:gui/sprites/widget/button.png", scaling="nine_slice",
                    width=200, height=20, border="2,3,2,0", stretchInner=true } },
      properties.jobs(info, "scaling", { type="nine_slice", width=200, height=20, left=2, top=3, right=2, bottom=0,
                                         stretchInner=true }))
  end)

  it("reads a border of four sides and writes a tile and a stretch without one", function()
    local info = override({ guiSprite=true, scaling={ type="nine_slice", width=20, height=20,
                                                      border={ left=1, top=2, right=3, bottom=4 } } })
    local form = properties.form(info, "scaling", tr, lists)
    assert.same({ "1", "2", "3", "4" }, { form.widgets[4].text, form.widgets[5].text, form.widgets[6].text, form.widgets[7].text })
    assert.same({ "scaleTexture", { texture="minecraft:gui/sprites/widget/button.png", scaling="tile", width=8, height=8 } },
      properties.jobs(info, "scaling", { type="tile", width=8, height=8, left=1, top=2, right=3, bottom=4 }))
    assert.same({ "scaleTexture", { texture="minecraft:gui/sprites/widget/button.png", scaling="stretch" } },
      properties.jobs(info, "scaling", { type="stretch", width=8, height=8 }))
  end)
end)

describe("a save of a texture", function()
  it("scales the display height of a glyph whose pixel height changed, as the switch says", function()
    assert.same({ "resizeGlyph", { glyph="star", from=7, coupled=true } },
      properties.saved(glyph(), { width=14, height=14, durations={ 100 } }, true))
    assert.same({ "resizeGlyph", { glyph="star", from=7, coupled=false } },
      properties.saved(glyph(), { width=14, height=14, durations={ 100 } }, false))
    assert.is_nil(properties.saved(glyph(), { width=7, height=7, durations={ 100 } }, true))
  end)

  it("writes the animation of an override from the sprite's frames", function()
    assert.same({ "animateTexture", { texture="minecraft:gui/sprites/widget/button.png", frames="100,250" } },
      properties.saved(override(), { width=200, height=20, durations={ 100, 250 } }, true))
  end)

  it("removes the animation of an override that has one frame left, and leaves one it could not read", function()
    assert.same({ "animateTexture", { texture="minecraft:gui/sprites/widget/button.png", frames="100" } },
      properties.saved(override({ animation={ frametime=2 }, frames={ { duration=100 }, { duration=100 } } }),
        { width=200, height=20, durations={ 100 } }, true))
    assert.is_nil(properties.saved(override({ animation={ frametime=2 } }), { width=200, height=20, durations={ 100 } }, true))
    assert.is_nil(properties.saved(override(), { width=200, height=20, durations={ 100 } }, true))
  end)

  it("writes the animation of a texture that an item definition's model draws", function()
    local entry = { key="layer0", texture={ id="nordtal:item/lamp.png", width=16, height=16 } }
    local info = { kind="texture", entry=entry, texture=entry.texture }
    assert.is_true(properties.animated(info))
    assert.is_true(properties.animated(override()))
    assert.is_false(properties.animated(glyph()))
    assert.same({ "animateTexture", { texture="nordtal:item/lamp.png", frames="100,250" } },
      properties.saved(info, { width=16, height=16, durations={ 100, 250 } }, true))
  end)
end)

describe("a text", function()
  local function text(fields)
    local entry = { key="menu.title", values={ en_us="Pause" } }
    for k, v in pairs(fields or {}) do entry[k] = v end
    return { kind="text", entry=entry, languages={ "de_de", "en_us" } }
  end

  local function rows(info)
    local out = {}
    for _, row in ipairs(properties.rows(info, tr, {})) do
      table.insert(out, (row.label or row.key) .. "=" .. tostring(row.value) .. (row.field and "*" or ""))
    end
    return out
  end

  it("shows its key, its asset and its value in each language of the pack, and a button to delete it", function()
    assert.same({
      "key=menu.title", "owner=properties.no_owner", "de_de=properties.no_text*", "en_us=Pause*", "delete_text=properties.delete_text*",
    }, rows(text()))
    assert.equal("owner=nordtal:block/lamp.png", rows(text({ owner="nordtal:block/lamp.png" }))[2])
    assert.equal("fr_fr=Pause*", rows(text({ values={ fr_fr="Pause" } }))[5])
  end)

  it("writes the value of one language, and nothing when it stays or stays unset", function()
    local form = properties.form(text(), "text:en_us", tr, lists)
    assert.equal("Pause", form.widgets[1].text)
    assert.same({ "editText", { key="menu.title", language="de_de", value="Pause!" } },
      properties.jobs(text(), "text:de_de", { value="Pause!" }, tr))
    assert.is_nil(properties.jobs(text(), "text:en_us", { value="Pause" }, tr))
    assert.is_nil(properties.jobs(text(), "text:de_de", { value="" }, tr))
  end)

  it("deletes the key from every language", function()
    assert.equal("label", properties.form(text(), "deleteText", tr, lists).widgets[1].kind)
    assert.same({ "editText", { key="menu.title", delete=true } }, properties.jobs(text(), "deleteText", {}, tr))
  end)

  it("is added on the texts root with a key, a language of the pack and a value", function()
    local root = { kind="texts", languages={ "de_de", "en_us" } }
    assert.same({ "add_text=properties.add_text*" }, rows(root))
    local form = properties.form(root, "addText", tr, lists)
    assert.same({ "key", "language", "value" }, { form.widgets[1].id, form.widgets[2].id, form.widgets[3].id })
    assert.same({ "de_de", "en_us" }, form.widgets[2].options)
    assert.same({ "editText", { key="menu.help", language="en_us", value="Help" } },
      properties.jobs(root, "addText", { key="menu.help", language="en_us", value="Help" }, tr))
    assert.is_nil(properties.jobs(root, "addText", { key="", language="en_us", value="" }, tr))
  end)
end)
