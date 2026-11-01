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
    assert.equal(2, #buttons)
    assert.equal("Try Again", buttons[1].text)
    assert.equal("Quit", buttons[2].text)
    assert.same({}, required)

    -- Try Again checks the app again and shows the status in place of the first
    buttons[1].onclick()
    assert.equal(2, #shown)
    assert.is_true(status.closed)
    assert.is_false(shown[2].closed)
    assert.same({}, required)

    -- Quit quits, and Try Again opens the tab once the app offers the version
    local again
    for _, w in ipairs(shown[2].widgets) do
      if w.kind == "button" and w.args.text == "Quit" then w.args.onclick() end
      if w.kind == "button" and w.args.text == "Try Again" then again = w.args.onclick end
    end
    assert.same({ "Exit" }, ran)
    env.app.forkApiVersion = 8
    again()
    assert.is_true(shown[2].closed)
    assert.equal(2, #shown)
    assert.same({ "language", "tab" }, { required[1], required[2] })
    env.exit()
  end)

  it("opens the Nordtal tab without a status on fork API 8", function()
    local shown, required = start(8)
    assert.same({}, shown)
    assert.equal("language", required[1])
    assert.equal("tab", required[2])
  end)
end)
