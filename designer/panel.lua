-- The lines of the properties panel: each key on a line of its own
-- and its value below it, wrapped to the panel's width. measure(text)
-- gives the width of a text.

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

-- The lines of { key, value } rows
function panel.lines(rows, width, measure)
  local lines = {}
  for _, row in ipairs(rows) do
    table.insert(lines, { text=row[1], key=true })
    for _, text in ipairs(panel.wrap(row[2], width, measure)) do
      table.insert(lines, { text=text })
    end
  end
  return lines
end

return panel
