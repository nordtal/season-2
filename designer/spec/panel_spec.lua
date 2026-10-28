local panel = require("panel")

-- Every character is one unit wide
local function measure(text) return utf8.len(text) end

describe("the properties panel", function()
  -- Each line is 2 units high with a text 1 high, and a row follows the one before after 1
  local function layout(rows, width)
    return panel.layout(rows, width, measure, 2, 1)
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

  it("wraps a key to the panel's width and puts its value below its last line", function()
    assert.same({ { "Give minecraft:", 0, 0, true }, { "lodestone_tracker", 0, 2, true }, { "/give", 0, 4, false } },
      texts(layout({ { text="Give minecraft:lodestone_tracker", value="/give" } }, 20)))
  end)

  it("draws a value without a key below the row before", function()
    assert.same({ { "Refused", 0, 0, true }, { "admin", 0, 2, false }, { "a.java:1", 0, 5, false } },
      texts(layout({ { text="Refused", value="admin" }, { value="a.java:1" } }, 20)))
  end)

  it("draws a value that can be edited in a box that a click on it hits", function()
    local drawn = layout({ { text="Name", value="star", field="name" } }, 20)
    assert.same({ { "Name", 0, 0, true }, { "star", 6, 8, false } }, texts(drawn))
    assert.same({ op="box", x=0, y=2, width=20, height=13 }, drawn.ops[2])
    assert.equal("name", panel.hit(drawn, 5, 4))
    assert.is_nil(panel.hit(drawn, 5, 1))
    assert.equal(15, drawn.height)
  end)

  it("keeps the text of a box inside the box's edge with a gap on all four sides", function()
    local drawn = layout({ { value="a long value", field="v" } }, 20)
    assert.same({ { "a long", 6, 6, false }, { "value", 6, 8, false } }, texts(drawn))
    assert.same({ op="box", x=0, y=0, width=20, height=15 }, drawn.ops[1])
  end)

  it("centres the text of a box and of a button, the same gap above and below", function()
    local function gaps(drawn)
      local frame, first, last = drawn.ops[1], nil, nil
      for _, d in ipairs(drawn.ops) do
        if d.op == "text" then first, last = first or d, d end
      end
      return first.y - frame.y, frame.y + frame.height - (last.y + 1)
    end
    local above, below = gaps(layout({ { value="star", field="name" } }, 20))
    assert.equal(above, below)
    above, below = gaps(layout({ { value="a long value", field="v" } }, 20))
    assert.equal(above, below)
    -- The bottom edge of a button is 2 thicker than its top edge
    above, below = gaps(layout({ { text="Delete", field="delete", kind="button" } }, 20))
    assert.equal(above + 2, below)
  end)

  it("draws a switch as a check box beside its text", function()
    local drawn = layout({ { text="Coupled", value=true, field="coupled", kind="switch" } }, 20)
    assert.same({ op="check", x=0, y=0, size=2, checked=true }, drawn.ops[1])
    assert.same({ { "Coupled", 5, 0, false } }, texts(drawn))
    assert.equal("coupled", panel.hit(drawn, 10, 1))
  end)

  it("draws a button as wide as the panel with its text in the middle", function()
    local drawn = layout({ { text="Delete", field="delete", kind="button" } }, 20)
    assert.same({ op="button", x=0, y=0, width=20, height=15 }, drawn.ops[1])
    assert.same({ { "Delete", 7, 6, false } }, texts(drawn))
    assert.equal("delete", panel.hit(drawn, 19, 14))
  end)

  it("wraps a value at spaces", function()
    assert.same({ "a short", "about" },
      panel.wrap("a short about", 8, measure))
  end)

  it("wraps a path after its slashes", function()
    assert.same({ "src/assets/", "nordtal/", "star.png" },
      panel.wrap("src/assets/nordtal/star.png", 11, measure))
  end)

  it("wraps a command only at spaces and punctuation, never inside a word or an escape", function()
    assert.same({ "/tellraw @s", '{"text":', '"\\U000F0000"}' },
      panel.wrap('/tellraw @s {"text":"\\U000F0000"}', 14, measure))
  end)

  it("keeps a run of punctuation marks together and ends the word after the last one", function()
    assert.same({ "wird", "geprüft..." }, panel.wrap("wird geprüft...", 13, measure))
  end)

  it("ends no word at an underscore or at a full stop between two letters or digits", function()
    assert.same({ "at", "0.63.0" }, panel.wrap("at 0.63.0", 6, measure))
    assert.same({ "give", "custom_name" }, panel.wrap("give custom_name", 12, measure))
    assert.same({ "nordtal-bridge-", "0.63.0.jar" }, panel.wrap("nordtal-bridge-0.63.0.jar", 17, measure))
  end)

  it("keeps an umlaut and an SNBT escape whole", function()
    assert.same({ "Größe:", '"\\U000FE011"' }, panel.wrap('Größe:"\\U000FE011"', 12, measure))
  end)

  it("cuts a word wider than the panel", function()
    assert.same({ "BOARD_", "CORNER" }, panel.wrap("BOARD_CORNER", 6, measure))
  end)

  it("keeps characters of more than one byte whole", function()
    assert.same({ "9 × 9", "px" }, panel.wrap("9 × 9 px", 5, measure))
  end)

  it("gives at most most lines, the last one the rest of the text cut at the width with ...", function()
    assert.same({ "src/assets/", "nordtal/..." }, panel.wrap("src/assets/nordtal/star.png", 11, measure, 2))
    assert.same({ "a short", "about" }, panel.wrap("a short about", 8, measure, 2))
  end)

  it("shows a text in a fixed set of labels and hides the labels it does not need", function()
    local Dialog = require("spec.fake_dialog")()
    local d = Dialog()
    panel.labels(d, "hint", 3, "a short about", true, 8, measure)
    assert.same({ "a short", "about" }, d:labels())
    assert.same({ "hint1", "hint2", "hint3" }, { d.widgets[1].args.id, d.widgets[3].args.id, d.widgets[5].args.id })
    panel.labels(d, "hint", 3, "src/assets/nordtal/star.png and more", false, 11, measure)
    assert.same({ "src/assets/", "nordtal/", "star.png..." }, d:labels())
    panel.labels(d, "hint", 3, nil, false, 11, measure)
    assert.same({}, d:labels())
  end)

  describe("scroll bar", function()
    it("shows none while the content fits", function()
      assert.is_nil(panel.scrollbar(100, 100, 0))
      assert.is_nil(panel.scrollbar(40, 100, 0))
    end)

    it("gives the thumb the share of the content that shows, at the place that shows", function()
      assert.same({ thumb=0, height=50 }, panel.scrollbar(200, 100, 0))
      assert.same({ thumb=25, height=50 }, panel.scrollbar(200, 100, 50))
      assert.same({ thumb=50, height=50 }, panel.scrollbar(200, 100, 100))
      -- A scroll past the end keeps the thumb at the end
      assert.same({ thumb=50, height=50 }, panel.scrollbar(200, 100, 500))
    end)

    it("keeps the thumb at least twice as long as the bar is wide", function()
      assert.same({ thumb=88, height=12 }, panel.scrollbar(10000, 100, 10000))
    end)
  end)

  describe("bar", function()
    -- A bar 100 wide, its text 1 high
    local function bar(items, width)
      return panel.bar(items, width or 100, measure, 2, 1)
    end

    it("puts its items on one line, each beside the one before with a gap, and their texts on one baseline", function()
      local drawn = bar({ { kind="box", text="/mc", field="choose" }, { kind="switch", text="Sync", value=true, field="live" },
                          { kind="button", text="Install", field="pack" }, { kind="text", text="ok", color="good" } })
      assert.same({
        { op="box", x=0, y=0, width=15, height=13 },
        { op="text", text="/mc", x=6, y=6 },
        { op="check", x=21, y=5, size=2, checked=true },
        { op="text", text="Sync", x=26, y=6 },
        { op="button", x=36, y=0, width=19, height=15 },
        { op="text", text="Install", x=42, y=6 },
        { op="text", text="ok", x=61, y=6, color="good" },
      }, drawn.ops)
      assert.equal(15, drawn.height)
      assert.equal("choose", panel.hit(drawn, 1, 1))
      assert.equal("live", panel.hit(drawn, 29, 6))
      assert.equal("pack", panel.hit(drawn, 54, 14))
      assert.is_nil(panel.hit(drawn, 62, 6))
    end)

    it("keeps the text of a box and of a button inside its edge, with the same gap above as at the sides", function()
      local drawn = bar({ { kind="button", text="Install" } })
      local frame, text = drawn.ops[1], drawn.ops[2]
      assert.equal(text.x - frame.x, text.y - frame.y)
      assert.equal(frame.x + frame.width - (text.x + 7), text.y - frame.y)
    end)

    it("shortens the items that may shrink to a quarter of the bar each, and gives a shortened one its whole text as a tip", function()
      local drawn = bar({ { kind="box", text="/Users/me/mc/fabric", field="choose", shrink="start" },
                          { kind="text", text="The change was refused", shrink="end" } }, 40)
      assert.same({ op="box", x=0, y=0, width=22, height=13 }, drawn.ops[1])
      assert.same({ op="text", text=".../fabric", x=6, y=6, cut=true }, drawn.ops[2])
      assert.same({ op="text", text="The chang...", x=28, y=6, cut=true }, drawn.ops[3])
      assert.equal("/Users/me/mc/fabric", panel.hit(drawn, 1, 1, "tip"))
      assert.equal("The change was refused", panel.hit(drawn, 30, 6, "tip"))
    end)

    it("keeps the items that may shrink at their least characters except the last, which shortens further, and marks a text that the bar's end cuts", function()
      local drawn = bar({ { kind="box", text="/Users/me/mc/fabric", field="choose", shrink="start", least=4 },
                          { kind="button", text="Install", field="pack" },
                          { kind="text", text="The change was refused", shrink="end", least=6 } }, 30)
      assert.same({ op="box", x=0, y=0, width=19, height=13 }, drawn.ops[1])
      assert.same({ op="text", text="...bric", x=6, y=6, cut=true }, drawn.ops[2])
      assert.same({ op="text", text="...", x=50, y=6, cut=true }, drawn.ops[5])
      assert.equal("choose", panel.hit(drawn, 1, 1))
      assert.equal("The change was refused", panel.hit(drawn, 51, 6, "tip"))
      -- A text that fits whole but runs past the end of the bar is cut too
      drawn = bar({ { kind="button", text="Install" }, { kind="text", text="ok", shrink="end", least=6 } }, 20)
      assert.same({ op="text", text="ok", x=25, y=6, cut=true }, drawn.ops[3])
      assert.equal("ok", panel.hit(drawn, 25, 6, "tip"))
    end)

    it("switches the buttons with a short text to it when the items do not fit at their least, and shows the long one as the tip", function()
      local function items()
        return { { kind="box", text="fabric", field="choose", shrink="start", least=4 },
                 { kind="switch", text="Live-Sync", value=true, field="live" },
                 { kind="button", text="Pack installieren", short="Installieren", field="pack" },
                 { kind="button", text="Bridge-Mod aktualisieren", short="Bridge-Mod", field="bridge" },
                 { kind="text", text="Nicht erreichbar", color="wait" },
                 { kind="text", text="Die Bridge-Mod des neuesten Releases wird geladen...", shrink="end", least=12, divider=true } }
      end
      local drawn = bar(items(), 160)
      assert.same({ op="button", x=44, y=0, width=24, height=15 }, drawn.ops[5])
      assert.same({ op="text", text="Installieren", x=50, y=6, cut=true }, drawn.ops[6])
      assert.same({ op="button", x=74, y=0, width=22, height=15 }, drawn.ops[7])
      assert.same({ op="text", text="Bridge-Mod", x=80, y=6, cut=true }, drawn.ops[8])
      assert.same({ op="text", text="Nicht erreichbar", x=102, y=6, color="wait" }, drawn.ops[9])
      assert.same({ op="text", text="Die Bridge-Mod des neueste...", x=131, y=6, cut=true }, drawn.ops[11])
      assert.equal("pack", panel.hit(drawn, 45, 1))
      assert.equal("Pack installieren", panel.hit(drawn, 45, 1, "tip"))
      assert.equal("Bridge-Mod aktualisieren", panel.hit(drawn, 75, 1, "tip"))
      -- The long texts stay while every item fits at its least
      drawn = bar(items(), 165)
      assert.same({ op="text", text="Pack installieren", x=50, y=6 }, drawn.ops[6])
      assert.same({ op="text", text="Bridge-Mod aktualisieren", x=85, y=6 }, drawn.ops[8])
      assert.same({ op="text", text="Die Bridge-M...", x=150, y=6, cut=true }, drawn.ops[11])
      -- The status gives up its least characters when the short labels do not free enough room
      drawn = bar(items(), 140)
      assert.same({ op="text", text="Bridge-Mod", x=80, y=6, cut=true }, drawn.ops[8])
      assert.same({ op="text", text="Die Br...", x=131, y=6, cut=true }, drawn.ops[11])
      assert.equal("Die Bridge-Mod des neuesten Releases wird geladen...", panel.hit(drawn, 135, 6, "tip"))
    end)

    it("shortens the short text of an item that has one and may shrink", function()
      local drawn = bar({ { kind="button", text="Install the pack", short="Install" },
                          { kind="text", text="abcdefghijklmnop", short="qrstuvwxyz", shrink="end", least=2 } }, 34)
      assert.same({ op="text", text="qrstuv...", x=25, y=6, cut=true }, drawn.ops[3])
      assert.equal("abcdefghijklmnop", panel.hit(drawn, 26, 6, "tip"))
    end)

    it("draws a divider with a gap on each side before an item that asks for one", function()
      local drawn = bar({ { kind="text", text="ok", color="good" }, { kind="text", text="Saved", divider=true } })
      assert.same({ op="divider", x=8, y=3, height=7 }, drawn.ops[2])
      assert.same({ op="text", text="Saved", x=15, y=6 }, drawn.ops[3])
    end)

    it("shows the tip of an item that has one", function()
      local drawn = bar({ { kind="text", text="refused", color="bad", tip="Minecraft refused the token." } })
      assert.equal("Minecraft refused the token.", panel.hit(drawn, 1, 6, "tip"))
      assert.is_nil(panel.hit(drawn, 1, 6))
    end)
  end)
end)
