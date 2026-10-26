local items = require("items")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

-- The types that readItem prints, which a test of build-logic checks
-- against the task
local file = assert(io.open("spec/item-types.json"))
local dkjson = require("dkjson")
local types = dkjson.decode(file:read("a"))
file:close()

local function decode(text) return dkjson.decode(text) end

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
      "hand_animation_on_swap=*", "oversized_in_gui=*", "swap_animation_scale=*",
      "items.type=select*", "property=display_context*", "[items.add cases]", "transformation=*",
    }, shown(items.rows(read(), "", "item", tr)))
  end)

  it("offers an absent optional model and no second one", function()
    local r = read()
    r.definition.model.fallback = nil
    local rows = shown(items.rows(r, "", "item", tr))
    assert.equal("[items.add fallback]", rows[#rows - 1])
    assert.equal(1, #(function()
      local found = {}
      for _, row in ipairs(rows) do if row:find("fallback", 1, true) then table.insert(found, row) end end
      return found
    end)())
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
    assert.same({ "items.type=special*", "base=minecraft:item/bell*", "transformation=*", "items.special=bell*" },
      shown(items.rows(read(), "model/fallback/on_false", "model", tr)))
  end)

  it("gives the textures of a node's model that the pack holds, with their keys, to open", function()
    local textures = items.textures(read(), "model/cases/0", "case")
    assert.same({ { key="layer0", texture=lampGui } }, textures)
    assert.same({}, items.textures(read(), "model/fallback/on_true", "model"))
    local row = field(items.rows(read(), "model/cases/0", "case", tr), "#layer0")
    assert.same({ op="texture", entry=textures[1] }, row)
  end)

  it("sets a field with its kind as JSON", function()
    local r = read()
    local rows = items.rows(r, "model/cases/0", "case", tr)
    local when = field(rows, "when")
    assert.same({ kind="entry", id="value", label="when", text="\"gui\"" }, items.form(r, when, {}, tr).widgets[1])
    assert.same({ "editItem", { item="nordtal:lamp", at="model/cases/0", fields="{\"when\":[\"gui\",\"ground\"]}" } },
      items.job(r, when, { value="[\"gui\",\"ground\"]" }, decode))
    assert.is_nil(items.job(r, when, { value="\"gui\"" }, decode))

    local model = field(rows, "model")
    assert.same({ "editItem", { item="nordtal:lamp", at="model/cases/0/model", fields="{\"model\":\"nordtal:item/lamp\"}" } },
      items.job(r, model, { value="nordtal:item/lamp" }, decode))
  end)

  it("sends a json field's text that is no JSON as a string", function()
    local r = read()
    local when = field(items.rows(r, "model/cases/1", "case", tr), "when")
    assert.equal("{\"when\":\"gui\"}", items.job(r, when, { value="gui" }, decode)[2].fields)
    assert.equal("{\"when\":{\"a\":1}}", items.job(r, when, { value="{\"a\":1}" }, decode)[2].fields)
    assert.equal("{\"when\":3}", items.job(r, when, { value="3" }, decode)[2].fields)
  end)

  it("labels and sends a fraction whole in Aseprite's Lua, whose math.tointeger rounds a float down", function()
    local tointeger = math.tointeger
    math.tointeger = function(x)
      if type(x) == "number" then return tointeger(math.floor(x)) end
      return tointeger(x)
    end
    local r = read()
    r.definition.model = { type="minecraft:range_dispatch", property="minecraft:compass", scale=2.0, entries={
      { threshold=0.0, model={ type="minecraft:empty" } }, { threshold=0.5, model={ type="minecraft:empty" } } } }
    local ok, err = pcall(function()
      local p = prefix("nordtal:lamp")
      assert.same({ p .. "model/entries/0 0: empty", p .. "model/entries/1 0.5: empty" }, outline((items.nodes(r, tr))))
      local scale = field(items.rows(r, "", "item", tr), "scale")
      assert.equal("{\"scale\":0.5}", items.job(r, scale, { value="0.5" }, decode)[2].fields)
    end)
    math.tointeger = tointeger
    assert(ok, err)
  end)

  it("sends a number as a number, a text that is none as text for the task to refuse, and an empty optional field as null", function()
    local r = read()
    r.definition.model = { type="minecraft:range_dispatch", property="minecraft:damage", entries={}, scale=2 }
    local scale = field(items.rows(r, "", "item", tr), "scale")
    assert.equal("{\"scale\":0.5}", items.job(r, scale, { value="0.5" }, decode)[2].fields)
    assert.equal("{\"scale\":\"1,5\"}", items.job(r, scale, { value="1,5" }, decode)[2].fields)
    assert.equal("{\"scale\":null}", items.job(r, scale, { value="" }, decode)[2].fields)
    local oversized = field(items.rows(r, "", "item", tr), "oversized_in_gui")
    local widget = items.form(r, oversized, {}, tr).widgets[1]
    assert.same({ "items.unset", "true", "false" }, widget.options)
    assert.equal("{\"oversized_in_gui\":true}", items.job(r, oversized, { value="true" }, decode)[2].fields)
  end)

  it("asks for a new type only, and leaves the fields it requires to the task", function()
    local r = read()
    local type = field(items.rows(r, "model/cases/1", "case", tr), "items.type")
    local first = items.form(r, type, {}, tr)
    assert.same({ "minecraft:empty", "minecraft:model", "minecraft:range_dispatch", "minecraft:special",
                  "minecraft:composite", "minecraft:bundle/selected_item", "minecraft:select",
                  "minecraft:condition" }, first.widgets[1].options)
    assert.equal("minecraft:empty", first.widgets[1].option)
    assert.is_nil(items.form(r, type, { type="minecraft:special" }, tr))
    assert.same({ "editItem", { item="nordtal:lamp", at="model/cases/1/model", fields="{\"type\":\"minecraft:special\"}" } },
      items.job(r, type, { type="minecraft:special" }, decode))
    assert.is_nil(items.job(r, type, { type="minecraft:empty" }, decode))
  end)

  it("asks for each field that the task needs, and sends them with the change", function()
    local r = read()
    local type = field(items.rows(r, "model/cases/1", "case", tr), "items.type")
    local needs = { { name="base", kind="id" }, { name="scale", kind="number" }, { name="when", kind="json" } }
    local form = items.form(r, type, { type="minecraft:special", needs=needs }, tr)
    assert.equal("items.needs", form.title)
    assert.same({ { kind="entry", id="need:base", label="base", text="" },
                  { kind="entry", id="need:scale", label="scale", text="" },
                  { kind="entry", id="need:when", label="when", text="" } }, form.widgets)
    local data = { type="minecraft:special", needs=needs, ["need:base"]="minecraft:item/bell", ["need:scale"]="2",
                   ["need:when"]="gui" }
    assert.is_nil(items.form(r, type, data, tr))

    assert.same({ "editItem", { item="nordtal:lamp", at="model/cases/1/model",
      fields="{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/bell\",\"scale\":2,\"when\":\"gui\"}" } },
      items.job(r, type, data, decode))
    items.needs(data, { { name="base", kind="id" }, { name="index", kind="number" } })
    assert.same({ { kind="entry", id="need:base", label="base", text="" },
                  { kind="entry", id="need:index", label="index", text="" } }, items.form(r, type, data, tr).widgets)
    data["need:base"], data["need:index"] = "minecraft:item/bell", "1"
    assert.equal("{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/bell\",\"scale\":2,\"when\":\"gui\",\"index\":1}",
      items.job(r, type, data, decode)[2].fields)
  end)

  it("asks for a new property apart from a field named value", function()
    local r = read()
    local property = field(items.rows(r, "", "item", tr), "property")
    local form = items.form(r, property, {}, tr)
    assert.equal("property", form.widgets[1].id)
    assert.equal("minecraft:display_context", form.widgets[1].option)
    assert.is_nil(items.form(r, property, { property="minecraft:block_state" }, tr))
    assert.same({ "editItem", { item="nordtal:lamp", at="model", fields="{\"property\":\"minecraft:block_state\"}" } },
      items.job(r, property, { property="minecraft:block_state" }, decode))
    assert.is_nil(items.job(r, property, { property="minecraft:display_context" }, decode))
  end)

  it("adds a case at once, a tint with its type, and a fallback at once", function()
    local r = read()
    local case = field(items.rows(r, "", "item", tr), "items.add cases")
    assert.is_nil(items.form(r, case, {}, tr))
    assert.same({ "editItem", { item="nordtal:lamp", at="model", add="cases", fields="{}" } },
      items.job(r, case, {}, decode))

    local tint = field(items.rows(r, "model/cases/0", "case", tr), "items.add tints")
    assert.equal("combobox", items.form(r, tint, {}, tr).widgets[1].kind)
    assert.is_nil(items.form(r, tint, { type="minecraft:dye" }, tr))
    assert.same({ "editItem", { item="nordtal:lamp", at="model/cases/0/model", add="tints",
                                fields="{\"type\":\"minecraft:dye\"}" } },
      items.job(r, tint, { type="minecraft:dye" }, decode))

    r.definition.model.fallback = nil
    local fallback = field(items.rows(r, "", "item", tr), "items.add fallback")
    assert.is_nil(items.form(r, fallback, {}, tr))
    assert.same({ "editItem", { item="nordtal:lamp", at="model", add="fallback", fields="{}" } },
      items.job(r, fallback, {}, decode))
  end)

  it("removes a case or an optional model at once", function()
    local r = read()
    local remove = field(items.rows(r, "model/cases/1", "case", tr), "items.remove")
    assert.is_nil(items.form(r, remove, {}, tr))
    assert.same({ "editItem", { item="nordtal:lamp", at="model/cases/1", remove=true } }, items.job(r, remove, {}, decode))
  end)

  it("imports a Blockbench model into the case in one run, named in the pack's namespace", function()
    local r = read()
    r.item = "minecraft:apple"
    local import = field(items.rows(r, "model/cases/0", "case", tr), "items.import")
    local first = items.form(r, import, {}, tr).widgets
    assert.same({ "file" }, { first[1].kind, first[2] })
    assert.same({ { kind="entry", id="id", label="items.model", text="nordtal:item/lamp_lit" } },
      items.form(r, import, { file="/tmp/../export/Lamp_Lit.bbmodel" }, tr).widgets)
    assert.is_nil(items.form(r, import, { file="" }, tr))
    assert.same({ "importBlockbench", { model="/tmp/lamp.bbmodel", id="nordtal:item/lamp", item="minecraft:apple",
                                        at="model/cases/0/model" } },
      items.job(r, import, { file="/tmp/lamp.bbmodel", id="nordtal:item/lamp" }, decode))
    assert.is_nil(items.job(r, import, { file="", id="nordtal:item/lamp" }, decode))
  end)

  it("imports a Blockbench model into a model that draws nothing", function()
    local rows = shown(items.rows(read(), "model/cases/1", "case", tr))
    assert.equal("[items.import]", rows[#rows - 1])
  end)
end)
