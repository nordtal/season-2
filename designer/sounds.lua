-- The sound events of the pack in the properties panel: the rows of
-- an event and of each of its sounds, the forms that ask for a change
-- and the tasks of the resource pack that write it. Every rule of a
-- sound, such as the format of its file or the values of its fields,
-- stays in those tasks.

local sounds = {}

-- The file types the file dialog offers. The task refuses each but
-- Ogg Vorbis and says how to convert, so the others are offered too.
sounds.filetypes = { "ogg", "oga", "wav", "mp3", "flac", "m4a", "aiff" }

-- The fields of a sound that the panel edits, in display order, and
-- what each holds: a number with decimals, a whole number or a switch
local fields = {
  { "volume", "decimal" },
  { "pitch", "decimal" },
  { "weight", "whole" },
  { "stream", "switch" },
}

-- A number of the model as text. Aseprite builds Lua with
-- LUA_FLOORN2I=F2Ifloor, so math.tointeger would show 0.5 as 0.
local function shown(value)
  if type(value) == "number" then
    return value % 1 == 0 and string.format("%d", value) or tostring(value)
  end
  return (tostring(value))
end

-- Whether the panel of info is this module's
function sounds.handles(info)
  local kind = info and info.kind
  return kind == "sounds" or kind == "sound" or kind == "sound_file"
end

-- The event of info: the entry of a sound event, or the event of one
-- of its sounds
local function event(info)
  return info.kind == "sound" and info.entry or info.event
end

-- Whether the event replaces vanilla's: only a vanilla event can
local function vanilla(e)
  return e.id:sub(1, #"minecraft:") == "minecraft:"
end

-- The languages of the subtitle: the pack's, then any other that the
-- subtitle has
local function languages(info, e)
  local list, seen = {}, {}
  for _, language in ipairs(info.languages or {}) do
    table.insert(list, language)
    seen[language] = true
  end
  local others = {}
  for language in pairs(e.subtitles or {}) do
    if not seen[language] then table.insert(others, language) end
  end
  table.sort(others)
  table.move(others, 1, #others, #list + 1, list)
  if #list == 0 then list = { "en_us" } end
  return list
end

-- The rows of info as properties.rows gives them, each with its label
function sounds.rows(info, tr)
  local list = {}
  local function add(key, value, field)
    if value ~= nil and value ~= "" then
      table.insert(list, { key=key, label=tr("sounds." .. key), value=shown(value), field=field })
    end
  end
  local function button(key, field)
    table.insert(list, { key=key, label=tr("sounds." .. key), value=tr("sounds." .. key), field=field, button=true })
  end
  if info.kind == "sounds" then
    button("new", "newSound")
    return list
  end
  local e = event(info)
  if info.kind == "sound" then
    add("event", e.id)
    if vanilla(e) then
      add("replace", tr(e.replace and "sounds.replaces" or "sounds.keeps"), "replace")
    end
    -- The client shows vanilla's subtitle for a vanilla event that keeps vanilla's sounds
    if not vanilla(e) or e.replace then
      for _, language in ipairs(languages(info, e)) do
        local value = (e.subtitles or {})[language]
        table.insert(list, { key="subtitle", label=tr("sounds.subtitle_in", language), field="subtitle:" .. language,
                             value=value == nil and tr("sounds.no_subtitle") or shown(value) })
      end
      add("key", e.subtitle)
    end
    local n = #(e.sounds or {})
    table.insert(list, { key="count", label=tr("sounds.files"), value=n == 1 and tr("sounds.count_one") or tr("sounds.count", n) })
    button("play", "play")
    button("add_file", "addFile")
    button("add_name", "addName")
    table.insert(list, { key="delete", value=tr("sounds.delete"), field="delete" })
    return list
  end
  local s = info.entry
  add("name", s.name)
  if s.type == "event" then
    add("file", tr("sounds.of_event"))
  else
    add("file", s.file or tr("sounds.of_minecraft"))
  end
  for _, f in ipairs(fields) do
    local name, kind = f[1], f[2]
    local value = s[name]
    if kind == "switch" then
      value = tr(value and "sounds.on" or "sounds.off")
    elseif value == nil then
      value = tr("sounds.default", name == "weight" and "1" or "1.0")
    end
    add(name, value, "field:" .. name)
  end
  button("play", "play")
  button("remove", "remove")
  return list
end

-- The question that confirms the delete of the event of info, as
-- lines of their own
function sounds.confirm(info, tr)
  local e = event(info)
  return { tr("sounds.delete_question", e.id), tr(vanilla(e) and "sounds.delete_vanilla_note" or "sounds.delete_note") }
end

-- The form that asks for a change of field: { title, widgets }
function sounds.form(info, field, tr)
  local e = event(info)
  local language = field:match("^subtitle:(.+)$")
  if language then
    local title = tr("sounds.subtitle_of", e.id, language)
    return { title=title, widgets={
      { kind="entry", id="value", label=language, text=(e.subtitles or {})[language] or "" } } }
  elseif field == "replace" then
    local title = tr("sounds.replace")
    return { title=title, widgets={
      { kind="check", id="replace", text=tr("sounds.replace_check"), selected=e.replace == true },
      { kind="label", id="note", text=tr("sounds.replace_note") } } }
  elseif field == "addFile" then
    local title = tr("sounds.add_file")
    return { title=title, widgets={
      { kind="file", id="file", label=tr("sounds.file"), open=true, filetypes=sounds.filetypes },
      { kind="label", id="note", text=tr("sounds.ogg_note") } } }
  elseif field == "addName" then
    local title = tr("sounds.add_name")
    return { title=title, widgets={
      { kind="entry", id="name", label=tr("sounds.name"), text="" },
      { kind="label", id="note", text=tr("sounds.name_note") } } }
  elseif field == "remove" then
    local title = tr("sounds.remove")
    return { title=title, widgets={ { kind="label", id="confirm", text=tr("sounds.remove_confirm", info.entry.name) } } }
  end
  local name = field:match("^field:(.+)$")
  local s = info.entry
  local title = tr("sounds." .. name)
  if name == "stream" then
    return { title=title, widgets={
      { kind="check", id="value", text=tr("sounds.stream_check"), selected=s.stream == true } } }
  end
  -- An empty box clears the field, so the sound takes Minecraft's default
  return { title=title, widgets={
    { kind="entry", id="value", label=title, text=s[name] ~= nil and shown(s[name]) or "" },
    { kind="label", id="note", text=tr("sounds." .. name .. "_note") } } }
end

-- A JSON value of the field name from the text of its box, or from
-- the switch of stream: null for an empty box, else the number as
-- typed. The task checks it.
local function value(name, text)
  -- A sound streams only where it says so, so off clears the field
  if name == "stream" then
    return text and "true" or "null"
  end
  text = tostring(text or ""):match("^%s*(.-)%s*$"):gsub(",", ".")
  if text == "" then
    return "null"
  end
  -- A text that is no number goes as a JSON string, which the task refuses with its rule
  if tonumber(text) then
    return text
  end
  return '"' .. text:gsub('[\\"]', "\\%0") .. '"'
end

-- The task that writes the values data of a form for field as
-- { task, options }, or nil for none
function sounds.jobs(info, field, data)
  local e = event(info)
  if field == "delete" then
    return { "deleteSound", { sound=e.id } }
  end
  local language = field:match("^subtitle:(.+)$")
  if language then
    if data.value == ((e.subtitles or {})[language] or "") then return nil end
    if data.value == "" then
      return { "subtitleSound", { sound=e.id, delete=true } }
    end
    return { "subtitleSound", { sound=e.id, language=language, value=data.value } }
  elseif field == "replace" then
    if (data.replace == true) == (e.replace == true) then return nil end
    return { "editSound", { sound=e.id, replace=data.replace == true } }
  elseif field == "addFile" then
    if (data.file or "") == "" then return nil end
    return { "editSound", { sound=e.id, file=data.file } }
  elseif field == "addName" then
    local name = tostring(data.name or ""):match("^%s*(.-)%s*$")
    if name == "" then return nil end
    return { "editSound", { sound=e.id, name=name } }
  elseif field == "remove" then
    return { "editSound", { sound=e.id, at=info.at, remove=true } }
  end
  local name = field:match("^field:(.+)$")
  if not name then return nil end
  local s = info.entry
  local new = value(name, data.value)
  local old = s[name] == nil and "null" or value(name, name == "stream" and s[name] == true or shown(s[name]))
  if new == old then return nil end
  return { "editSound", { sound=e.id, at=info.at, fields='{"' .. name .. '":' .. new .. "}" } }
end

-- The options of playSound for info: the event and, for one of its
-- sounds, its place
function sounds.play(info)
  return { sound=event(info).id, at=info.kind == "sound_file" and info.at or nil }
end

-- The commands that play the WAV file wav on the operating system,
-- each tried after the one before failed: the system's own player on
-- macOS and Windows, PulseAudio's or ALSA's on Linux
function sounds.players(os, wav)
  if os == "macos" then
    return { { "afplay", wav } }
  elseif os == "windows" then
    local quoted = "'" .. wav:gsub("'", "''") .. "'"
    return { { "powershell", "-NoProfile", "-NonInteractive", "-Command",
               "(New-Object Media.SoundPlayer " .. quoted .. ").PlaySync()" } }
  end
  return { { "paplay", wav }, { "aplay", "-q", wav } }
end

return sounds
