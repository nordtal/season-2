-- The nodes of the Nordtal tab's tree, built from the pack model that
-- :resource-pack:describePack writes.

local tree = {}

local glyphtext = require("glyphtext")

-- The theme part that marks the node of a draft
tree.draftIcon = "tab_modified_icon_normal"
-- The theme part that marks a draft with a conflict and each node above it
tree.conflictIcon = "warning_box"
-- The badge that marks an entry with unsaved changes and each node
-- above it, before the badge the node has without them
tree.unsavedBadge = "*"

-- The nodes for Dialog:tree{} and an index from each node's id to
-- { kind, entry, texture, font, block, event, at }, with the marks that mark(info)
-- gives as icons and badges, as the README says. It raises an error
-- that names the place of a cell whose code is no code point.
function tree.build(model, tr, mark)
  local index = {}
  -- How many drafts each node and the nodes inside it are, and the
  -- nodes with a conflict inside them
  local counts, conflicts = {}, {}

  local function node(id, text, info, children)
    index[id] = info
    local marks = mark(info) or {}
    local n = { id=id, text=text, children=children, icon=marks.draft and tree.draftIcon or nil }
    local inside, conflict = 0, marks.conflict
    for _, child in ipairs(children or {}) do
      inside = inside + counts[child]
      conflict = conflict or conflicts[child]
    end
    local badges = {}
    if marks.review then
      table.insert(badges, "#" .. math.tointeger(marks.review) .. (marks.failed and " " .. tr("tree.failed") or ""))
    end
    if inside > 0 then table.insert(badges, "(" .. inside .. ")") end
    n.badge = badges[1] and table.concat(badges, " ")
    if conflict then n.icon = tree.conflictIcon end
    counts[n], conflicts[n] = inside + (marks.draft and 1 or 0), conflict
    return n
  end

  local function glyphText(glyph)
    return glyph.title or glyph.name or glyph.constant or glyph.code
  end

  local function cellText(cell, font, block)
    if cell.title or cell.constant then
      return cell.title or cell.constant
    end
    local code = tonumber(cell.code, 16)
    if not code or code > 0x10FFFF then
      error(font.font .. " > " .. tostring(block.title) .. ": " .. tostring(cell.code) .. " is no code point", 0)
    end
    return (utf8.char(code))
  end

  local fonts = {}
  for _, font in ipairs(model.fonts or {}) do
    local blocks = {}
    for b, block in ipairs(font.blocks or {}) do
      local key = font.font .. "/" .. b
      local children = {}
      if block.kind == "sheet" then
        for _, cell in ipairs(block.cells or {}) do
          table.insert(children, node("cell/" .. font.font .. "/" .. cell.code, cellText(cell, font, block),
            { kind="cell", entry=cell, texture=block.texture, font=font, block=block }))
        end
      else
        for _, glyph in ipairs(block.glyphs or {}) do
          local n = node("glyph/" .. font.font .. "/" .. glyph.code, glyphText(glyph),
            { kind="glyph", entry=glyph, texture=glyph.texture, font=font, block=block })
          -- A glyph without a file says so, since the editor can show nothing
          local absent = glyph.texture and glyph.texture.pngExists == false
          n.badge = n.badge or (absent and tr("tree.no_texture") .. " " .. glyph.code or glyph.code)
          table.insert(children, n)
        end
      end
      table.insert(blocks, node(block.kind .. "/" .. key, block.title,
        { kind=block.kind, entry=block, texture=block.texture, font=font, block=block }, children))
    end
    table.insert(fonts, node("font/" .. font.font, font.font, { kind="font", entry=font, font=font }, blocks))
  end

  local items = {}
  for _, category in ipairs(model.items or {}) do
    local entries = {}
    for _, item in ipairs(category.entries or {}) do
      table.insert(entries, node("item/" .. item.id, item.id, { kind="item", entry=item, texture=item.texture }))
    end
    table.insert(items, node("items/" .. category.category, category.category,
      { kind="category", entry=category }, entries))
  end

  -- The vanilla overrides and the textures that no glyph, sheet or item
  -- shows have entries of one shape, so both are of the kind override
  local function textures(categories, prefix)
    local out = {}
    for _, category in ipairs(categories or {}) do
      local entries = {}
      for _, override in ipairs(category.entries or {}) do
        local id = override.texture.id
        local name = id:match("([^/:]+)%.png$") or id
        table.insert(entries, node(prefix .. "/" .. id, name,
          { kind="override", entry=override, texture=override.texture }))
      end
      table.insert(out, node(prefix .. "s/" .. category.category, category.category,
        { kind="category", entry=category }, entries))
    end
    return out
  end
  local overrides = textures(model.overrides, "override")
  local own = textures(model.textures, "texture")

  -- A text carries the languages of the pack, so its panel shows each,
  -- and the titles of its glyphs, so a value shows the glyphs it holds
  local titles = glyphtext.titles(model)
  local texts = {}
  for _, text in ipairs(model.texts or {}) do
    table.insert(texts, node("text/" .. text.key, text.key,
      { kind="text", entry=text, languages=model.languages, titles=titles }))
  end

  -- A sound event lists its sounds, each by the name after its namespace
  local sounds = {}
  for _, event in ipairs(model.sounds or {}) do
    local children = {}
    for i, sound in ipairs(event.sounds or {}) do
      local name = tostring(sound.name)
      table.insert(children, node("sound_file/" .. event.id .. "/" .. i, name:match(":(.+)$") or name,
        { kind="sound_file", entry=sound, event=event, at=i - 1 }))
    end
    table.insert(sounds, node("sound/" .. event.id, event.id,
      { kind="sound", entry=event, languages=model.languages, titles=titles }, children))
  end

  local nodes = {
    node("glyphs", tr("tree.glyphs"), { kind="root" }, fonts),
    node("items", tr("tree.items"), { kind="root" }, items),
    node("overrides", tr("tree.overrides"), { kind="root" }, overrides),
    node("textures", tr("tree.textures"), { kind="root" }, own),
    node("texts", tr("tree.texts"), { kind="texts", languages=model.languages, titles=titles }, texts),
    node("sounds", tr("tree.sounds"), { kind="sounds" }, sounds),
  }
  -- The badge without unsaved changes, which unsaved() falls back to
  local function keep(list)
    for _, n in ipairs(list) do
      n.saved = n.badge or false
      keep(n.children or {})
    end
  end
  keep(nodes)
  for _, root in ipairs(nodes) do
    root.expanded = true
  end
  return nodes, index
end

-- Marks each node of nodes whose entry in index has unsaved changes, as
-- changed(info) says, and each node above it with the unsaved badge. A
-- cell is part of its sheet. Sets the new badges and returns { node,
-- badge } for each node whose badge changed, with false for none.
function tree.unsaved(nodes, index, changed)
  local out = {}
  local function walk(n)
    local inside = false
    for _, child in ipairs(n.children or {}) do
      inside = walk(child) or inside
    end
    local info = index[n.id]
    local marked = inside or (info ~= nil and info.kind ~= "cell" and changed(info) == true)
    local saved = n.saved
    if saved == nil then saved = n.badge or false end
    local badge = marked and (saved and tree.unsavedBadge .. " " .. saved or tree.unsavedBadge) or saved
    if badge ~= (n.badge or false) then
      n.badge = badge or nil
      table.insert(out, { node=n.id, badge=badge })
    end
    return marked
  end
  for _, n in ipairs(nodes) do walk(n) end
  return out
end

return tree
