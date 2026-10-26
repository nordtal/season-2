local drafts = require("drafts")
local fake = require("spec.fake_dialog")
local panel = require("panel")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

-- Shows a text in the labels of a dialog as the tab does, in lines of
-- at most width characters
local function labeller(width)
  return function(dialog, id, count, text, add) panel.labels(dialog, id, count, text, add, width, utf8.len) end
end

local function texture(id, draft) return { id=id, draft=draft } end

local function draft(label, state, review) return { id="nordtal:badges/" .. label .. ".png", label=label, state=state, review=review } end

-- A Dialog that answers each show with the next of answers, a
-- function(dialog), and the dialogs it showed
local function fakeDialog(answers)
  local Dialog, _, shown = fake(answers)
  return Dialog, shown
end

describe("drafts", function()
  it("names the draft of an entry the tree marks, and none of an entry that is no draft", function()
    local font = { font="minecraft:default" }
    assert.equal("nordtal:a.png", drafts.id({ kind="glyph", entry={}, texture=texture("nordtal:a.png", "nordtal:a.png"), font=font }))
    assert.equal("nordtal:s.png",
      drafts.id({ kind="sheet", entry={ draft="nordtal:s.png" }, texture=texture("nordtal:s.png", "nordtal:s.png"), font=font }))
    assert.equal("block minecraft:default FE000-FE00F",
      drafts.id({ kind="block", entry={ draft="block minecraft:default FE000-FE00F", range="FE000-FE00F", title="Badges" }, font=font }))
    assert.equal("block minecraft:default Badges", drafts.id({ kind="block", entry={ draft="block minecraft:default Badges", title="Badges" }, font=font }))
    assert.equal("font minecraft:default", drafts.id({ kind="font", entry={ font="minecraft:default", draft="font minecraft:default" }, font=font }))
    assert.equal("minecraft:x.png", drafts.id({ kind="override", entry={}, texture=texture("minecraft:x.png", "minecraft:x.png") }))
    assert.is_nil(drafts.id({ kind="glyph", entry={}, texture=texture("nordtal:a.png", nil), font=font }))
    assert.is_nil(drafts.id({ kind="cell", entry={}, texture=texture("nordtal:s.png", "nordtal:s.png"), font=font }))
    assert.is_nil(drafts.id({ kind="block", entry={ title="Badges" }, font=font }))
    assert.is_nil(drafts.id(nil))
  end)

  it("titles a publish by what it does to the pack and the names of its drafts", function()
    assert.equal("feat(resource-pack): the pack adds star", drafts.title({ draft("star", "added") }))
    assert.equal("feat(resource-pack): the pack removes star and moon",
      drafts.title({ draft("star", "deleted"), draft("moon", "deleted") }))
    assert.equal("feat(resource-pack): the pack changes star, moon and sun",
      drafts.title({ draft("star", "added"), draft("moon", "changed"), draft("sun", "deleted") }))
    assert.equal("feat(resource-pack): the pack changes a, b, c and 2 more",
      drafts.title({ draft("a", "changed"), draft("b", "changed"), draft("c", "changed"), draft("d", "changed"),
                     draft("e", "changed") }))
  end)

  it("lists every draft chosen, and publishes the chosen ones with the title once the check accepts it", function()
    local list = { draft("star", "changed"), draft("moon", "added", 42) }
    local Dialog = fakeDialog({ function(d)
      d.data = { draft2=false }
      d.handlers.draft2.onclick()
      d.data = { publish=true }
    end })
    local checked = {}
    local answer = drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list=list, check=function(title)
      table.insert(checked, title)
      return true
    end }
    assert.same({ publish={ "nordtal:badges/star.png" }, title="feat(resource-pack): the pack changes star" }, answer)
    assert.same({ "feat(resource-pack): the pack changes star" }, checked)
  end)

  it("shows each draft's state and pull request beside its name", function()
    local Dialog, shown = fakeDialog({ function(d) d.data = { cancel=true } end })
    assert.is_nil(drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed"), draft("moon", "added", 42) },
      check=function() return true end })
    local texts = {}
    for _, w in ipairs(shown[1].widgets) do
      if w.kind == "check" then table.insert(texts, w.args.text) end
    end
    assert.same({ "publish.draft star drafts.changed", "publish.draft_review moon drafts.added 42" }, texts)
  end)

  it("writes a new title for the drafts chosen until the designer edits it", function()
    local Dialog, shown = fakeDialog({ function(d)
      d.data = { draft2=false }
      d.handlers.draft2.onclick()
      assert.equal("feat(resource-pack): the pack changes star", d.data.title)
      d.data = { title="fix(resource-pack): the star shines" }
      d.handlers.title.onchange()
      d.data = { draft2=true }
      d.handlers.draft2.onclick()
      d.data = { publish=true }
    end })
    local answer = drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed"), draft("moon", "changed") },
      check=function() return true end }
    assert.equal("fix(resource-pack): the star shines", answer.title)
    assert.equal(1, #shown)
  end)

  it("shows the check's reason and asks again while it refuses the title", function()
    local Dialog, shown = fakeDialog({
      function(d) d.data = { title="white star", publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    local answer = drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed") }, check=function()
      return false, { "commit-msg: \"white star\" is not type(scope): description" }
    end }
    assert.is_nil(answer)
    assert.equal(2, #shown)
    assert.same({ "commit-msg: \"white star\" is not type(scope):", "description" }, shown[2]:labels())
  end)

  it("wraps a long refusal over lines and ends the last one with ... when the refusal needs more", function()
    local Dialog, shown = fakeDialog({
      function(d) d.data = { publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    drafts.ask{ labels=labeller(9), Dialog=Dialog, tr=tr, list={ draft("star", "changed") },
      check=function() return false, { "one two three", "four five six seven eight nine ten eleven twelve" } end }
    assert.same({ "one two", "three", "four five", "six seven", "eight", "nine t..." }, shown[2]:labels())
  end)

  it("keeps an id of a long refusal whole and no line wider than 48 characters", function()
    local Dialog, shown = fakeDialog({
      function(d) d.data = { publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    local line = string.rep("x", 48)
    local id = "nordtal:textures/container/villager/profession/weaponsmith_level_two.png"
    drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed") },
      check=function() return false, { line, line, line, line, line, id } end }
    local labels = shown[2]:labels()
    for _, label in ipairs(labels) do assert.is_true(utf8.len(label) <= 48, label) end
    assert.same({ line, line, line, line, line, "nordtal:textures/container/villager/professio..." }, labels)
  end)

  it("makes the dialog wide enough for a long title and keeps it inside the window", function()
    local Dialog, shown = fakeDialog({ function(d) d.data = { cancel=true } end, function(d) d.data = { cancel=true } end })
    local function Rectangle(x, y, width, height) return { x=x, y=y, width=width, height=height } end
    local function wide(screenWidth)
      local Sized = function(args)
        local d = Dialog(args)
        rawset(d, "sizeHint", { width=200, height=100 })
        return d
      end
      drafts.ask{ labels=labeller(48), Dialog=Sized, tr=tr, list={ draft("star", "changed") }, Rectangle=Rectangle,
        screen=function() return screenWidth, 400 end, check=function() return true end }
      return rawget(shown[#shown], "bounds")
    end
    assert.same({ x=60, y=150, width=480, height=100 }, wide(600))
    assert.same({ x=8, y=150, width=284, height=100 }, wide(300))
  end)

  it("asks for a draft when none is chosen, without the check", function()
    local Dialog, shown = fakeDialog({
      function(d) d.data = { draft1=false, publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    local checked = false
    drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed") }, check=function() checked = true return true end }
    assert.is_false(checked)
    assert.same({ "publish.none_chosen", true }, { shown[2].handlers.refusal1.text, shown[2].handlers.refusal1.visible })
  end)

  it("discards the chosen drafts once the designer confirms it", function()
    local Dialog = fakeDialog({ function(d) d.data = { draft1=false, discard=true } end })
    local asked
    local answer = drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed"), draft("moon", "added") },
      check=function() return true end, confirm=function(text) asked = text return true end }
    assert.same({ discard={ "nordtal:badges/moon.png" } }, answer)
    assert.equal("publish.discard_confirm moon", asked)
  end)

  it("leaves a draft with a conflict unchosen, and refuses it when the designer chooses it", function()
    local moon = draft("moon", "changed")
    moon.conflict = { files={ "src/moon.png" }, fields={} }
    local Dialog, shown = fakeDialog({
      function(d) d.data = { draft2=true, publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    local checked = false
    drafts.ask{ labels=labeller(48), Dialog=Dialog, tr=tr, list={ draft("star", "changed"), moon }, check=function() checked = true return true end }
    assert.same({ "publish.draft_conflict moon drafts.changed", false },
      { shown[1].handlers.draft2.text, shown[1].handlers.draft2.selected })
    assert.equal("feat(resource-pack): the pack changes star", shown[1].handlers.title.text)
    assert.is_false(checked)
    assert.equal("publish.conflict moon", shown[2].handlers.refusal1.text)
  end)

  it("lists a draft's conflict with the button that resolves it, and the pull request that holds the entry", function()
    local info = { kind="glyph", entry={}, texture={ id="nordtal:badges/moon.png", draft="nordtal:badges/moon.png", review=42 } }
    local model = {
      drafts={ { id="nordtal:badges/moon.png", label="moon", state="changed",
                 conflict={ files={ "src/moon.png" }, fields={ { name="about", clone="\"a\"", main="\"b\"" } } } } },
      reviews={ { number=42, url="https://github.com/nordtal/season-2/pull/42", decision="CHANGES_REQUESTED", checks="failure",
                  comments={ { author="ana", body="Too bright.", path="resource-pack/src/moon.png", url="u1" },
                             { author="bo", body="Fine otherwise." } } } },
    }
    assert.same({
      { text="properties.conflict", value="src/moon.png, about" },
      { text="properties.resolve", field="resolve", kind="button" },
      { text="properties.discard", field="discard", kind="button" },
      { text="properties.review", value="review.changes_requested 42" },
      { text="properties.checks", value="checks.failure" },
      { text="review.comment_at ana moon.png", value="Too bright." },
      { text="bo", value="Fine otherwise." },
      { text="properties.github", field="github", kind="button" },
    }, drafts.rows(model, info, tr))
  end)

  it("shows an open pull request without a decision or checks, and nothing for an entry that is neither a draft nor in review", function()
    local info = { kind="block", entry={ review=7 } }
    local model = { reviews={ { number=7, url="https://github.com/nordtal/season-2/pull/7", comments={} } } }
    assert.same({
      { text="properties.review", value="review.open 7" },
      { text="properties.github", field="github", kind="button" },
    }, drafts.rows(model, info, tr))
    assert.same({}, drafts.rows(model, { kind="block", entry={} }, tr))
    assert.equal("https://github.com/nordtal/season-2/pull/7", drafts.reviewOf(model, info).url)
  end)
end)
