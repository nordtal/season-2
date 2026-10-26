local language = require("language")

describe("language", function()
  it("reads the sections and keys of an Aseprite string file", function()
    local strings = language.parse("[tab]\ntitle = Nordtal\n; a comment\n[tree]\nglyphs = Glyphs\n")
    assert.same({ ["tab.title"] = "Nordtal", ["tree.glyphs"] = "Glyphs" }, strings)
  end)

  it("unescapes a value the way Aseprite does", function()
    local strings = language.parse("[a]\nb = one\\ntwo\\sthree\\\\\n")
    assert.equal("one\ntwo three\\", strings["a.b"])
  end)

  it("translates into the chosen language and falls back to English per key", function()
    local tr = language.translator({ ["a.x"] = "X", ["a.y"] = "Y" }, { ["a.x"] = "iks" })
    assert.equal("iks", tr("a.x"))
    assert.equal("Y", tr("a.y"))
  end)

  it("shows the id of a key that no file holds", function()
    local tr = language.translator({}, nil)
    assert.equal("a.missing", tr("a.missing"))
  end)

  it("fills numbered and plain placeholders", function()
    local tr = language.translator({ ["a.n"] = "{1} of {0}", ["a.p"] = "{} and {}" }, nil)
    assert.equal("b of a", tr("a.n", "a", "b"))
    assert.equal("1 and 2", tr("a.p", 1, 2))
  end)

  it("loads the strings of the language Aseprite is set to", function()
    local files = {
      ["/d/strings/en.ini"] = "[a]\nx = cat\ny = dog\n",
      ["/d/strings/de.ini"] = "[a]\nx = Katze\n",
    }
    local read = function(path) return files[path] end
    local join = function(...) return table.concat({ ... }, "/") end
    local tr = language.load("/d", "de", read, join)
    assert.equal("Katze", tr("a.x"))
    assert.equal("dog", tr("a.y"))
    assert.equal("cat", language.load("/d", "fr", read, join)("a.x"))
  end)
end)

describe("the string files of the designer", function()
  local function read(path)
    local file = assert(io.open(path, "rb"))
    local text = file:read("a")
    file:close()
    return text
  end

  it("hold the same keys in German and English", function()
    local en = language.parse(read("strings/en.ini"))
    local de = language.parse(read("strings/de.ini"))
    local missing = {}
    for key in pairs(en) do
      if not de[key] then table.insert(missing, "de lacks " .. key) end
    end
    for key in pairs(de) do
      if not en[key] then table.insert(missing, "en lacks " .. key) end
    end
    table.sort(missing)
    assert.same({}, missing)
    assert.is_not_nil(next(en))
  end)

  it("hold every id the plugin translates", function()
    local en = language.parse(read("strings/en.ini"))
    local missing = {}
    for _, file in ipairs({ "init.lua", "tab.lua", "tree.lua", "properties.lua" }) do
      for id in read(file):gmatch('tr%("([%w_]+%.[%w_]+)"') do
        if not en[id] then table.insert(missing, file .. ": " .. id) end
      end
    end
    for key in read("properties.lua"):gmatch('add%("([%w_]+)"') do
      if not en["properties." .. key] then table.insert(missing, "properties.lua: properties." .. key) end
    end
    -- The sides of a border, whose ids properties.lua puts together
    for _, side in ipairs({ "left", "top", "right", "bottom" }) do
      if not en["properties.border_" .. side] then table.insert(missing, "properties.lua: properties.border_" .. side) end
    end
    assert.same({}, missing)
  end)
end)
