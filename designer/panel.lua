-- The layout of the properties panel: each key on a line of its own
-- and its value below it, wrapped to the panel's width, a value that
-- can be edited in a box, a switch as a check box and a button.
-- measure(text) gives the width of a text.

local panel = {}

local function trim(text)
  return (text:gsub("%s+$", ""))
end

-- The words of a text, each with the space or slash that ends it
local function words(text)
  local list = {}
  for word in text:gmatch("[^%s/]*[%s/]*") do
    if word ~= "" then table.insert(list, word) end
  end
  return list
end

-- The pieces of a word, each as wide as fits
local function cut(word, width, measure)
  local pieces, piece = {}, ""
  for _, code in utf8.codes(word) do
    local c = utf8.char(code)
    if piece ~= "" and measure(trim(piece .. c)) > width then
      table.insert(pieces, piece)
      piece = c
    else
      piece = piece .. c
    end
  end
  if piece ~= "" then table.insert(pieces, piece) end
  return pieces
end

-- The lines of a text no wider than width, where a word allows it
function panel.wrap(text, width, measure)
  local lines, line = {}, ""
  for _, word in ipairs(words(text)) do
    for _, piece in ipairs(cut(word, width, measure)) do
      if line ~= "" and measure(trim(line .. piece)) > width then
        table.insert(lines, trim(line))
        line = piece
      else
        line = line .. piece
      end
    end
  end
  if trim(line) ~= "" then table.insert(lines, trim(line)) end
  return lines
end

-- The space between a box or a button and its text, in points: the
-- 4-point edge of the theme parts, which Aseprite's entry widget
-- insets its text by and Lua cannot read, plus a visible gap of 2
local inset = 6

-- The bottom edge of the part button_normal, 2 points thicker
local buttonShadow = 2

-- The space between a check box and its text
local gap = 3

-- What to draw for rows { text, value, field, kind } and where a click hits a field: { ops, hits, height }.
-- An op is a text { text, x, y, key }, a box, a check or a button. kind is nil for a key and its value or a
-- value alone, "switch" for a check box with its text, or "button". textHeight is the height of a line's
-- text, lineHeight that and the space below it.
function panel.layout(rows, width, measure, lineHeight, textHeight)
  local ops, hits, y = {}, {}, 0
  local function text(t, x, ty, key)
    table.insert(ops, { op="text", text=t, x=x, y=ty, key=key })
  end
  for i, row in ipairs(rows) do
    if i > 1 then
      y = y + lineHeight // 2
    end
    local top, height = y, 0
    if row.kind == "switch" then
      local size = lineHeight
      table.insert(ops, { op="check", x=0, y=y, size=size, checked=row.value == true })
      local lines = panel.wrap(row.text, width - size - gap, measure)
      for n, line in ipairs(lines) do text(line, size + gap, y + (n - 1) * lineHeight) end
      height = math.max(size, #lines * lineHeight)
    elseif row.kind == "button" then
      local lines = panel.wrap(row.text, width - 2 * inset, measure)
      height = (math.max(1, #lines) - 1) * lineHeight + textHeight + 2 * inset + buttonShadow
      table.insert(ops, { op="button", x=0, y=y, width=width, height=height })
      for n, line in ipairs(lines) do
        text(line, (width - measure(line)) // 2, y + inset + (n - 1) * lineHeight)
      end
    else
      if row.text then
        text(row.text, 0, y, true)
        y = y + lineHeight
      end
      top = y
      local lines = panel.wrap(row.value, width - (row.field and 2 * inset or 0), measure)
      if row.field then
        height = (math.max(1, #lines) - 1) * lineHeight + textHeight + 2 * inset
        table.insert(ops, { op="box", x=0, y=y, width=width, height=height })
        for n, line in ipairs(lines) do text(line, inset, y + inset + (n - 1) * lineHeight) end
      else
        for n, line in ipairs(lines) do text(line, 0, y + (n - 1) * lineHeight) end
        height = #lines * lineHeight
      end
    end
    if row.field then
      table.insert(hits, { field=row.field, x=0, y=top, width=width, height=height })
    end
    y = top + height
  end
  return { ops=ops, hits=hits, height=y }
end

-- The field of a layout that a click at x, y hits, or nil
function panel.hit(layout, x, y)
  for _, h in ipairs(layout.hits) do
    if x >= h.x and x < h.x + h.width and y >= h.y and y < h.y + h.height then
      return h.field
    end
  end
end

return panel
