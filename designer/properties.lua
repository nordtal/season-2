-- The properties of a tree entry, which of them the panel edits, the
-- form that asks for a new value and the tasks of the resource pack
-- that write it. Every rule of the pack, such as the constant a name
-- gives or the display height a pixel size gives, stays in those
-- tasks. The panel shows what the pack model says after each one.

local properties = {}

-- The integer of a whole number, any other value as it is. Aseprite's
-- Lua floors in math.tointeger, so a fraction needs its own check.
local function int(value)
  if type(value) == "number" and value % 1 == 0 then
    return math.tointeger(value) or value
  end
  return value
end

-- A number of the model as text: JSON gives every number as a float
local function shown(value)
  if type(value) == "number" then
    return (tostring(int(value)))
  end
  return (tostring(value))
end

-- The name or constant by which the tasks find a glyph or cell, or
-- nil for a cell of ordinary characters, which has neither
function properties.label(info)
  local e = info.entry
  return (e.name or nil) or (e.constant or nil)
end

-- The question that confirms the delete of info: for an override or
-- an item, the question and what goes with it as lines of their own,
-- since a long id would push one line past the window
function properties.confirm(info, tr)
  if info.kind == "override" then
    local lines = { tr("properties.delete_question", info.texture.id), tr("properties.delete_texture_note") }
    -- An override of Minecraft's texture gives the jar's back
    if info.texture.id:find("^minecraft:") then
      table.insert(lines, tr("properties.delete_override_note"))
    end
    return lines
  elseif info.kind == "item" then
    return { tr("properties.delete_question", info.entry.id), tr("properties.delete_item_note") }
  elseif info.kind == "font" then
    return { tr("properties.delete_question", info.entry.font), tr("properties.delete_font_note") }
  elseif info.kind == "block" or info.kind == "sheet" then
    return { tr("properties.delete_question", info.entry.title), tr("properties.delete_block_note") }
  end
  local text = tr("properties.delete_confirm", properties.label(info))
  return text
end

-- The size { width, height } of the sprite of info: one frame of an
-- animated texture whose model splits its PNG into frames, else the
-- PNG's. The tab writes the sprite's size into the texture and removes
-- the frames from the model after a save. A read of the pack replaces
-- both.
local function size(info)
  local frame = properties.animated(info) and info.entry.frames and info.entry.frames[1]
  return frame or info.texture
end

-- The languages of a text or of the texts root: the pack's, then any
-- other that the text holds
local function languages(info)
  local list, seen = {}, {}
  for _, language in ipairs(info.languages or {}) do
    table.insert(list, language)
    seen[language] = true
  end
  local others = {}
  for language in pairs(info.entry and info.entry.values or {}) do
    if not seen[language] then table.insert(others, language) end
  end
  table.sort(others)
  table.move(others, 1, #others, #list + 1, list)
  return list
end

-- The rows of an entry as { key, value, field } in display order. A
-- row with a field can be edited, and field names the edit. The
-- coupled row is the switch, its value true or false. A row with a
-- label shows it in place of its key's text, and a row with button is
-- a button. context holds coupled.
function properties.rows(info, tr, context)
  local list = {}
  local function add(key, value, field)
    if value ~= nil and value ~= false and value ~= "" then
      table.insert(list, { key=key, value=shown(value), field=field })
    end
  end
  if not info then
    return list
  end

  local e, kind, t = info.entry, info.kind, info.texture
  local editable = (kind == "glyph" or kind == "cell") and properties.label(info) ~= nil
  -- The font of ordinary text and a sheet of ordinary characters stay,
  -- since code writes them as plain text
  local ordinary = (kind == "font" and e.font == properties.text_font) or (kind == "sheet" and e.characters == true)
  -- A texture without a glyph is deleted with its own task, an item
  -- with its models and textures
  local deletable = editable or (kind == "override" and t ~= nil) or kind == "item"
    or ((kind == "font" or kind == "block" or kind == "sheet") and not ordinary)
  local function pixels(field)
    local s = t and size(info)
    if s and s.width then
      add("size", tr("properties.pixels", shown(s.width), shown(s.height)), field)
    end
  end
  local function files()
    if t then
      add("png", t.png)
      add("source", t.generated and tr("properties.generated") or t.asepriteExists and t.aseprite or tr("properties.no_source"))
    end
  end

  if info.font and kind ~= "font" then
    add("font", info.font.font)
  end
  if kind == "font" then
    add("font", e.font)
    add("rows", e.rows)
    if e.font == properties.text_font then
      add("constant", e.constant)
    else
      table.insert(list, { key="constant", value=shown(e.constant or ""), field="constant" })
    end
    table.insert(list, { key="about", value=e.about or "", field="about" })
  elseif kind == "block" or kind == "sheet" then
    add("block", e.title, "title")
    add("range", e.range)
    table.insert(list, { key="status", value=e.status or tr("properties.unjudged"), field="status" })
    for _, key in ipairs({ "height", "ascent" }) do
      table.insert(list, { key=key, value=e[key] and shown(e[key]) or tr("properties.per_glyph"), field=key })
    end
    table.insert(list, { key="about", value=e.about or "", field="about" })
    if kind == "sheet" and not ordinary then
      table.insert(list, { key="add_cells", value=tr("properties.add_cells"), field="addCells", button=true })
    end
  elseif kind == "glyph" or kind == "cell" then
    local glyph = kind == "glyph"
    add("block", info.block.title)
    add("title", e.title, editable and "title")
    add("code", e.code)
    add("name", e.name, editable and "name")
    add("constant", e.constant, editable and not e.name and "constant" or nil)
    add("status", e.effectiveStatus, editable and "status")
    add("height", e.height, editable and glyph and "height")
    add("ascent", e.ascent, editable and glyph and "ascent")
    if glyph then
      pixels(editable and "size")
      if editable then
        table.insert(list, { key="coupled", value=context.coupled ~= false, field="coupled" })
      end
    end
    if editable then
      table.insert(list, { key="about", value=e.about or "", field="about" })
    else
      add("about", e.about)
    end
  elseif kind == "item" then
    add("item", e.id)
    add("model", e.model)
    add("file", e.file)
  elseif kind == "texts" then
    table.insert(list, { key="add_text", value=tr("properties.add_text"), field="addText", button=true })
  elseif kind == "text" then
    add("key", e.key)
    add("owner", e.owner or tr("properties.no_owner"))
    for _, language in ipairs(languages(info)) do
      local value = e.values[language]
      table.insert(list, { key="text", label=language, field="text:" .. language,
                           value=value == nil and tr("properties.no_text") or shown(value) })
    end
    table.insert(list, { key="delete_text", value=tr("properties.delete_text"), field="deleteText", button=true })
  elseif kind == "override" then
    add("mcmeta", e.mcmeta)
    if e.frames then
      add("animation", tr("properties.frames", #e.frames))
    else
      add("animation", e.animation and tr("properties.yes"))
    end
    if e.guiSprite then
      add("scaling", type(e.scaling) == "table" and e.scaling.type or "stretch", "scaling")
    end
  end

  if t then
    add("texture", t.id)
    if kind ~= "glyph" then
      pixels(kind == "override" and "size" or nil)
    end
    files()
  end
  if deletable then
    table.insert(list, { key="delete", value=tr("properties.delete"), field="delete" })
  end
  return list
end

-- The first choice of a status, which leaves the glyph with its block's
local function fromBlock(info, tr)
  return (tr("properties.from_block", info.block.status or "-"))
end

local function number(id, label, value)
  return { kind="number", id=id, label=label, text=shown(value or 0), decimals=0 }
end

-- The form that asks for a new value of field: { title, widgets },
-- each widget { kind, id, label, ... } as Dialog takes it. lists holds
-- the statuses and scalings of the pack model.
function properties.form(info, field, tr, lists)
  local e, t = info.entry, info.texture
  local language = field:match("^text:(.+)$")
  if language then
    local title = tr("properties.text_in", e.key, language)
    return { title=title, widgets={ { kind="entry", id="value", label=language, text=e.values[language] or "" } } }
  elseif field == "addText" then
    local title = tr("properties.add_text")
    local options = languages(info)
    return { title=title, widgets={
      { kind="entry", id="key", label=tr("properties.key"), text="" },
      #options > 0 and { kind="combobox", id="language", label=tr("properties.language"), options=options, option=options[1] }
        or { kind="entry", id="language", label=tr("properties.language"), text="en_us" },
      { kind="entry", id="value", label=tr("properties.value"), text="" } } }
  elseif field == "deleteText" then
    local title = tr("properties.delete_text")
    return { title=title, widgets={ { kind="label", id="confirm", text=tr("properties.delete_text_confirm", e.key) } } }
  end
  if field == "addCells" then
    local title = tr("properties.add_cells")
    return { title=title, widgets={ { kind="entry", id="constants", label=tr("properties.constants"), text="" } } }
  end
  local title = tr("properties." .. field)
  if field == "status" and (info.kind == "block" or info.kind == "sheet") then
    local options = { tr("properties.unjudged") }
    for _, status in ipairs(lists.statuses or {}) do table.insert(options, status) end
    return { title=title, widgets={
      { kind="combobox", id="value", label=title, options=options, option=e.status or options[1] } } }
  elseif field == "status" then
    local options = { fromBlock(info, tr) }
    for _, status in ipairs(lists.statuses or {}) do table.insert(options, status) end
    return { title=title, widgets={
      { kind="combobox", id="value", label=title, options=options, option=e.status or options[1] } } }
  elseif field == "height" or field == "ascent" then
    return { title=title, widgets={ number("value", title, e[field]) } }
  elseif field == "size" then
    local s = size(info)
    return { title=title, widgets={
      number("width", tr("properties.width"), s.width), number("height", tr("properties.pixel_height"), s.height) } }
  elseif field == "scaling" then
    local s = type(e.scaling) == "table" and e.scaling or {}
    local border = s.border
    if type(border) ~= "table" then
      border = { left=border, top=border, right=border, bottom=border }
    end
    local widgets = {
      { kind="combobox", id="type", label=tr("properties.scaling_type"), options=lists.scalings or {},
        option=s.type or "stretch" },
      number("width", tr("properties.width"), s.width or t.width),
      number("height", tr("properties.pixel_height"), s.height or t.height),
    }
    for _, side in ipairs({ "left", "top", "right", "bottom" }) do
      local key = "properties.border_" .. side
      table.insert(widgets, number(side, tr(key), border[side]))
    end
    table.insert(widgets, { kind="check", id="stretchInner", label=tr("properties.stretch_inner"),
                            selected=s.stretch_inner == true })
    return { title=title, widgets=widgets }
  end
  return { title=title, widgets={ { kind="entry", id="value", label=title, text=e[field] or "" } } }
end

-- The task that writes the values data of a form for field as
-- { task, options }, nil for none, or { resize={ width, height,
-- lossy } } for a pixel size, which the sprite takes before its save
-- writes it. old is the { width, height } of the open sprite, nil for
-- none. A size that is no whole multiple of old up is lossy: nearest
-- neighbour drops or doubles rows and columns.
function properties.jobs(info, field, data, tr, old)
  local e, t = info.entry, info.texture
  local value = data.value
  local language = field:match("^text:(.+)$")
  if language then
    if value == (e.values[language] or "") then return nil end
    return { "editText", { key=e.key, language=language, value=value } }
  elseif field == "addText" then
    if (data.key or "") == "" then return nil end
    return { "editText", { key=data.key, language=data.language, value=data.value or "" } }
  elseif field == "deleteText" then
    return { "editText", { key=e.key, delete=true } }
  elseif field == "delete" and info.kind == "override" then
    return { "deleteTexture", { texture=t.id } }
  elseif field == "delete" and info.kind == "item" then
    return { "deleteItem", { item=e.id } }
  elseif info.kind == "font" then
    local job = properties.fontJob(e, field, value)
    return job
  elseif info.kind == "block" or info.kind == "sheet" then
    local job = properties.blockJob(info, field, data, tr)
    return job
  end
  local glyph = properties.label(info)
  if field == "name" or field == "constant" then
    if value == e[field] or value == "" then return nil end
    return { "renameGlyph", { glyph=glyph, to=value } }
  elseif field == "status" then
    if value == fromBlock(info, tr) then value = "" end
    if value == (e.status or "") then return nil end
    return { "editGlyph", { glyph=glyph, status=value } }
  elseif field == "height" or field == "ascent" then
    value = int(value)
    if value == int(e[field]) then return nil end
    return { "editGlyph", { glyph=glyph, [field]=value } }
  elseif field == "about" then
    if value == (e.about or "") then return nil end
    return { "editGlyph", { glyph=glyph, about=value } }
  elseif field == "title" then
    -- Every glyph keeps a title, so an empty one changes nothing
    value = tostring(value or ""):match("^%s*(.-)%s*$")
    if value == "" or value == e.title then return nil end
    return { "editGlyph", { glyph=glyph, title=value } }
  elseif field == "delete" then
    return { "deleteGlyph", { glyph=glyph } }
  elseif field == "size" and old then
    local width, height = int(data.width), int(data.height)
    local oldWidth, oldHeight = int(old.width), int(old.height)
    if width == oldWidth and height == oldHeight then return nil end
    local whole = width % oldWidth == 0 and height % oldHeight == 0 and width >= oldWidth and height >= oldHeight
    return { resize={ width=width, height=height, lossy=not whole or nil } }
  elseif field == "scaling" then
    local options = { texture=t.id, scaling=data.type }
    if data.type ~= "stretch" then
      options.width, options.height = int(data.width), int(data.height)
    end
    if data.type == "nine_slice" then
      local sides = {}
      for _, side in ipairs({ "left", "top", "right", "bottom" }) do table.insert(sides, shown(int(data[side]))) end
      options.border = table.concat(sides, ",")
      options.stretchInner = data.stretchInner == true
    end
    return { "scaleTexture", options }
  end
  return nil
end

-- The id of the font of ordinary text, which has no constant and stays
properties.text_font = "minecraft:default"

-- The task that writes field of the font e from value
function properties.fontJob(e, field, value)
  if field == "delete" then
    return { "deleteFont", { font=e.font } }
  elseif field == "constant" or field == "about" then
    if value == nil or value == (e[field] or "") then return nil end
    return { "editFont", { font=e.font, [field]=value } }
  end
  return nil
end

local function trim(text)
  return (tostring(text or ""):match("^%s*(.-)%s*$"))
end

-- The task that writes field of the block or sheet of info from the
-- values data of its form
function properties.blockJob(info, field, data, tr)
  local e = info.entry
  local options = { font=info.font.font, block=e.title }
  local value = data.value
  if field == "delete" then
    return { "deleteGlyphBlock", options }
  elseif field == "addCells" then
    local constants = {}
    for constant in tostring(data.constants or ""):gmatch("[^,%s]+") do table.insert(constants, constant) end
    if #constants == 0 then return nil end
    options.constants = table.concat(constants, ",")
    return { "addSheetCells", options }
  elseif field == "title" then
    value = trim(value)
    if value == "" or value == e.title then return nil end
    options.title = value
  elseif field == "status" then
    if value == tr("properties.unjudged") then value = "" end
    if value == nil or value == (e.status or "") then return nil end
    options.status = value
  elseif field == "height" or field == "ascent" then
    value = int(value)
    if value == nil or value == int(e[field]) then return nil end
    options[field] = value
  elseif field == "about" then
    if value == nil or value == (e.about or "") then return nil end
    options.about = value
  else
    return nil
  end
  return { "editGlyphBlock", options }
end

-- The width of the sheet of info once job, an addSheetCells, added
-- its cells: one cell's width more per new cell
function properties.widened(info, job)
  local width, cells = int(info.texture.width), #(info.entry.cells or {})
  local added = select(2, job[2].constants:gsub("[^,]+", ""))
  if cells == 0 then return width end
  return width + width // cells * added
end

-- Whether the texture of info keeps its frames in one column with an
-- animation: an override's or one that an item definition's model draws
function properties.animated(info)
  return info.kind == "override" or info.kind == "texture"
end

-- The task that follows a save of the texture of info, whose sprite
-- now is { width, height, durations, opened } with a duration in
-- milliseconds per frame and the count of frames it was opened with: a glyph whose pixel height changed scales its display
-- height when coupled, and an animated texture writes its animation. An
-- animation the model could not split into frames stays as it is.
-- nil when nothing follows.
function properties.saved(info, sprite, coupled)
  local e, t = info.entry, info.texture
  if info.kind == "glyph" and properties.label(info) and t.height and sprite.height ~= int(t.height) then
    return { "resizeGlyph", { glyph=properties.label(info), from=int(t.height), coupled=coupled ~= false } }
  end
  if properties.animated(info) and (#sprite.durations > 1 or (e.animation and e.frames)) then
    local frames = {}
    for _, ms in ipairs(sprite.durations) do table.insert(frames, shown(int(ms))) end
    return { "animateTexture", { texture=t.id, frames=table.concat(frames, ","), from=sprite.opened } }
  end
  return nil
end

return properties
