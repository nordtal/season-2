-- The layout of the properties panel: each key on a line of its own
-- and its value below it, wrapped to the panel's width, a value that
-- can be edited in a box, a switch as a check box and a button.
-- measure(text) gives the width of a text.

local panel = {}

local function trim(text)
  return (text:gsub("%s+$", ""))
end

-- The words of a text, each with the spaces after it. A word ends after
-- a space, and after a punctuation mark that follows a letter. A run of
-- marks ends the word only after its last mark. An opening mark (a
-- quote, a bracket or a backslash), an underscore and a full stop
-- between two letters or digits end no word.
local function words(text)
  local chars = {}
  for _, code in utf8.codes(text) do table.insert(chars, utf8.char(code)) end
  local function kind(c)
    if c == nil then return "end" end
    if c:match("^%s$") then return "space" end
    if c:match("^[\\%[{(\"'_]$") then return "open" end
    if c:match("^%p$") then return "mark" end
    return "letter"
  end
  local list, word, letters = {}, "", false
  for i, c in ipairs(chars) do
    word = word .. c
    local this, after = kind(c), kind(chars[i + 1])
    local joins = c == "." and kind(chars[i - 1]) == "letter" and after == "letter"
    local ends = this == "space" or letters and this == "mark" and after ~= "mark" and not joins
    letters = letters or this == "letter"
    if ends and after ~= "space" then
      table.insert(list, word)
      word, letters = "", false
    end
  end
  if word ~= "" then table.insert(list, word) end
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
        for _, line in ipairs(panel.wrap(row.text, width, measure)) do
          text(line, 0, y, true)
          y = y + lineHeight
        end
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

-- The field of a layout that a click at x, y hits, or with what="tip"
-- the tip of what lies there, or nil
function panel.hit(layout, x, y, what)
  for _, h in ipairs(layout.hits) do
    local value = h[what or "field"]
    if value ~= nil and x >= h.x and x < h.x + h.width and y >= h.y and y < h.y + h.height then
      return value
    end
  end
end

-- chars as a text that keeps keep of them, with "..." at its start or end
local function clip(chars, keep, from)
  if keep >= #chars then
    return (table.concat(chars))
  end
  return from == "start" and "..." .. table.concat(chars, "", #chars - keep + 1)
    or trim(table.concat(chars, "", 1, keep)) .. "..."
end

-- text cut to width with "..." at its start or end, keeping at least
-- least of its characters
local function shorten(text, width, measure, from, least)
  local chars = {}
  for _, code in utf8.codes(text) do table.insert(chars, utf8.char(code)) end
  for keep = #chars - 1, least + 1, -1 do
    local short = clip(chars, keep, from)
    if measure(short) <= width then
      return short
    end
  end
  return (clip(chars, least, from))
end

-- What to draw for a bar of items on one line and where a click hits one: { ops, hits, height }. An item is
-- { kind, text, value, field, tip, color, shrink, least, divider }: kind is "box", "switch", "button" or "text".
-- An item with shrink ("start" or "end") gives up room when the line is too short: each first down to a quarter
-- of the width, then from the last one on down to least characters, 1 unless given. Its text then loses its
-- start or end. No item leaves the bar. A text that is shortened or runs past the bar's end has cut set and
-- shows whole as its tip. An item with divider has a line before it, with a gap on each side.
function panel.bar(items, width, measure, lineHeight, textHeight)
  local sized = {}
  for _, item in ipairs(items) do
    local frame = (item.kind == "box" or item.kind == "button") and 2 * inset
      or item.kind == "switch" and lineHeight + gap or 0
    table.insert(sized, { item=item, text=item.text, frame=frame, inner=measure(item.text),
                          lead=item.divider and inset + 1 or 0 })
  end
  local function excess()
    local total = -inset
    for _, s in ipairs(sized) do
      total = total + inset + s.lead + s.frame + s.inner
    end
    return total - width
  end
  local function give(s, floor)
    local inner = math.max(floor, s.inner - math.max(0, excess()))
    local short = inner < s.inner and shorten(s.item.text, inner, measure, s.item.shrink, s.item.least or 1)
    if short and measure(short) < s.inner then
      s.text, s.inner = short, measure(short)
    end
  end
  for _, s in ipairs(sized) do
    if s.item.shrink then give(s, math.min(s.inner, width // 4)) end
  end
  for i = #sized, 1, -1 do
    if sized[i].item.shrink then give(sized[i], 0) end
  end

  local ops, hits, x = {}, {}, 0
  local height = textHeight + 2 * inset + buttonShadow
  for _, s in ipairs(sized) do
    local item = s.item
    if #ops > 0 then
      x = x + inset
      if item.divider then
        table.insert(ops, { op="divider", x=x, y=inset // 2, height=textHeight + inset })
        x = x + 1 + inset
      end
    end
    local w = s.frame + s.inner
    local tx = x
    if item.kind == "box" then
      table.insert(ops, { op="box", x=x, y=0, width=w, height=textHeight + 2 * inset })
      tx = x + inset
    elseif item.kind == "button" then
      table.insert(ops, { op="button", x=x, y=0, width=w, height=height })
      tx = x + inset
    elseif item.kind == "switch" then
      table.insert(ops, { op="check", x=x, y=inset + (textHeight - lineHeight) // 2, size=lineHeight,
                          checked=item.value == true })
      tx = x + lineHeight + gap
    end
    local cut = s.text ~= item.text or x + w > width or nil
    table.insert(ops, { op="text", text=s.text, x=tx, y=inset, color=item.color, cut=cut })
    local tip = item.tip or (cut and item.text or nil)
    if item.field ~= nil or tip then
      table.insert(hits, { field=item.field, tip=tip, x=x, y=0, width=w, height=height })
    end
    x = x + w
  end
  return { ops=ops, hits=hits, height=height }
end

return panel
