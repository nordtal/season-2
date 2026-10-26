-- The wizard that creates a texture: it asks for the kind first and
-- then for the options of the kind's task, which checks them. A
-- refusal shows below the field it names, and the dialog stays open.
-- A vanilla override is chosen in a browser of the client jar's
-- textures, and a vanilla item in one of its item definitions that
-- the pack does not override yet.
--
-- wizard.open{ api, tr, model, run, listing, created } shows it. api
-- holds Dialog, Rectangle and screen, which gives the window's width
-- and height. run(job, done) runs a { task, options }
-- and calls done(ok, reason, out) with the lines of the refusal and
-- what the task printed. listing(done) calls done(listing) with the
-- textures and items of the client jar, or done(nil, reason).
-- created(job) is called once the task succeeded.

local wizard = {}

wizard.kinds = { "glyph", "block", "sheet", "item", "vanilla_item", "override" }

-- The kinds chosen in a browser of the client jar: the option of the
-- task that names the choice and the strings of the browser
local browsers = {
  override={ option="texture", count="new.count", more="new.count_more", none="new.chosen_none",
             required="new.required_texture" },
  vanilla_item={ option="item", count="new.count_items", more="new.count_items_more", none="new.chosen_none_item",
                 required="new.required_item" },
}

-- The fields of each kind but those of a browser, in the order the dialog
-- shows them. Each is an option of the kind's task, except namespace
-- and path, which form the id.
wizard.fields = {
  glyph={ "font", "block", "namespace", "path", "name", "constant", "width", "height", "ascent" },
  block={ "font", "title", "size", "height", "ascent" },
  sheet={ "font", "title", "namespace", "path", "constants", "width", "height", "ascent" },
  item={ "namespace", "path", "width", "height" },
}

-- The text a field starts with
local defaults = {
  glyph={ namespace="nordtal" },
  block={ size="16" },
  sheet={ namespace="nordtal" },
  item={ namespace="nordtal", width="16", height="16" },
}

-- editItem without a change writes a vanilla item's definition into
-- the pack as the jar has it
local tasks = { glyph="createGlyph", block="createGlyphBlock", sheet="createGlyphSheet", item="createItem",
                vanilla_item="editItem", override="copyVanillaTexture" }

-- How many ids of the client jar the browser shows at once
local limit = 300

-- The characters a line of a refusal holds at most
local lineWidth = 48

-- The fonts of the model, by id
function wizard.fonts(model)
  local out = {}
  for _, font in ipairs(model.fonts or {}) do table.insert(out, font.font) end
  return out
end

-- The titles of the blocks of font that hold glyphs of their own
function wizard.blocks(model, id)
  local out = {}
  for _, font in ipairs(model.fonts or {}) do
    if font.font == id then
      for _, block in ipairs(font.blocks or {}) do
        if block.kind == "block" then table.insert(out, block.title) end
      end
    end
  end
  return out
end

local function trim(text)
  return (tostring(text or ""):match("^%s*(.-)%s*$"))
end

-- The task of kind and its options from the values of the form. An
-- empty value is left out, so the task falls back to its default or
-- asks for it. The id is left out while its path is empty.
function wizard.job(kind, values)
  local options = {}
  local browser = browsers[kind]
  if browser then
    options[browser.option] = values[browser.option]
    return { tasks[kind], options }
  end
  local namespace, path = trim(values.namespace), trim(values.path)
  for _, field in ipairs(wizard.fields[kind]) do
    local value = trim(values[field])
    if field ~= "namespace" and field ~= "path" and value ~= "" then options[field] = value end
  end
  if path ~= "" then
    if kind == "item" then
      options.item = namespace .. ":" .. path
    else
      options.texture = namespace .. ":" .. path .. (path:match("%.png$") and "" or ".png")
    end
  end
  return { tasks[kind], options }
end

-- The field of the form that a refusal names on its line "refused
-- <field>", or nil. The id's options show at its path.
function wizard.refused(out)
  for _, line in ipairs(out or {}) do
    local field = line:match("^refused (%S+)$")
    if field then
      return (field == "texture" or field == "item") and "path" or field
    end
  end
end

-- Whether a refusal names an option that job left out, because its
-- field was empty
function wizard.absent(out, job)
  for _, line in ipairs(out or {}) do
    local option = line:match("^refused (%S+)$")
    if option then
      -- The id's namespace and path are one option, passed whenever the path is filled
      if option == "namespace" or option == "path" then return job[2].texture == nil and job[2].item == nil end
      return job[2][option] == nil
    end
  end
  return false
end

-- The id of the node that shows what job created, or nil
function wizard.target(index, job)
  local task, options = job[1], job[2]
  for id, info in pairs(index or {}) do
    if task == "createGlyphBlock" then
      if info.kind == "block" and info.entry.title == options.title and info.font.font == options.font then return id end
    elseif options.item then
      if info.kind == "item" and info.entry.id == options.item then return id end
    elseif info.kind ~= "cell" and info.kind ~= "item" and info.texture and info.texture.id == options.texture then
      return id
    end
  end
end

-- Whether a node of the browser is a texture or an item rather than
-- a folder
function wizard.isChoice(id)
  return id:sub(1, 7) ~= "folder/"
end

-- The ids that the browser of kind offers from the listing of the
-- client jar: every texture, or every item that the model's pack does
-- not hold
function wizard.choices(kind, listing, model)
  local out = {}
  if kind == "override" then
    for _, texture in ipairs(listing.textures or {}) do table.insert(out, texture.id) end
    return out
  end
  local held = {}
  for _, category in ipairs(model.items or {}) do
    for _, item in ipairs(category.entries or {}) do held[item.id] = true end
  end
  for _, id in ipairs(listing.items or {}) do
    if not held[id] then table.insert(out, id) end
  end
  return out
end

-- The nodes of the ids that hold every word of query, grouped by
-- folder with an id outside a folder after them, at most limit of
-- them, and how many match
function wizard.matches(ids, query, most)
  local words = {}
  for word in query:lower():gmatch("%S+") do table.insert(words, word) end
  local folders, order, loose, count, shown = {}, {}, {}, 0, 0
  local sorted = table.move(ids, 1, #ids, 1, {})
  table.sort(sorted)
  for _, id in ipairs(sorted) do
    local matched = true
    for _, word in ipairs(words) do
      if not id:lower():find(word, 1, true) then matched = false break end
    end
    if matched then
      count = count + 1
      if shown < most then
        shown = shown + 1
        local path = id:match("^[^:]+:(.*)$") or id
        local folder, name = path:match("^(.*)/([^/]+)$")
        name = (name or path):gsub("%.png$", "")
        if not folder then
          table.insert(loose, { id=id, text=name })
        else
          if not folders[folder] then
            folders[folder] = { id="folder/" .. folder, text=folder, children={}, expanded=#words > 0 or nil }
            table.insert(order, folder)
          end
          table.insert(folders[folder].children, { id=id, text=name })
        end
      end
    end
  end
  table.sort(order)
  local nodes = {}
  for _, folder in ipairs(order) do table.insert(nodes, folders[folder]) end
  table.move(loose, 1, #loose, #nodes + 1, nodes)
  return nodes, count
end

-- text in lines of at most width characters, broken at spaces, or of
-- the width a refusal takes when width is nil. A word longer than
-- width takes a line of its own.
function wizard.wrap(text, width)
  width = width or lineWidth
  local lines, line = {}, nil
  for word in text:gmatch("%S+") do
    if line and utf8.len(line) + 1 + utf8.len(word) <= width then
      line = line .. " " .. word
    else
      if line then table.insert(lines, line) end
      line = word
    end
  end
  if line then table.insert(lines, line) end
  return lines
end

function wizard.open(context)
  local api, tr, model = context.api, context.tr, context.model
  local kind, values, refusal = "glyph", {}, nil
  -- The listing of the client jar, whether it is being read, and the
  -- id chosen in the browser of each kind
  local listing, reading, chosen = nil, false, {}
  local dlg
  -- Set once the designer closed the wizard, so no late run or
  -- listing opens it again
  local closed = false

  local labels, byLabel = {}, {}
  for _, k in ipairs(wizard.kinds) do
    local label = tr("new.kind_" .. k)
    table.insert(labels, label)
    byLabel[label] = k
  end

  -- Keeps what the fields hold, so another kind or a refusal keeps it
  local function keep()
    if not dlg then return end
    for key, value in pairs(dlg.data) do
      if type(value) == "string" then values[key] = value end
    end
  end

  local function reason(lines)
    for _, line in ipairs(wizard.wrap(table.concat(lines, " "), lineWidth)) do
      dlg:label{ text=line }
      dlg:newrow()
    end
  end

  local build

  local function create()
    keep()
    local browser = browsers[kind]
    if browser then
      if not chosen[kind] then
        refusal = { lines={ tr(browser.required) } }
        build()
        return
      end
      values[browser.option] = chosen[kind]
    end
    local job = wizard.job(kind, values)
    dlg:modify{ id="create", enabled=false }
    context.run(job, function(ok, lines, out)
      if ok then
        dlg:close()
        context.created(job)
        return
      end
      -- An empty field is asked for in the form's words, not in the task's
      if wizard.absent(out, job) then
        lines = { tr(browser and browser.required or "new.required") }
      end
      refusal = { field=wizard.refused(out), lines=lines }
      if not closed then build() end
    end)
  end

  local function browse()
    if not listing then
      dlg:label{ text=tr("new.reading") }
      dlg:newrow()
      return
    end
    local browser = browsers[kind]
    local ids = wizard.choices(kind, listing, model)
    local function counted(n)
      return n > limit and tr(browser.more, limit, n) or tr(browser.count, n)
    end
    local nodes, count = wizard.matches(ids, values.search or "", limit)
    dlg:entry{ id="search", label=tr("new.search"), text=values.search or "", onchange=function()
      values.search = dlg.data.search
      local found, n = wizard.matches(ids, values.search, limit)
      dlg:modify{ id="choices", nodes=found }
      dlg:modify{ id="count", text=counted(n) }
    end }
    dlg:newrow()
    dlg:tree{ id="choices", nodes=nodes, width=240, height=160, onchange=function(ev)
      if wizard.isChoice(ev.node) then
        chosen[kind] = ev.node
        dlg:modify{ id="chosen", text=tr("new.chosen", ev.node) }
      end
    end }
    dlg:newrow()
    dlg:label{ id="count", text=counted(count) }
    dlg:newrow()
    dlg:label{ id="chosen", text=chosen[kind] and tr("new.chosen", chosen[kind]) or tr(browser.none) }
    dlg:newrow()
  end

  local function fields()
    local own = {}
    for _, field in ipairs(wizard.fields[kind]) do
      own[field] = true
      local text = values[field] or defaults[kind][field] or ""
      if field == "font" then
        local fonts = wizard.fonts(model)
        values.font = values.font or fonts[1]
        dlg:combobox{ id="font", label=tr("new.font"), options=fonts, option=values.font, onchange=function()
          values.font = dlg.data.font
          local blocks = wizard.blocks(model, values.font)
          values.block = blocks[1]
          if kind == "glyph" then
            dlg:modify{ id="block", options=blocks, option=blocks[1] }
          end
        end }
      elseif field == "block" then
        local blocks = wizard.blocks(model, values.font)
        dlg:combobox{ id="block", label=tr("new.block"), options=blocks, option=values.block or blocks[1] }
      else
        dlg:entry{ id=field, label=tr("new." .. field), text=text }
      end
      dlg:newrow()
      if refusal and refusal.field == field then reason(refusal.lines) end
    end
    return own
  end

  function build()
    keep()
    local bounds = dlg and dlg.bounds
    -- The close of the dialog built before is not the designer's
    local old = dlg
    dlg = nil
    if old then old:close() end
    local this
    this = api.Dialog{ title=tr("new.dialog"), onclose=function()
      if dlg == this then closed = true end
    end }
    dlg = this
    dlg:combobox{ id="kind", label=tr("new.kind"), options=labels, option=tr("new.kind_" .. kind), onchange=function()
      keep()
      kind = byLabel[dlg.data.kind] or kind
      refusal = nil
      build()
    end }
    dlg:newrow()
    local own = {}
    if browsers[kind] then browse() else own = fields() end
    if refusal and not own[refusal.field] then reason(refusal.lines) end
    dlg:separator()
    dlg:button{ id="create", text=tr("new.create"), focus=true, onclick=create }
    dlg:button{ id="cancel", text=tr("edit.cancel"), onclick=function()
      closed = true
      dlg:close()
    end }
    dlg:show{ wait=false }
    -- A dialog built again stays where it was and takes the size of its
    -- new fields, and one that grew moves back inside the window
    if bounds and api.Rectangle then
      local size = dlg.bounds
      local width, height = api.screen()
      local x = math.max(0, math.min(bounds.x, width - size.width))
      local y = math.max(0, math.min(bounds.y, height - size.height))
      dlg.bounds = api.Rectangle(x, y, size.width, size.height)
    end
    -- The listing may come at once, and then builds the dialog again
    if browsers[kind] and not listing and not reading then
      reading = true
      context.listing(function(read, problem)
        reading = false
        listing = read or {}
        if problem then refusal = { lines=problem } end
        if not closed then build() end
      end)
    end
  end

  build()
end

return wizard
