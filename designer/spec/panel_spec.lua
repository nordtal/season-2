local panel = require("panel")

-- Every character is one unit wide
local function measure(text) return utf8.len(text) end

describe("the properties panel", function()
  it("puts each key on a line of its own and its value below it", function()
    assert.same({
      { text="Code", key=true }, { text="F0000" },
      { text="Name", key=true }, { text="star" },
    }, panel.lines({ { "Code", "F0000" }, { "Name", "star" } }, 20, measure))
  end)

  it("wraps a value at spaces", function()
    assert.same({ "a short", "about" },
      panel.wrap("a short about", 8, measure))
  end)

  it("wraps a path after its slashes", function()
    assert.same({ "src/assets/", "nordtal/", "star.png" },
      panel.wrap("src/assets/nordtal/star.png", 11, measure))
  end)

  it("cuts a word wider than the panel", function()
    assert.same({ "BOARD_", "CORNER" }, panel.wrap("BOARD_CORNER", 6, measure))
  end)

  it("keeps characters of more than one byte whole", function()
    assert.same({ "9 × 9", "px" }, panel.wrap("9 × 9 px", 5, measure))
  end)
end)
