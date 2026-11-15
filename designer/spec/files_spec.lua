local files = require("files")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

local stone = { id="minecraft:blockstates/stone.json", file="src/assets/minecraft/blockstates/stone.json", name="stone.json",
                kind="blockstate", problems={} }
local lamp = { id="nordtal:models/block/lamp.json", file="src/assets/nordtal/models/block/lamp.json", name="block/lamp.json",
               kind="model", problems={ "Its parent names nordtal:block/base, which the pack does not hold.", "B" } }

-- The rows as "label field: value"
local function lines(rows)
  local out = {}
  for _, row in ipairs(rows) do
    table.insert(out, tostring(row.label) .. " " .. tostring(row.field) .. ": " .. row.value .. (row.button and " [button]" or ""))
  end
  return out
end

describe("files", function()
  it("handles Files and each of its files", function()
    assert.is_true(files.handles({ kind="files" }))
    assert.is_true(files.handles({ kind="file", entry=stone }))
    assert.is_false(files.handles({ kind="sound" }))
    assert.is_false(files.handles(nil))
  end)

  it("offers a new file on Files", function()
    assert.same({ "files.new newFile: files.new [button]" }, lines(files.rows({ kind="files" }, tr)))
  end)

  it("shows a file's path, kind and a check without problems, then its actions", function()
    assert.same({
      "files.file nil: src/assets/minecraft/blockstates/stone.json",
      "files.kind nil: files.kind_blockstate",
      "files.check nil: files.no_problems",
      "files.edit edit: files.edit [button]",
      "nil delete: files.delete",
    }, lines(files.rows({ kind="file", entry=stone }, tr)))
  end)

  it("lists each problem the check found, the first under its label", function()
    local rows = lines(files.rows({ kind="file", entry=lamp }, tr))
    assert.equal("files.problems nil: Its parent names nordtal:block/base, which the pack does not hold.", rows[3])
    assert.equal("false nil: B", rows[4])
  end)

  it("deletes a file with its task and says when Minecraft's own file applies again", function()
    assert.same({ "deleteFile", { asset="nordtal:models/block/lamp.json" } }, files.jobs({ kind="file", entry=lamp }, "delete"))
    assert.is_nil(files.jobs({ kind="files" }, "delete"))
    assert.same({ "files.delete_question nordtal:models/block/lamp.json" }, files.confirm({ kind="file", entry=lamp }, tr))
    assert.same({ "files.delete_question minecraft:blockstates/stone.json", "files.delete_vanilla_note" },
      files.confirm({ kind="file", entry=stone }, tr))
  end)

  it("opens a file in the text editor of each system", function()
    assert.same({ "/usr/bin/open", "-t", "/p/a.json" }, files.command("macos", "/p/a.json"))
    assert.same({ "explorer", "C:\\p\\a.json" }, files.command("windows", "C:\\p\\a.json"))
    assert.same({ "xdg-open", "/p/a.json" }, files.command("linux", "/p/a.json"))
  end)

  it("names each watched file whose text changed, and forgets one that is gone", function()
    local watched = { a="1", b="2", c="3" }
    local now = { a="1", b="22" }
    assert.same({ "b" }, files.changed(watched, function(path) return now[path] end))
    assert.same({ a="1", b="22" }, watched)
    assert.same({}, files.changed(watched, function(path) return now[path] end))
  end)
end)
