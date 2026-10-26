-- The Nordtal tab: the pack tree beside the embedded editor, and the
-- properties of the selected entry below them.
--
-- tab.open{ api, modules, bootstrap, preferences, tr } builds the tab
-- and reads the pack. api holds the Aseprite functions that pack and
-- textures name, plus Dialog and activeSprite. modules holds pack,
-- tree and textures. preferences.treeSide is "left" or "right".

local tab = {}

-- The most rows the properties of one entry take
local rows = 14

-- The properties of a tree entry, as { key, value } in display order
local function properties(info, tr)
  local list = {}
  local function add(key, value)
    if value ~= nil and value ~= "" then
      table.insert(list, { key, tostring(value) })
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
    add("size", t.width and tr("properties.pixels", t.width, t.height))
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
    local list = properties(index and selected and index[selected], tr)
    for i = 1, rows do
      local row = list[i]
      if row then
        dlg:modify{ id="property" .. i, visible=true,
                    text=tr("properties.row", tr("properties." .. row[1]), row[2]) }
      else
        dlg:modify{ id="property" .. i, visible=false }
      end
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
    if preferences.treeSide == "right" then
      addEditor()
      dlg:samerow()
      addTree()
    else
      addTree()
      dlg:samerow()
      addEditor()
    end
    dlg:newrow()
    dlg:label{ id="status", text=status.text, visible=status.visible }
    for i = 1, rows do
      dlg:newrow()
      dlg:label{ id="property" .. i, text="", visible=false }
    end
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
  end

  -- Moves the tree to "left" or "right" of the editor
  function ctl.setSide(side)
    preferences.treeSide = side
    local sprite = dlg.data.editor
    dlg:close()
    build(sprite)
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
