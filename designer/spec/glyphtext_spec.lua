local glyphtext = require("glyphtext")

-- U+FE021 and U+E000 as UTF-8
local logo = utf8.char(0xFE021)
local corner = utf8.char(0xE000)

local model = { fonts={
  { font="nordtal:gui", blocks={ { kind="sheet", cells={ { code="E000", title="Corner" }, { code="FE021", title="Other" } } } } },
  { font="minecraft:default", blocks={ { kind="block", glyphs={ { code="FE021", title="Logo, 32 px high" } } } } },
} }

describe("glyphtext", function()
  it("names each glyph by its code and title, the default font's first", function()
    local titles = glyphtext.titles(model)
    assert.are.equal("Logo, 32 px high", titles[0xFE021])
    assert.are.equal("Corner", titles[0xE000])
  end)

  it("shows a glyph in a text as a token with its code and title", function()
    local titles = glyphtext.titles(model)
    assert.are.equal("Play [FE021: Logo, 32 px high] now", glyphtext.readable("Play " .. logo .. " now", titles))
    assert.are.equal("[E000: Corner][E000: Corner]", glyphtext.readable(corner .. corner, titles))
  end)

  it("shows a glyph the pack does not name by its code alone, and leaves other text as it is", function()
    assert.are.equal("[F0001]", glyphtext.readable(utf8.char(0xF0001), {}))
    assert.are.equal("Grüße [x] 5", glyphtext.readable("Grüße [x] 5", {}))
  end)

  it("turns each token back into its glyph, whatever title it carries", function()
    local titles = glyphtext.titles(model)
    local text = "Play " .. logo .. " now " .. corner
    assert.are.equal(text, glyphtext.exact(glyphtext.readable(text, titles)))
    assert.are.equal(logo, glyphtext.exact("[FE021]"))
    assert.are.equal(logo, glyphtext.exact("[fe021: anything]"))
  end)

  it("keeps brackets that name no private-use character", function()
    assert.are.equal("[0041] [x: y] [12345678]", glyphtext.exact("[0041] [x: y] [12345678]"))
  end)

  it("leaves a value that is not a string as it is", function()
    assert.are.equal(5, glyphtext.readable(5, {}))
  end)
end)
