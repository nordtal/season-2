-- The wizard that creates a texture: it asks for the kind first and
-- then for the options of the kind's task, which checks them. A
-- refusal shows below the field it names, and the dialog stays open.
-- A vanilla override is chosen in a browser of the client jar's
-- textures.
--
-- wizard.open{ api, tr, model, run, listing, created } shows it. api
-- holds Dialog, Rectangle and screen, which gives the window's width
-- and height. run(job, done) runs a { task, options }
-- and calls done(ok, reason, out) with the lines of the refusal and
-- what the task printed. listing(done) calls done(textures) with the
-- textures of the client jar, or done(nil, reason). created(job) is
-- called once the task succeeded.

local wizard = {}

wizard.kinds = { "glyph", "block", "sheet", "item", "override" }

-- The fields of each kind but the override, in the order the dialog
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

local tasks = { glyph="createGlyph", block="createGlyphBlock", sheet="createGlyphSheet", item="createItem",
                override="copyVanillaTexture" }

-- How many textures of the client jar the browser shows at once
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
-- asks for it.
function wizard.job(kind, values)
  local options = {}
  if kind == "override" then
    options.texture = values.texture
    return { tasks[kind], options }
  end
  local namespace, path = trim(values.namespace), trim(values.path)
  for _, field in ipairs(wizard.fields[kind]) do
    local value = trim(values[field])
    if field ~= "namespace" and field ~= "path" and value ~= "" then options[field] = value end
  end
  if namespace ~= "" or path ~= "" then
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
    if option then return job[2][option] == nil end
  end
  return false
end

-- The id of the node that shows what job created, or nil
function wizard.target(index, job)
  local task, options = job[1], job[2]
  for id, info in pairs(index or {}) do
    if task == "createGlyphBlock" then
      if info.kind == "block" and info.entry.title == options.title and info.font.font == options.font then return id end
    elseif task == "createItem" then
      if info.kind == "item" and info.entry.id == options.item then return id end
    elseif info.kind ~= "cell" and info.kind ~= "item" and info.texture and info.texture.id == options.texture then
      return id
    end
  end
end

-- Whether a node of the browser is a texture rather than a folder
function wizard.isTexture(id)
  return id:sub(1, 7) ~= "folder/"
end

-- The nodes of the textures whose id holds every word of query,
-- grouped by folder and at most limit of them, and how many match
function wizard.matches(textures, query, most)
  local words = {}
  for word in query:lower():gmatch("%S+") do table.insert(words, word) end
  local folders, order, count, shown = {}, {}, 0, 0
  local sorted = {}
  for _, texture in ipairs(textures) do table.insert(sorted, texture.id) end
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
        local folder, name = id:match("^[^:]+:(.*)/([^/]+)%.png$")
        folder, name = folder or "", name or id
        if not folders[folder] then
          folders[folder] = { id="folder/" .. folder, text=folder, children={}, expanded=#words > 0 or nil }
          table.insert(order, folder)
        end
        table.insert(folders[folder].children, { id=id, text=name })
      end
    end
  end
  table.sort(order)
  local nodes = {}
  for _, folder in ipairs(order) do table.insert(nodes, folders[folder]) end
  return nodes, count
end

-- text in lines of at most width characters, broken at spaces. A word
-- longer than width takes a line of its own.
function wizard.wrap(text, width)
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
  local textures, reading, chosen
  local dlg

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
    if kind == "override" then values.texture = chosen end
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
        lines = { tr(kind == "override" and "new.required_texture" or "new.required") }
      end
      refusal = { field=wizard.refused(out), lines=lines }
      build()
    end)
  end

  local function browser()
    if not textures then
      dlg:label{ text=tr("new.reading") }
      dlg:newrow()
      return
    end
    local nodes, count = wizard.matches(textures, values.search or "", limit)
    dlg:entry{ id="search", label=tr("new.search"), text=values.search or "", onchange=function()
      values.search = dlg.data.search
      local found, n = wizard.matches(textures, values.search, limit)
      dlg:modify{ id="textures", nodes=found }
      dlg:modify{ id="count", text=n > limit and tr("new.count_more", limit, n) or tr("new.count", n) }
    end }
    dlg:newrow()
    dlg:tree{ id="textures", nodes=nodes, width=240, height=160, onchange=function(ev)
      if wizard.isTexture(ev.node) then
        chosen = ev.node
        dlg:modify{ id="chosen", text=tr("new.chosen", chosen) }
      end
    end }
    dlg:newrow()
    dlg:label{ id="count", text=count > limit and tr("new.count_more", limit, count) or tr("new.count", count) }
    dlg:newrow()
    dlg:label{ id="chosen", text=chosen and tr("new.chosen", chosen) or tr("new.chosen_none") }
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
    if dlg then dlg:close() end
    dlg = api.Dialog{ title=tr("new.dialog") }
    dlg:combobox{ id="kind", label=tr("new.kind"), options=labels, option=tr("new.kind_" .. kind), onchange=function()
      keep()
      kind = byLabel[dlg.data.kind] or kind
      refusal = nil
      build()
    end }
    dlg:newrow()
    local own = {}
    if kind == "override" then browser() else own = fields() end
    if refusal and not own[refusal.field] then reason(refusal.lines) end
    dlg:separator()
    dlg:button{ id="create", text=tr("new.create"), focus=true, onclick=create }
    dlg:button{ id="cancel", text=tr("edit.cancel"), onclick=function() dlg:close() end }
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
    if kind == "override" and not textures and not reading then
      reading = true
      context.listing(function(list, problem)
        reading = false
        textures = list or {}
        if problem then refusal = { lines=problem } end
        build()
      end)
    end
  end

  build()
end

return wizard
