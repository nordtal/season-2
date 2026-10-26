local items = require("items")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

local function f(name, kind, required) return { name=name, kind=kind, required=required or false } end

-- A part of the tables that readItem gives as types
local types = {
  item={ f("model", "model", true), f("oversized_in_gui", "boolean") },
  case={ f("when", "json", true), f("model", "model", true) },
  entry={ f("threshold", "number", true), f("model", "model", true) },
  models={
    { type="minecraft:empty", fields={} },
    { type="minecraft:model", fields={ f("model", "id", true), f("tints", "tints"), f("transformation", "json") } },
    { type="minecraft:select", properties="select",
      fields={ f("property", "property", true), f("cases", "cases", true), f("fallback", "model") } },
    { type="minecraft:condition", properties="condition",
      fields={ f("property", "property", true), f("on_true", "model", true), f("on_false", "model", true) } },
    { type="minecraft:range_dispatch", properties="range_dispatch",
      fields={ f("property", "property", true), f("entries", "entries", true), f("scale", "number"),
               f("fallback", "model") } },
    { type="minecraft:special", fields={ f("model", "special", true), f("base", "id", true) } },
  },
  select={ { type="minecraft:display_context", fields={} },
           { type="minecraft:block_state", fields={ f("block_state_property", "text", true) } } },
  condition={ { type="minecraft:using_item", fields={} } },
  range_dispatch={ { type="minecraft:damage", fields={ f("normalize", "boolean") } } },
  tints={ { type="minecraft:constant", fields={ f("value", "json", true) } },
          { type="minecraft:dye", fields={ f("default", "json", true) } } },
  special={ { type="minecraft:bell", fields={} },
            { type="minecraft:head", fields={ f("kind", "text", true) } } },
}

local lampGui = { id="nordtal:item/lamp_gui.png", png="src/assets/nordtal/textures/item/lamp_gui.png",
                  aseprite="art/assets/nordtal/textures/item/lamp_gui.aseprite", width=16, height=16 }

local function read()
  return {
    item="nordtal:lamp", source="pack", file="/c/resource-pack/src/assets/nordtal/items/lamp.json", problems={},
    definition={ model={
      type="minecraft:select", property="minecraft:display_context",
      cases={ { when="gui", model={ type="minecraft:model", model="nordtal:item/lamp_gui" } },
              { when={ "ground", "fixed" }, model={ type="minecraft:empty" } } },
      fallback={ type="minecraft:condition", property="minecraft:using_item",
                 on_true={ type="minecraft:model", model="minecraft:item/torch",
                           tints={ { type="minecraft:constant", value=-1 } } },
                 on_false={ type="minecraft:special", base="minecraft:item/bell", model={ type="minecraft:bell" } } },
    } },
    types=types,
    models={
      { model="nordtal:item/lamp_gui", textures={ { key="layer0", texture=lampGui } } },
      { model="minecraft:item/torch", textures={ { key="layer0", texture={ id="minecraft:item/torch.png" } } } },
    },
  }
end

-- The nodes as "id text", indented by depth
local function outline(nodes, depth, out)
  out = out or {}
  for _, node in ipairs(nodes) do
    table.insert(out, string.rep("  ", depth or 0) .. node.id .. " " .. node.text)
    outline(node.children or {}, (depth or 0) + 1, out)
  end
  return out
end

-- The rows as "text=value", "text=value*" where a click edits it and
-- "[text]" for a button
local function shown(rows)
  local out = {}
  for _, row in ipairs(rows) do
    if row.kind == "button" then
      table.insert(out, "[" .. row.text .. "]")
    else
      table.insert(out, (row.text or "") .. "=" .. tostring(row.value) .. (row.field and "*" or ""))
    end
  end
  return out
end

local function field(rows, text)
  for _, row in ipairs(rows) do
    if row.text == text then return row.field end
  end
  error(text .. " is no row")
end

local function prefix(item) return "item/" .. item .. "#" end

describe("the item definition editor", function()
  it("builds a node for each case, entry, tint and model that the definition nests", function()
    local nodes, index = items.nodes(read(), tr)
    local p = prefix("nordtal:lamp")
    assert.same({
      p .. "model/cases/0 gui: model nordtal:item/lamp_gui",
      p .. "model/cases/1 [\"ground\",\"fixed\"]: empty",
      p .. "model/fallback fallback: condition using_item",
      "  " .. p .. "model/fallback/on_true on_true: model minecraft:item/torch",
      "    " .. p .. "model/fallback/on_true/tints/0 items.tint 1: constant",
      "  " .. p .. "model/fallback/on_false on_false: special bell",
    }, outline(nodes))
    assert.same({ kind="definition", item="nordtal:lamp", at="model/cases/0", part="case" }, index[p .. "model/cases/0"])
    assert.same({ kind="definition", item="nordtal:lamp", at="model/fallback", part="model" }, index[p .. "model/fallback"])
  end)

  it("shows the item's own fields, then the type and fields of its model", function()
    assert.same({
      "items.item=nordtal:lamp", "properties.file=/c/resource-pack/src/assets/nordtal/items/lamp.json",
      "oversized_in_gui=*",
      "items.type=select*", "property=display_context*", "[items.add cases]",
    }, shown(items.rows(read(), "", "item", tr)))
  end)

  it("offers an absent optional model and no second one", function()
    local r = read()
    r.definition.model.fallback = nil
    local rows = shown(items.rows(r, "", "item", tr))
    assert.equal("[items.add fallback]", rows[#rows])
  end)

  it("shows a case's condition, its model and the textures of that model", function()
    assert.same({
      "when=\"gui\"*", "items.type=model*", "model=nordtal:item/lamp_gui*", "[items.add tints]", "transformation=*",
      "#layer0=nordtal:item/lamp_gui.png*", "[items.import]", "[items.remove]",
    }, shown(items.rows(read(), "model/cases/0", "case", tr)))
  end)

  it("shows a texture that only the client jar has without opening it", function()
    local rows = items.rows(read(), "model/fallback/on_true", "model", tr)
    local texture
    for _, row in ipairs(rows) do
      if row.text == "#layer0" then texture = row end
    end
    assert.equal("minecraft:item/torch.png", texture.value)
    assert.is_nil(texture.field)
  end)

  it("shows a special model's own type and fields, and removes no model the definition requires", function()
    assert.same({ "items.type=special*", "base=minecraft:item/bell*", "items.special=bell*" },
      shown(items.rows(read(), "model/fallback/on_false", "model", tr)))
  end)

  it("gives the textures of a node's model that the pack holds, to open", function()
    assert.same({ lampGui }, items.textures(read(), "model/cases/0", "case"))
    assert.same({}, items.textures(read(), "model/fallback/on_true", "model"))
  end)

  it("sets a field with its kind as JSON", function()
    local r = read()
    local rows = items.rows(r, "model/cases/0", "case", tr)
    local when = field(rows, "when")
    assert.same({ kind="entry", id="value", label="when", text="\"gui\"" }, items.form(r, when, {}, tr).widgets[1])
    assert.same({ { "editItem", { item="nordtal:lamp", at="model/cases/0", fields="{\"when\":[\"gui\",\"ground\"]}" } } },
      items.jobs(r, when, { value="[\"gui\",\"ground\"]" }))
    assert.is_nil(items.jobs(r, when, { value="\"gui\"" }))

    local model = field(rows, "model")
    assert.same({ { "editItem", { item="nordtal:lamp", at="model/cases/0/model", fields="{\"model\":\"nordtal:item/lamp\"}" } } },
      items.jobs(r, model, { value="nordtal:item/lamp" }))
  end)

  it("sends a number as a number, a text that is none as text for the task to refuse, and an empty optional field as null", function()
    local r = read()
    r.definition.model = { type="minecraft:range_dispatch", property="minecraft:damage", entries={}, scale=2 }
    local scale = field(items.rows(r, "", "item", tr), "scale")
    assert.equal("{\"scale\":0.5}", items.jobs(r, scale, { value="0.5" })[1][2].fields)
    assert.equal("{\"scale\":\"half\"}", items.jobs(r, scale, { value="half" })[1][2].fields)
    assert.equal("{\"scale\":null}", items.jobs(r, scale, { value="" })[1][2].fields)
    local oversized = field(items.rows(r, "", "item", tr), "oversized_in_gui")
    local widget = items.form(r, oversized, {}, tr).widgets[1]
    assert.same({ "items.unset", "true", "false" }, widget.options)
    assert.equal("{\"oversized_in_gui\":true}", items.jobs(r, oversized, { value="true" })[1][2].fields)
  end)

  it("asks for a new type, then for each field it requires that is no model or list", function()
    local r = read()
    local type = field(items.rows(r, "model/cases/1", "case", tr), "items.type")
    local first = items.form(r, type, {}, tr)
    assert.same({ "minecraft:empty", "minecraft:model", "minecraft:select", "minecraft:condition",
                  "minecraft:range_dispatch", "minecraft:special" }, first.widgets[1].options)
    assert.equal("minecraft:empty", first.widgets[1].option)
    local second = items.form(r, type, { type="minecraft:special" }, tr)
    assert.same({ { kind="entry", id="base", label="base", text="" } }, second.widgets)
    assert.is_nil(items.form(r, type, { type="minecraft:special", base="minecraft:item/bell" }, tr))
    assert.is_nil(items.form(r, type, { type="minecraft:select" }, tr))
    assert.same({ { "editItem", { item="nordtal:lamp", at="model/cases/1/model",
                                  fields="{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/bell\"}" } } },
      items.jobs(r, type, { type="minecraft:special", base="minecraft:item/bell" }))
    assert.is_nil(items.jobs(r, type, { type="minecraft:empty" }))
  end)

  it("asks for a new property, then for each field it requires", function()
    local r = read()
    local property = field(items.rows(r, "", "item", tr), "property")
    assert.same({ "minecraft:display_context", "minecraft:block_state" }, items.form(r, property, {}, tr).widgets[1].options)
    assert.same({ { kind="entry", id="block_state_property", label="block_state_property", text="" } },
      items.form(r, property, { value="minecraft:block_state" }, tr).widgets)
    assert.same({ { "editItem", { item="nordtal:lamp", at="model",
      fields="{\"property\":\"minecraft:block_state\",\"block_state_property\":\"lit\"}" } } },
      items.jobs(r, property, { value="minecraft:block_state", block_state_property="lit" }))
  end)

  it("adds a case with its condition, a tint with its type and fields, and a fallback at once", function()
    local r = read()
    local rows = items.rows(r, "", "item", tr)
    local case = field(rows, "items.add cases")
    assert.same({ { kind="entry", id="when", label="when", text="" } }, items.form(r, case, {}, tr).widgets)
    assert.same({ { "editItem", { item="nordtal:lamp", at="model", add="cases", fields="{\"when\":\"ground\"}" } } },
      items.jobs(r, case, { when="\"ground\"" }))

    local tint = field(items.rows(r, "model/cases/0", "case", tr), "items.add tints")
    assert.equal("combobox", items.form(r, tint, {}, tr).widgets[1].kind)
    assert.same({ { kind="entry", id="default", label="default", text="" } },
      items.form(r, tint, { type="minecraft:dye" }, tr).widgets)
    assert.same({ { "editItem", { item="nordtal:lamp", at="model/cases/0/model", add="tints",
                                  fields="{\"type\":\"minecraft:dye\",\"default\":-1}" } } },
      items.jobs(r, tint, { type="minecraft:dye", default="-1" }))

    r.definition.model.fallback = nil
    local fallback = field(items.rows(r, "", "item", tr), "items.add fallback")
    assert.is_nil(items.form(r, fallback, {}, tr))
    assert.same({ { "editItem", { item="nordtal:lamp", at="model", add="fallback", fields="{}" } } },
      items.jobs(r, fallback, {}))
  end)

  it("removes a case or an optional model at once", function()
    local r = read()
    local remove = field(items.rows(r, "model/cases/1", "case", tr), "items.remove")
    assert.is_nil(items.form(r, remove, {}, tr))
    assert.same({ { "editItem", { item="nordtal:lamp", at="model/cases/1", remove=true } } }, items.jobs(r, remove, {}))
  end)

  it("imports a Blockbench model and shows it in the case", function()
    local r = read()
    local import = field(items.rows(r, "model/cases/0", "case", tr), "items.import")
    local first = items.form(r, import, {}, tr).widgets
    assert.same({ "file" }, { first[1].kind, first[2] })
    assert.same({ { kind="entry", id="id", label="items.model", text="nordtal:item/lamp_lit" } },
      items.form(r, import, { file="/tmp/../export/Lamp_Lit.json" }, tr).widgets)
    assert.is_nil(items.form(r, import, { file="" }, tr))
    assert.same({
      { "importBlockbench", { model="/tmp/lamp.json", id="nordtal:item/lamp" } },
      { "editItem", { item="nordtal:lamp", at="model/cases/0/model",
                       fields="{\"type\":\"minecraft:model\",\"model\":\"nordtal:item/lamp\"}" } },
    }, items.jobs(r, import, { file="/tmp/lamp.json", id="nordtal:item/lamp" }))
    assert.is_nil(items.jobs(r, import, { file="", id="nordtal:item/lamp" }))
  end)

  it("imports a Blockbench model into a model that draws nothing", function()
    local rows = shown(items.rows(read(), "model/cases/1", "case", tr))
    assert.equal("[items.import]", rows[#rows - 1])
  end)
end)
