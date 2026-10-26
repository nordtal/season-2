-- The Nordtal tab: the pack tree, the embedded editor and the
-- properties of the selected entry, side by side. The properties
-- stay beside the editor, on the side away from the tree.
--
-- tab.open{ api, modules, bootstrap, preferences, tr } builds the tab
-- and reads the pack. api holds the Aseprite functions that pack and
-- textures name, plus Dialog and activeSprite. modules holds pack,
-- tree, textures and panel. preferences.treeSide is "left" or "right".

local tab = {}

-- The width of the properties panel, in points
local panelWidth = 150

-- A number of the model as text: JSON gives every number as a float
local function shown(value)
  if type(value) == "number" then
    return tostring(math.tointeger(value) or value)
  end
  return tostring(value)
end

-- The properties of a tree entry, as { key, value } in display order
local function properties(info, tr)
  local list = {}
  local function add(key, value)
    if value ~= nil and value ~= false and value ~= "" then
      table.insert(list, { key, shown(value) })
    end
  end
  if not info then
    return list
  end

  local e, kind = info.entry, info.kind
  if info.font and kind ~= "font" then
    add("font", info.font.font)
  end
  if kind == "font" then
    add("font", e.font)
    add("constant", e.constant)
    add("about", e.about)
  elseif kind == "block" or kind == "sheet" then
    add("block", e.title)
    add("range", e.range)
    add("status", e.status)
    add("height", e.height)
    add("ascent", e.ascent)
    add("about", e.about)
  elseif kind == "glyph" or kind == "cell" then
    add("block", info.block.title)
    add("code", e.code)
    add("name", e.name)
    add("constant", e.constant)
    add("status", e.status or info.block.status)
    add("height", e.height)
    add("ascent", e.ascent)
    add("about", e.about)
  elseif kind == "item" then
    add("item", e.id)
    add("model", e.model)
    add("file", e.file)
  elseif kind == "override" then
    add("mcmeta", e.mcmeta)
    add("animation", e.animation and tr("properties.yes"))
    add("scaling", type(e.scaling) == "table" and e.scaling.type)
  end

  local t = info.texture
  if t then
    add("texture", t.id)
    add("size", t.width and tr("properties.pixels", shown(t.width), shown(t.height)))
    add("png", t.png)
    add("source", t.asepriteExists and t.aseprite or tr("properties.no_source"))
  end
  return list
end

function tab.open(context)
  local api, modules, tr = context.api, context.modules, context.tr
  local preferences, clone = context.preferences, context.bootstrap.clone
  local ctl = {}
  local dlg, nodes, index, selected
  local status = { text=tr("status.loading"), visible=true }
  -- What the tab opened, by the filenames of its PNG and its source
  local opened = {}

  local function setStatus(text)
    status = { text=text or "", visible=text ~= nil }
    dlg:modify{ id="status", text=status.text, visible=status.visible }
  end

  local function showProperties()
    dlg:repaint()
  end

  -- Draws the properties of the selected entry on the theme's face,
  -- keys in the color of its section labels and values below them
  local function paintProperties(ev)
    local gc = ev.context
    gc.color = gc.theme.color.face
    gc:fillRect{ x=0, y=0, width=gc.width, height=gc.height }
    local rows = {}
    for _, row in ipairs(properties(index and selected and index[selected], tr)) do
      table.insert(rows, { tr("properties." .. row[1]), row[2] })
    end
    local pad = 4
    local function measure(text) return gc:measureText(text).width end
    local lineHeight = gc:measureText("Ag").height + 2
    local width = gc.width - 2 * pad
    local lines = modules.panel.lines(rows, width, measure)
    if #rows == 0 then
      for _, text in ipairs(modules.panel.wrap(tr("properties.none"), width, measure)) do
        table.insert(lines, { text=text })
      end
    end
    local y = pad
    for i, line in ipairs(lines) do
      if line.key and i > 1 then
        y = y + lineHeight // 2
      end
      gc.color = line.key and gc.theme.color.separator_label or gc.theme.color.text
      gc:fillText(line.text, pad, y)
      y = y + lineHeight
    end
  end

  local function build(sprite)
    dlg = api.Dialog{ title=tr("tab.title"), tab=true, closable=false }
    local function addTree()
      dlg:tree{ id="tree", nodes=nodes or {}, hexpand=false,
                onchange=function(ev) ctl.select(ev.node) end }
    end
    local function addEditor()
      dlg:editor{ id="editor", sprite=sprite }
    end
    local function addPanel()
      dlg:canvas{ id="properties", width=panelWidth, hexpand=false, onpaint=paintProperties }
    end
    local order = { addTree, addEditor, addPanel }
    if preferences.treeSide == "right" then
      order = { addPanel, addEditor, addTree }
    end
    for i, add in ipairs(order) do
      if i > 1 then dlg:samerow() end
      add()
    end
    dlg:newrow()
    dlg:label{ id="status", text=status.text, visible=status.visible }
    if selected then
      dlg.data = { tree=selected }
    end
    showProperties()
    dlg:show()
  end

  -- Shows the entry of a node and opens its texture in the editor
  function ctl.select(id)
    selected = id
    showProperties()
    local info = index and index[id]
    if not (info and info.texture) then
      return
    end
    local paths = modules.pack.paths(clone, info.texture, api.join)
    local sprite, missing = modules.textures.open(info.texture, paths, api)
    if not sprite then
      setStatus(tr("status.missing", missing))
      return
    end
    local record = { texture=info.texture, paths=paths }
    opened[paths.png], opened[paths.aseprite] = record, record
    setStatus(nil)
    dlg:modify{ id="editor", sprite=sprite }
    -- A texture of a few pixels fills the editor
    api.fitScreen()
  end

  -- Moves the tree to "left" or "right" of the editor
  function ctl.setSide(side)
    preferences.treeSide = side
    local sprite = dlg.data.editor
    dlg:close()
    build(sprite)
    if sprite then
      api.fitScreen()
    end
  end

  -- Saves a sprite the tab opened as its source and its PNG instead
  -- of Aseprite's own save
  function ctl.onBeforeCommand(ev)
    if ev.name ~= "SaveFile" then
      return
    end
    local sprite = api.activeSprite()
    local record = sprite and opened[sprite.filename]
    if not record then
      return
    end
    ev.stopPropagation()
    local ok, err = pcall(modules.textures.save, sprite, record.texture, record.paths, api)
    if ok then
      setStatus(nil)
      showProperties()
    else
      setStatus(tr("status.save_failed") .. "\n" .. tostring(err))
    end
  end

  -- Reads the pack again and shows its nodes
  function ctl.reload()
    setStatus(tr("status.loading"))
    return (modules.pack.describe(context.bootstrap, api, function(model, err)
      if not model then
        setStatus(tr("status.failed") .. "\n" .. table.concat(err, "\n"))
        return
      end
      nodes, index = modules.tree.build(model, tr)
      dlg:modify{ id="tree", nodes=nodes }
      setStatus(nil)
      showProperties()
    end))
  end

  function ctl.close()
    dlg:close()
  end

  build(nil)
  ctl.reload()
  return ctl
end

return tab
