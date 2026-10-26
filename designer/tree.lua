-- The nodes of the Nordtal tab's tree, built from the pack model that
-- :resource-pack:describePack writes.

local tree = {}

-- The theme part that marks the node of a draft
tree.draftIcon = "tab_modified_icon_normal"
-- The theme part that marks a draft with a conflict and each node above it
tree.conflictIcon = "warning_box"

-- The nodes for Dialog:tree{} and an index from each node's id to
-- { kind, entry, texture, font, block }, with the marks that mark(info)
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
    return glyph.name or glyph.constant or glyph.code
  end

  local function cellText(cell, font, block)
    if cell.constant then
      return cell.constant
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
          n.badge = n.badge or glyph.code
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

  local overrides = {}
  for _, category in ipairs(model.overrides or {}) do
    local entries = {}
    for _, override in ipairs(category.entries or {}) do
      local id = override.texture.id
      local name = id:match("([^/:]+)%.png$") or id
      table.insert(entries, node("override/" .. id, name,
        { kind="override", entry=override, texture=override.texture }))
    end
    table.insert(overrides, node("overrides/" .. category.category, category.category,
      { kind="category", entry=category }, entries))
  end

  local nodes = {
    node("glyphs", tr("tree.glyphs"), { kind="root" }, fonts),
    node("items", tr("tree.items"), { kind="root" }, items),
    node("overrides", tr("tree.overrides"), { kind="root" }, overrides),
  }
  for _, root in ipairs(nodes) do
    root.expanded = true
  end
  return nodes, index
end

return tree
