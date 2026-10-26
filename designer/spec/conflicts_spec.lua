local conflicts = require("conflicts")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

-- A Dialog that records its widgets and answers its show with answer(dialog)
local function fakeDialog(answer)
  local shown = {}
  local function Dialog(args)
    local d = { args=args, widgets={}, handlers={}, data={} }
    for _, kind in ipairs({ "canvas", "label", "radio", "button", "separator", "newrow" }) do
      d[kind] = function(self, t)
        t = t or {}
        table.insert(self.widgets, { kind=kind, args=t })
        if t.id then
          self.handlers[t.id] = t
          if kind == "radio" then self.data[t.id] = t.selected or false end
        end
        return self
      end
    end
    function d:show()
      table.insert(shown, self)
      answer(self)
    end
    return d
  end
  return Dialog, shown
end

local function image(width, height) return { width=width, height=height } end

-- What the compare canvas draws on a context of width and height
local function painted(d, width, height)
  local ops = {}
  local gc = { width=width, height=height, theme={ color={ face="face", text="text" } } }
  function gc:fillRect(r) table.insert(ops, { "fill", self.color, r.x, r.y, r.width, r.height }) end
  function gc:drawImage(img, src, dst) table.insert(ops, { "image", img, dst.x, dst.y, dst.width, dst.height }) end
  function gc:fillText(text, x, y) table.insert(ops, { "text", text, x, y }) end
  function gc:measureText(text) return { width=#text, height=10 } end
  d.handlers.compare.onpaint({ context=gc })
  return ops
end

local draft = { id="nordtal:badges/moon.png", label="moon", state="changed", conflict={
  files={ "src/assets/nordtal/textures/badges/moon.png" },
  fields={ { name="about", clone="\"The moon\"", main="\"A moon\"" }, { name="F0001 height", clone="7", main="9" } } } }

describe("conflicts", function()
  it("shows both versions of the texture side by side, the clone's on the left, each at the same whole scale", function()
    local clone, main = image(8, 8), image(16, 8)
    local Dialog, shown = fakeDialog(function(d) d.data.cancel = true end)
    assert.is_nil(conflicts.ask{ Dialog=Dialog, tr=tr, draft=draft, clone=clone, main=main })
    local images, texts = {}, {}
    for _, op in ipairs(painted(shown[1], 300, 150)) do
      if op[1] == "image" then table.insert(images, op) end
      if op[1] == "text" then table.insert(texts, op[2]) end
    end
    assert.same({ { "image", clone, 0, 20, 64, 64 }, { "image", main, 150, 20, 128, 64 } }, images)
    assert.same({ "conflict.clone", "conflict.main" }, texts)
  end)

  it("keeps the clone's version of the files and of each field unless the designer chooses main's", function()
    local Dialog, shown = fakeDialog(function(d)
      d.data.keep_clone, d.data.keep_main = false, true
      d.data.clone2, d.data.main2 = false, true
      d.data.ok = true
    end)
    local answer = conflicts.ask{ Dialog=Dialog, tr=tr, draft=draft }
    assert.same({ keep="main", fields={ about="clone", ["F0001 height"]="main" } }, answer)
    local radios = {}
    for _, w in ipairs(shown[1].widgets) do
      if w.kind == "radio" then table.insert(radios, { w.args.label or false, w.args.text, w.args.selected or false }) end
    end
    assert.same({
      { "conflict.files", "conflict.clone", true }, { false, "conflict.main", false },
      { "about", "conflict.clone_value \"The moon\"", true }, { false, "conflict.main_value \"A moon\"", false },
      { "F0001 height", "conflict.clone_value 7", true }, { false, "conflict.main_value 9", false },
    }, radios)
    assert.is_nil(shown[1].handlers.compare)
  end)

  it("asks for no version of the files when only fields conflict, and shortens a long value", function()
    local long = string.rep("x", 60)
    local only = { id="font nordtal:gui", label="nordtal:gui", conflict={ files={}, fields={
      { name="about", clone="\"" .. long .. "\"", main="null" } } } }
    local Dialog, shown = fakeDialog(function(d) d.data.ok = true end)
    assert.same({ fields={ about="clone" } }, conflicts.ask{ Dialog=Dialog, tr=tr, draft=only })
    assert.is_nil(shown[1].handlers.keep_clone)
    assert.equal("conflict.clone_value \"" .. string.rep("x", 39) .. "…", shown[1].handlers.clone1.text)
  end)
end)
