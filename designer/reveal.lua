-- The file of the pack behind an entry of the tree, and the command
-- that shows it in the system's file manager: selected in Finder on
-- macOS and in Explorer on Windows, its folder on Linux.
--
-- An entry without a file of its own shows the file that names it: a
-- font, a glyph block or a glyph without a texture glyphs.json, a part
-- of an item's definition the definition, a text the language file
-- that holds its key, and a sound event, or a sound of Minecraft or of
-- an event, the sounds.json of the event's namespace. A text that no
-- language file holds any more shows the folder of new language files.

local reveal = {}

-- The path of the event's sounds.json, relative to the pack
local function soundsJson(id)
  local namespace = tostring(id):match("^([^:]+):") or "minecraft"
  return "src/assets/" .. namespace .. "/sounds.json"
end

-- The language files that may hold a text, in the order they are
-- tried: the designer's language and its other regions, then English,
-- then the pack's other languages, each in every namespace of the pack
local function languageFiles(info, context)
  local order, seen = {}, {}
  local function add(language)
    if language and not seen[language] then
      seen[language] = true
      table.insert(order, language)
    end
  end
  add(context.language)
  -- Another region of the designer's language, such as pt_br for pt
  local prefix = tostring(context.language or ""):match("^(%a%a)_")
  for _, language in ipairs(info.languages or {}) do
    if prefix and language:sub(1, 3) == prefix .. "_" then add(language) end
  end
  add("en_us")
  for _, language in ipairs(info.languages or {}) do add(language) end
  local files = {}
  for _, language in ipairs(order) do
    for _, namespace in ipairs(context.namespaces or {}) do
      table.insert(files, "src/assets/" .. namespace .. "/lang/" .. language .. ".json")
    end
  end
  return files
end

-- The path of the file behind info, relative to the pack's folder, or
-- nil for an entry that has none, such as a category. context holds
-- language, the designer's language as a language file names it,
-- namespaces, the folders under src/assets, holds(path, key), whether
-- the language file at path holds key, and item(id), the entry of the
-- item id.
function reveal.file(info, context)
  if not info then
    return nil
  end
  local kind, e = info.kind, info.entry
  if info.texture and info.texture.png and kind ~= "font" and kind ~= "item" then
    return info.texture.png
  end
  if kind == "font" or kind == "block" or kind == "sheet" or kind == "glyph" or kind == "cell" then
    return "glyphs.json"
  end
  if kind == "item" then
    return e.file
  end
  if kind == "definition" then
    local item = context.item and context.item(info.item)
    return item and item.file
  end
  if kind == "text" then
    for _, path in ipairs(languageFiles(info, context)) do
      if context.holds(path, e.key) then
        return path
      end
    end
    -- The folder where a new text's language file goes
    return "src/assets/minecraft/lang"
  end
  if kind == "sound" then
    return soundsJson(e.id)
  end
  if kind == "sound_file" then
    return e.file or soundsJson(info.event.id)
  end
  return nil
end

-- Whether info has a file that the panel offers to show. item(id) gives
-- the entry of the item id.
function reveal.offered(info, item)
  local any = { namespaces={ "" }, item=item, holds=function() return true end }
  return reveal.file(info, any) ~= nil
end

-- The command that shows path in the file manager of os ("macos",
-- "windows" or "linux"): the file selected, or on Linux its folder.
-- A path that is no file opens the nearest folder above it that is
-- there. fs holds isFile, isDirectory and folderOf.
function reveal.command(os, path, fs)
  local file = fs.isFile(path)
  local folder = file and fs.folderOf(path) or path
  while not file and folder and folder ~= "" and not fs.isDirectory(folder) do
    local above = fs.folderOf(folder)
    if above == folder then break end
    folder = above
  end
  if os == "macos" then
    return file and { "/usr/bin/open", "-R", path } or { "/usr/bin/open", folder }
  elseif os == "windows" then
    return file and { "explorer", "/select,", path } or { "explorer", folder }
  end
  return { "xdg-open", folder }
end

-- The language file's name for the designer's language, such as de_de
-- for de and en_us for en, or nil for none
function reveal.language(code)
  local language = tostring(code or ""):lower():match("^(%a%a)")
  if language == "en" then
    return "en_us"
  end
  return language and language .. "_" .. language or nil
end

return reveal
