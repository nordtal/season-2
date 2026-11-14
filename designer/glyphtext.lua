-- A text of the pack as the designer shows it. The UI font has no
-- glyph of the pack, so each private-use character of a text shows as
-- a token of its code and title, "[FE021: Logo, 32 px high]", and a
-- token typed or kept in a field turns back into its character.

local glyphtext = {}

-- Whether code is a private-use character, where the pack's glyphs are
local function private(code)
  return code >= 0xE000 and code <= 0xF8FF or code >= 0xF0000 and code <= 0x10FFFD
end

-- The title of each glyph and cell of the pack model by its code point.
-- A code that two fonts use takes the title of minecraft:default,
-- which the texts of the game draw with.
function glyphtext.titles(model)
  local titles = {}
  local function add(entry, replace)
    local code = tonumber(entry.code or "", 16)
    if code and entry.title and (replace or titles[code] == nil) then titles[code] = entry.title end
  end
  for _, font in ipairs(model and model.fonts or {}) do
    local default = font.font == "minecraft:default"
    for _, block in ipairs(font.blocks or {}) do
      for _, glyph in ipairs(block.glyphs or {}) do add(glyph, default) end
      for _, cell in ipairs(block.cells or {}) do add(cell, default) end
    end
  end
  return titles
end

-- text with each private-use character as a token of its code and its
-- title in titles, or of its code alone. A value that is no string
-- stays as it is.
function glyphtext.readable(text, titles)
  if type(text) ~= "string" then return text end
  return (text:gsub(utf8.charpattern, function(char)
    local code = utf8.codepoint(char)
    if not private(code) then return char end
    local title = titles and titles[code]
    local hex = string.format("%04X", code)
    return title and "[" .. hex .. ": " .. title .. "]" or "[" .. hex .. "]"
  end))
end

-- text with each token "[CODE]" or "[CODE: anything]" of a private-use
-- code as its character, so a value keeps its glyphs through a field
function glyphtext.exact(text)
  if type(text) ~= "string" then return text end
  return (text:gsub("%[(%x%x%x%x%x?%x?)(:?[^%]]*)%]", function(hex, rest)
    local code = tonumber(hex, 16)
    if not private(code) or rest ~= "" and rest:sub(1, 1) ~= ":" then return nil end
    return utf8.char(code)
  end))
end

return glyphtext
