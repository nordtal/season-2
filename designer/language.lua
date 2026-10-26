-- The interface strings in German and English, read from Aseprite
-- string files under strings/ in the language Aseprite is set to.

local language = {}

local escapes = { n="\n", t="\t", s=" ", ["\\"]="\\" }

-- The strings of an Aseprite string file, keyed "section.key"
function language.parse(text)
  local strings, section = {}, nil
  for line in (text .. "\n"):gmatch("([^\r\n]*)\r?\n") do
    local name = line:match("^%s*%[([^%]]+)%]%s*$")
    if name then
      section = name
    elseif section and not line:match("^%s*[;#]") then
      local key, value = line:match("^%s*([^=%s]+)%s*=%s*(.-)%s*$")
      if key then
        strings[section .. "." .. key] = (value:gsub("\\(.)", function(c) return escapes[c] or c end))
      end
    end
  end
  return strings
end

-- tr(id, ...) for the strings of a language, with English for each
-- key the language lacks. {0}, {1} name the arguments, {} takes the
-- next one, as in Aseprite's strings. tr takes the form <key>_one of a
-- key when that form exists and the call's only number argument is 1.
function language.translator(english, chosen)
  local function lookup(id)
    return (chosen and chosen[id]) or english[id]
  end
  return function(id, ...)
    local args, next = { ... }, 0
    local numbers = {}
    for i = 1, select("#", ...) do
      if type(args[i]) == "number" then table.insert(numbers, args[i]) end
    end
    if #numbers == 1 and numbers[1] == 1 and lookup(id .. "_one") then
      id = id .. "_one"
    end
    local text = lookup(id) or id
    return (text:gsub("{(%d*)}", function(n)
      local index
      if n == "" then
        next = next + 1
        index = next
      else
        index = tonumber(n) + 1
      end
      local value = args[index]
      return value ~= nil and tostring(value) or nil
    end))
  end
end

-- The translator for the language id Aseprite is set to. read(path)
-- returns a file's text or nil.
function language.load(dir, id, read, join)
  local function strings(lang)
    local text = read(join(dir, "strings", lang .. ".ini"))
    return text and language.parse(text) or nil
  end
  local english = strings("en") or {}
  local chosen = id ~= "en" and strings(id) or nil
  return (language.translator(english, chosen))
end

return language
