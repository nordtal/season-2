-- The editor of an item definition, from what :resource-pack:readItem
-- prints: its nested models as nodes of the tree, the fields of a
-- node as rows of the properties panel, the forms that ask for a new
-- value and the editItem runs that write it. Which fields a type has,
-- their kinds and whether they are required come from the task's
-- types, and every rule, such as what a new type keeps or starts
-- with, stays in the task.

local items = {}

-- The kinds of a field that hold a value the panel edits as text
local values = { id=true, text=true, number=true, boolean=true, json=true }

-- The kind of an element of each list kind
local elements = { models="model", cases="case", entries="entry", tints="tint" }

-- The table of types that names the types of each kind of object
local typed = { model="models", tint="tints", special="special" }

-- A JSON value as text, objects with their keys sorted. A table with
-- no members is an empty list, as plain() gives it either way.
local function encode(value)
  local kind = type(value)
  if kind == "string" then
    return '"' .. value:gsub('[%c"\\]', function(c)
      local named = { ['"']='\\"', ["\\"]="\\\\", ["\n"]="\\n", ["\t"]="\\t", ["\r"]="\\r" }
      return named[c] or string.format("\\u%04x", c:byte())
    end) .. '"'
  elseif kind == "number" then
    return tostring(math.tointeger(value) or value)
  elseif kind == "table" then
    local out = {}
    if next(value) == nil or value[1] ~= nil then
      for _, v in ipairs(value) do table.insert(out, encode(v)) end
      return "[" .. table.concat(out, ",") .. "]"
    end
    local keys = {}
    for k in pairs(value) do table.insert(keys, k) end
    table.sort(keys)
    for _, k in ipairs(keys) do table.insert(out, encode(k) .. ":" .. encode(value[k])) end
    return "{" .. table.concat(out, ",") .. "}"
  end
  return tostring(value == nil and "null" or value)
end

-- A JSON object of pairs { name, text } whose text is JSON already
local function members(list)
  local out = {}
  for _, pair in ipairs(list) do table.insert(out, encode(pair[1]) .. ":" .. pair[2]) end
  return "{" .. table.concat(out, ",") .. "}"
end

local function short(id)
  return (tostring(id):gsub("^minecraft:", ""))
end

local function join(at, key)
  return at == "" and key or at .. "/" .. key
end

-- The object at a path of keys and list indices from 0
local function find(definition, at)
  local node = definition
  for key in at:gmatch("[^/]+") do
    if type(node) ~= "table" then return nil end
    local i = math.tointeger(tonumber(key))
    node = node[i and i + 1 or key]
  end
  return node
end

local function entry(list, name)
  for _, t in ipairs(list or {}) do
    if t.type == name then return t end
  end
  return nil
end

-- The fields of an object of kind: the item, a case, an entry, or a
-- model, tint or special model by its type and its property
local function fieldsOf(types, kind, object)
  if kind == "item" or kind == "case" or kind == "entry" then
    return types[kind] or {}
  end
  local own = entry(types[typed[kind]], object and object.type)
  local list = {}
  for _, field in ipairs(own and own.fields or {}) do table.insert(list, field) end
  local property = own and own.properties and entry(types[own.properties], object.property)
  for _, field in ipairs(property and property.fields or {}) do table.insert(list, field) end
  return list
end

-- The text of a value in a row or a form
local function text(value, kind)
  if value == nil then return "" end
  if kind == "json" then return encode(value) end
  if type(value) == "number" then return encode(value) end
  return tostring(value)
end

-- The node of a model: its kind, what it shows and its own label
local function modelLabel(model)
  if type(model) ~= "table" then return "?" end
  local label = short(model.type)
  if model.type == "minecraft:model" then
    label = label .. " " .. tostring(model.model)
  elseif model.property then
    label = label .. " " .. short(model.property)
  elseif type(model.model) == "table" then
    label = label .. " " .. short(model.model.type)
  end
  return label
end

-- The nodes nested in the model at at: a node for each model field
-- that is set and for each element of a list
local function children(read, at, tr, prefix, index)
  local nodes = {}
  local model = find(read.definition, at)
  local function add(id, part, label, nested)
    index[prefix .. id] = { kind="definition", item=read.item, at=id, part=part }
    table.insert(nodes, { id=prefix .. id, text=label, children=nested, expanded=true })
  end
  for _, field in ipairs(fieldsOf(read.types, "model", model)) do
    local value = model[field.name]
    local element = elements[field.kind]
    if field.kind == "model" and value ~= nil then
      local path = join(at, field.name)
      add(path, "model", field.name .. ": " .. modelLabel(value), children(read, path, tr, prefix, index))
    elseif element and type(value) == "table" then
      for i, item in ipairs(value) do
        local path = join(at, field.name .. "/" .. (i - 1))
        if element == "model" then
          add(path, "model", modelLabel(item), children(read, path, tr, prefix, index))
        elseif element == "tint" then
          add(path, "tint", tr("items.tint", i) .. ": " .. short(item.type), nil)
        else
          local key = element == "case" and item.when or item.threshold
          local shown = type(key) == "string" and key or encode(key)
          add(path, element, shown .. ": " .. modelLabel(item.model),
            children(read, join(path, "model"), tr, prefix, index))
        end
      end
    end
  end
  return #nodes > 0 and nodes or nil
end

-- The nodes nested in the item's definition, for the children of the
-- item's node, and an index from each node's id to { kind, item, at,
-- part }. part is "model", "case", "entry" or "tint", the object at at.
function items.nodes(read, tr)
  local index = {}
  local nodes = children(read, "model", tr, "item/" .. read.item .. "#", index)
  return nodes or {}, index
end

-- The objects a node edits, each { kind, at }: a case or an entry
-- and its model, a model and its special model, the item and its model
local function parts(read, at, part)
  local list = {}
  local function model(path)
    table.insert(list, { kind="model", at=path })
    local m = find(read.definition, path)
    if type(m) == "table" and type(m.model) == "table" then
      table.insert(list, { kind="special", at=join(path, "model") })
    end
  end
  if part == "item" then
    table.insert(list, { kind="item", at="" })
    model("model")
  elseif part == "case" or part == "entry" then
    table.insert(list, { kind=part, at=at })
    model(join(at, "model"))
  elseif part == "model" then
    model(at)
  else
    table.insert(list, { kind=part, at=at })
  end
  return list
end

-- Whether the node at at can be removed: an element of a list or a
-- model that its parent does not require
local function removable(read, at, part)
  if part == "item" then return false end
  local parent, key = at:match("^(.*)/([^/]+)$")
  if not parent then return false end
  if math.tointeger(tonumber(key)) then return true end
  local owner = find(read.definition, parent)
  local kind = (owner and owner.when ~= nil and "case") or (owner and owner.threshold ~= nil and "entry") or "model"
  for _, field in ipairs(fieldsOf(read.types, kind, owner)) do
    if field.name == key then return not field.required end
  end
  return false
end

-- The model of the node, of its case or entry, or of the item, and
-- its path
local function modelOf(read, at, part)
  if part == "tint" then return nil end
  local path = (part == "item" and "model") or ((part == "case" or part == "entry") and join(at, "model")) or at
  local model = find(read.definition, path)
  return type(model) == "table" and model or nil, path
end

local function texturesOf(read, at, part)
  local model = modelOf(read, at, part)
  model = model and model.type == "minecraft:model" and model or nil
  for _, described in ipairs(model and read.models or {}) do
    if described.model == model.model then return described.textures or {} end
  end
  return {}
end

-- The textures of the node's model that the pack holds, which the
-- editor opens
function items.textures(read, at, part)
  local list = {}
  for _, t in ipairs(texturesOf(read, at, part)) do
    if t.texture.png then table.insert(list, t.texture) end
  end
  return list
end

-- The rows of the node at at as the panel lays them out. The field of
-- a row is the action a click on it takes.
function items.rows(read, at, part, tr)
  local list = {}
  local function row(label, value, action)
    table.insert(list, { text=label, value=value, field=action })
  end
  local function button(label, action)
    table.insert(list, { text=label, field=action, kind="button" })
  end
  if part == "item" then
    row(tr("items.item"), read.item)
    if read.source == "vanilla" then row(tr("items.source"), tr("items.vanilla")) end
    if read.file then row(tr("properties.file"), read.file) end
    for i, problem in ipairs(read.problems or {}) do
      table.insert(list, { text=i == 1 and tr("items.problems") or nil, value=problem })
    end
  end
  for _, p in ipairs(parts(read, at, part)) do
    local object = find(read.definition, p.at) or {}
    if typed[p.kind] then
      local label = p.kind == "special" and tr("items.special") or tr("items.type")
      row(label, short(object.type), { op="type", at=p.at, part=p.kind })
    end
    for _, field in ipairs(fieldsOf(read.types, p.kind, object)) do
      local value = object[field.name]
      if values[field.kind] then
        row(field.name, text(value, field.kind), { op="set", at=p.at, part=p.kind, name=field.name, kind=field.kind,
                                                    required=field.required })
      elseif field.kind == "property" then
        row(field.name, short(value), { op="set", at=p.at, part=p.kind, name=field.name, kind=field.kind,
                                        required=true })
      elseif elements[field.kind] or (field.kind == "model" and value == nil and p.kind ~= "item") then
        button(tr("items.add", field.name), { op="add", at=p.at, part=p.kind, name=field.name,
                                              element=elements[field.kind] or "model" })
      end
    end
  end
  for _, t in ipairs(texturesOf(read, at, part)) do
    row("#" .. t.key, t.texture.id, t.texture.png and { op="texture", texture=t.texture } or nil)
  end
  -- An import replaces a model that draws one model or nothing
  local model, modelAt = modelOf(read, at, part)
  if model and (model.type == "minecraft:model" or model.type == "minecraft:empty") then
    button(tr("items.import"), { op="import", at=modelAt })
  end
  if removable(read, at, part) then
    button(tr("items.remove"), { op="remove", at=at })
  end
  return list
end

-- The widget that asks for a field of kind
local function widget(read, id, field, value, tr)
  if field.kind == "boolean" then
    local options = { tr("items.unset"), "true", "false" }
    return { kind="combobox", id=id, label=field.name, options=options,
             option=value == nil and options[1] or tostring(value) }
  end
  return { kind="entry", id=id, label=field.name, text=text(value, field.kind) }
end

local function typeOptions(read, kind)
  local options = {}
  for _, t in ipairs(read.types[typed[kind]] or {}) do table.insert(options, t.type) end
  return options
end

-- The fields of kind with object changed to change that it requires,
-- that hold a value and that neither it keeps nor the data has
local function needed(read, kind, object, change, data)
  local old = {}
  for _, field in ipairs(fieldsOf(read.types, kind, object)) do old[field.name] = field.kind end
  local new = {}
  for k, v in pairs(object or {}) do new[k] = v end
  for k, v in pairs(change) do new[k] = v end
  local list = {}
  for _, field in ipairs(fieldsOf(read.types, kind, new)) do
    local kept = object and object[field.name] ~= nil and old[field.name] == field.kind
    if field.required and values[field.kind] and not kept and change[field.name] == nil and data[field.name] == nil then
      table.insert(list, field)
    end
  end
  return list
end

-- The form of the required fields still missing, or nil
local function missing(read, kind, object, change, data, title, tr)
  local list = needed(read, kind, object, change, {})
  local widgets = {}
  for _, field in ipairs(list) do
    if data[field.name] == nil then table.insert(widgets, widget(read, field.name, field, nil, tr)) end
  end
  return #widgets > 0 and { title=title, widgets=widgets } or nil
end

-- The next form that action asks for, given the data of the forms
-- before it, or nil once nothing more is needed
function items.form(read, action, data, tr)
  local object = action.at and find(read.definition, action.at)
  if action.op == "set" then
    if data.value == nil then
      local field = { name=action.name, kind=action.kind }
      if action.kind == "property" then
        local owner = entry(read.types.models, object.type)
        local options = {}
        for _, t in ipairs(read.types[owner.properties] or {}) do table.insert(options, t.type) end
        return { title=action.name, widgets={
          { kind="combobox", id="value", label=action.name, options=options, option=object.property } } }
      end
      return { title=action.name, widgets={ widget(read, "value", field, object[action.name], tr) } }
    end
    if action.kind == "property" and data.value ~= object.property then
      return missing(read, action.part, object, { property=data.value }, data, action.name, tr)
    end
    return nil
  elseif action.op == "type" then
    if data.type == nil then
      return { title=tr("items.type"), widgets={
        { kind="combobox", id="type", label=tr("items.type"), options=typeOptions(read, action.part),
          option=object.type } } }
    end
    if data.type == object.type then return nil end
    return missing(read, action.part, object, { type=data.type }, data, short(data.type), tr)
  elseif action.op == "add" then
    local kind = action.element
    if kind == "model" then return nil end
    if typed[kind] then
      if data.type == nil then
        return { title=tr("items.add", action.name), widgets={
          { kind="combobox", id="type", label=tr("items.type"), options=typeOptions(read, kind) } } }
      end
      return missing(read, kind, nil, { type=data.type }, data, short(data.type), tr)
    end
    return missing(read, kind, nil, {}, data, tr("items.add", action.name), tr)
  elseif action.op == "import" then
    if data.file == nil then
      return { title=tr("items.import_title"), widgets={
        { kind="file", id="file", label=tr("items.file"), open=true, filetypes={ "json", "bbmodel" } } } }
    end
    -- The model is named after the file, in the item's namespace
    local name = data.file:match("([^/\\]+)%.%w+$")
    if data.id == nil and name then
      return { title=tr("items.import_title"), widgets={
        { kind="entry", id="id", label=tr("items.model"),
          text=read.item:match("^[^:]+") .. ":item/" .. name:lower() } } }
    end
  end
  return nil
end

-- A value of a field as JSON text: a number as a number, an empty
-- field that is not required as null, anything else as the kind says.
-- What the kind does not allow is left to the task to refuse.
local function value(field, given)
  if field.kind == "boolean" then
    return (given == "true" or given == "false") and given or "null"
  end
  if given == "" and not field.required then return "null" end
  if field.kind == "json" then return given end
  if field.kind == "number" and tonumber(given) then return encode(tonumber(given)) end
  return encode(given)
end

-- The pairs { name, JSON } of the required fields that the forms asked for
local function asked(read, kind, object, change, data)
  local list = {}
  for _, field in ipairs(needed(read, kind, object, change, {})) do
    if data[field.name] ~= nil then table.insert(list, { field.name, value(field, data[field.name]) }) end
  end
  return list
end

-- The runs that write what the forms asked for, each { task, options },
-- or nil when nothing changed
function items.jobs(read, action, data)
  local object = action.at and find(read.definition, action.at)
  local function edit(fields, extra)
    local options = { item=read.item, at=action.at, fields=fields }
    for k, v in pairs(extra or {}) do options[k] = v end
    return { { "editItem", options } }
  end
  if action.op == "set" then
    local given = data.value
    if action.kind == "property" then
      if given == object.property then return nil end
      local list = { { "property", encode(given) } }
      for _, pair in ipairs(asked(read, action.part, object, { property=given }, data)) do table.insert(list, pair) end
      return edit(members(list))
    end
    if action.kind == "boolean" and given ~= "true" and given ~= "false" then given = "" end
    if given == text(object[action.name], action.kind) then return nil end
    return edit(members({ { action.name, value(action, given) } }))
  elseif action.op == "type" then
    if data.type == object.type then return nil end
    local list = { { "type", encode(data.type) } }
    for _, pair in ipairs(asked(read, action.part, object, { type=data.type }, data)) do table.insert(list, pair) end
    return edit(members(list))
  elseif action.op == "add" then
    local kind, list = action.element, {}
    if kind ~= "model" then
      local change = typed[kind] and { type=data.type } or {}
      if change.type then table.insert(list, { "type", encode(change.type) }) end
      for _, pair in ipairs(asked(read, kind, nil, change, data)) do table.insert(list, pair) end
    end
    return edit(members(list), { add=action.name })
  elseif action.op == "remove" then
    return { { "editItem", { item=read.item, at=action.at, remove=true } } }
  elseif action.op == "import" then
    if (data.file or "") == "" or (data.id or "") == "" then return nil end
    return {
      { "importBlockbench", { model=data.file, id=data.id } },
      { "editItem", { item=read.item, at=action.at,
                      fields=members({ { "type", encode("minecraft:model") }, { "model", encode(data.id) } }) } },
    }
  end
  return nil
end

return items
