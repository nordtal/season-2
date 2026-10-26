-- The nodes of the Nordtal tab's tree, built from the pack model that
-- :resource-pack:describePack writes.

local tree = {}

-- The nodes for Dialog:tree{} and an index from each node's id to
-- { kind, entry, texture, font, block }
function tree.build(model, tr)
  local index = {}

  local function node(id, text, info, children)
    index[id] = info
    return { id=id, text=text, children=children }
  end

  local function glyphText(glyph)
    return glyph.name or glyph.constant or glyph.code
  end

  local function cellText(cell)
    return cell.constant or utf8.char(tonumber(cell.code, 16))
  end

  local fonts = {}
  for _, font in ipairs(model.fonts or {}) do
    local blocks = {}
    for b, block in ipairs(font.blocks or {}) do
      local key = font.font .. "/" .. b
      local children = {}
      if block.kind == "sheet" then
        for _, cell in ipairs(block.cells or {}) do
          table.insert(children, node("cell/" .. font.font .. "/" .. cell.code, cellText(cell),
            { kind="cell", entry=cell, texture=block.texture, font=font, block=block }))
        end
      else
        for _, glyph in ipairs(block.glyphs or {}) do
          local n = node("glyph/" .. font.font .. "/" .. glyph.code, glyphText(glyph),
            { kind="glyph", entry=glyph, texture=glyph.texture, font=font, block=block })
          n.badge = glyph.code
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
