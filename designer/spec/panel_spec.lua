local panel = require("panel")

-- Every character is one unit wide
local function measure(text) return utf8.len(text) end

describe("the properties panel", function()
  -- Each line is 2 units high and a row follows the one before after 1
  local function layout(rows, width)
    return panel.layout(rows, width, measure, 2)
  end

  local function texts(drawn)
    local out = {}
    for _, d in ipairs(drawn.ops) do
      if d.op == "text" then table.insert(out, { d.text, d.x, d.y, d.key or false }) end
    end
    return out
  end

  it("puts each key on a line of its own and its value below it", function()
    assert.same({ { "Code", 0, 0, true }, { "F0000", 0, 2, false }, { "Name", 0, 5, true }, { "star", 0, 7, false } },
      texts(layout({ { text="Code", value="F0000" }, { text="Name", value="star" } }, 20)))
  end)

  it("draws a value without a key below the row before", function()
    assert.same({ { "Refused", 0, 0, true }, { "admin", 0, 2, false }, { "a.java:1", 0, 5, false } },
      texts(layout({ { text="Refused", value="admin" }, { value="a.java:1" } }, 20)))
  end)

  it("draws a value that can be edited in a box that a click on it hits", function()
    local drawn = layout({ { text="Name", value="star", field="name" } }, 20)
    assert.same({ { "Name", 0, 0, true }, { "star", 3, 5, false } }, texts(drawn))
    assert.same({ op="box", x=0, y=2, width=20, height=8 }, drawn.ops[2])
    assert.equal("name", panel.hit(drawn, 5, 4))
    assert.is_nil(panel.hit(drawn, 5, 1))
    assert.equal(10, drawn.height)
  end)

  it("draws a switch as a check box beside its text", function()
    local drawn = layout({ { text="Coupled", value=true, field="coupled", kind="switch" } }, 20)
    assert.same({ op="check", x=0, y=0, size=2, checked=true }, drawn.ops[1])
    assert.same({ { "Coupled", 5, 0, false } }, texts(drawn))
    assert.equal("coupled", panel.hit(drawn, 10, 1))
  end)

  it("draws a button as wide as the panel with its text in the middle", function()
    local drawn = layout({ { text="Delete", field="delete", kind="button" } }, 20)
    assert.same({ op="button", x=0, y=0, width=20, height=8 }, drawn.ops[1])
    assert.same({ { "Delete", 7, 3, false } }, texts(drawn))
    assert.equal("delete", panel.hit(drawn, 19, 5))
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
