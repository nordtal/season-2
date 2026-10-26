local drafts = require("drafts")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

local function texture(id, draft) return { id=id, draft=draft } end

local function draft(label, state, review) return { id="nordtal:badges/" .. label .. ".png", label=label, state=state, review=review } end

-- A Dialog that records its widgets and answers each show with the
-- next of answers, a function(dialog)
local function fakeDialog(answers)
  local shown = {}
  local function Dialog(args)
    local d = { args=args, widgets={}, handlers={}, values={}, modified={} }
    setmetatable(d, {
      __index=function(t, k) if k == "data" then return rawget(t, "values") end end,
      __newindex=function(t, k, v)
        if k ~= "data" then return rawset(t, k, v) end
        for name, value in pairs(v) do t.values[name] = value end
      end,
    })
    for _, kind in ipairs({ "check", "entry", "label", "button", "separator", "newrow" }) do
      d[kind] = function(self, t)
        t = t or {}
        table.insert(self.widgets, { kind=kind, args=t })
        if t.id then
          self.handlers[t.id] = t
          if kind == "check" then self.values[t.id] = t.selected end
          if kind == "entry" then self.values[t.id] = t.text end
        end
        return self
      end
    end
    function d:modify(t)
      table.insert(self.modified, t)
      for k, v in pairs(t) do self.handlers[t.id][k] = v end
      if t.text and self.handlers[t.id] and self.values[t.id] ~= nil then self.values[t.id] = t.text end
    end
    function d:show()
      table.insert(shown, self)
      self.values.publish, self.values.discard, self.values.cancel = nil, nil, nil
      table.remove(answers, 1)(self)
    end
    return d
  end
  return Dialog, shown
end

describe("drafts", function()
  it("names the draft of an entry the tree marks, and none of an entry that is no draft", function()
    local font = { font="minecraft:default" }
    assert.equal("nordtal:a.png", drafts.id({ kind="glyph", entry={}, texture=texture("nordtal:a.png", true), font=font }))
    assert.equal("nordtal:s.png", drafts.id({ kind="sheet", entry={ draft=true }, texture=texture("nordtal:s.png", true), font=font }))
    assert.equal("block minecraft:default FE000-FE00F",
      drafts.id({ kind="block", entry={ draft=true, range="FE000-FE00F", title="Badges" }, font=font }))
    assert.equal("block minecraft:default Badges", drafts.id({ kind="block", entry={ draft=true, title="Badges" }, font=font }))
    assert.equal("font minecraft:default", drafts.id({ kind="font", entry={ font="minecraft:default", draft=true }, font=font }))
    assert.equal("minecraft:x.png", drafts.id({ kind="override", entry={}, texture=texture("minecraft:x.png", true) }))
    assert.is_nil(drafts.id({ kind="glyph", entry={}, texture=texture("nordtal:a.png", false), font=font }))
    assert.is_nil(drafts.id({ kind="cell", entry={}, texture=texture("nordtal:s.png", true), font=font }))
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
    local answer = drafts.ask{ Dialog=Dialog, tr=tr, list=list, check=function(title)
      table.insert(checked, title)
      return true
    end }
    assert.same({ publish={ "nordtal:badges/star.png" }, title="feat(resource-pack): the pack changes star" }, answer)
    assert.same({ "feat(resource-pack): the pack changes star" }, checked)
  end)

  it("shows each draft's state and pull request beside its name", function()
    local Dialog, shown = fakeDialog({ function(d) d.data = { cancel=true } end })
    assert.is_nil(drafts.ask{ Dialog=Dialog, tr=tr, list={ draft("star", "changed"), draft("moon", "added", 42) },
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
    local answer = drafts.ask{ Dialog=Dialog, tr=tr, list={ draft("star", "changed"), draft("moon", "changed") },
      check=function() return true end }
    assert.equal("fix(resource-pack): the star shines", answer.title)
    assert.equal(1, #shown)
  end)

  it("shows the check's reason and asks again while it refuses the title", function()
    local Dialog, shown = fakeDialog({
      function(d) d.data = { title="white star", publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    local answer = drafts.ask{ Dialog=Dialog, tr=tr, list={ draft("star", "changed") }, check=function()
      return false, { "commit-msg: \"white star\" is not type(scope): description" }
    end }
    assert.is_nil(answer)
    assert.equal(2, #shown)
    assert.same({ id="refusal", text="commit-msg: \"white star\" is not type(scope): description", visible=true },
      shown[2].modified[#shown[2].modified])
  end)

  it("asks for a draft when none is chosen, without the check", function()
    local Dialog, shown = fakeDialog({
      function(d) d.data = { draft1=false, publish=true } end,
      function(d) d.data = { cancel=true } end,
    })
    local checked = false
    drafts.ask{ Dialog=Dialog, tr=tr, list={ draft("star", "changed") }, check=function() checked = true return true end }
    assert.is_false(checked)
    assert.same({ id="refusal", text="publish.none_chosen", visible=true }, shown[2].modified[#shown[2].modified])
  end)

  it("discards the chosen drafts once the designer confirms it", function()
    local Dialog = fakeDialog({ function(d) d.data = { draft1=false, discard=true } end })
    local asked
    local answer = drafts.ask{ Dialog=Dialog, tr=tr, list={ draft("star", "changed"), draft("moon", "added") },
      check=function() return true end, confirm=function(text) asked = text return true end }
    assert.same({ discard={ "nordtal:badges/moon.png" } }, answer)
    assert.equal("publish.discard_confirm moon", asked)
  end)
end)
