local fake = require("spec.fake_dialog")

-- Loads init.lua as the bootstrap does, in an environment of its own
-- with an app of the fork's API version, and calls init(plugin).
-- Returns the dialogs it showed, the modules it required and the
-- commands it ran.
local function start(version)
  local Dialog, _, shown = fake()
  local required, ran = {}, {}
  local app = {
    isUIAvailable=true,
    forkApiVersion=version,
    preferences={ general={ language="en" } },
    fs={ normalizePath=function(p) return p end, joinPath=function(...) return table.concat({ ... }, "/") end },
    command=setmetatable({}, { __index=function(_, name) return function() table.insert(ran, name) end end }),
    events={ on=function() return 1 end, off=function() end },
    window={ width=1000, height=700 },
    os={ windows=false },
    alert=function(t) table.insert(ran, "alert") end,
  }
  local env = setmetatable({ app=app, Dialog=Dialog, json=require("dkjson") }, { __index=_G })
  assert(loadfile("init.lua", "t", env))()
  local plugin = { path=".", newCommand=function() end }
  plugin.require = function(name)
    table.insert(required, name)
    if name == "language" then return require("language") end
    return { open=function() return { close=function() end } end }
  end
  env.init(plugin)
  return shown, required, ran, env
end

describe("the plugin's start", function()
  it("shows one status that asks for an update on an app older than fork API 8, and opens no tab", function()
    local shown, required, ran, env = start(7)
    assert.equal(1, #shown)
    local status = shown[1]
    assert.is_true(status.args.notitlebar)
    assert.same({ "This plugin needs a newer Nordtal Designer.",
                  "Run the install line from the README of nordtal/aseprite again, then restart the app." },
                status:labels())
    local buttons = {}
    for _, w in ipairs(status.widgets) do
      if w.kind == "button" then table.insert(buttons, w.args) end
    end
    assert.equal(1, #buttons)
    assert.equal("Quit", buttons[1].text)
    buttons[1].onclick()
    assert.same({ "Exit" }, ran)
    assert.same({}, required)
    env.exit()
    assert.is_true(status.closed)
  end)

  it("opens the Nordtal tab without a status on fork API 8", function()
    local shown, required = start(8)
    assert.same({}, shown)
    assert.is_truthy(required[1] == "language")
    local tab = false
    for _, name in ipairs(required) do
      if name == "tab" then tab = true end
    end
    assert.is_true(tab)
  end)
end)
