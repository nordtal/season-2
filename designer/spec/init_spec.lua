local fake = require("spec.fake_dialog")

-- Loads init.lua as the bootstrap does, in an environment of its own
-- with an app of the fork's API version, and calls init(plugin).
-- Returns the dialogs it showed, the modules it required and the
-- commands it ran.
local function start(version, os, noRequire)
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
    os=os or { windows=false },
  }
  local env = setmetatable({ app=app, Dialog=Dialog, json=require("dkjson") }, { __index=_G })
  assert(loadfile("init.lua", "t", env))()
  local plugin = { path=".", newCommand=function() end }
  plugin.require = function(name)
    table.insert(required, name)
    if name == "language" then return require("language") end
    return { open=function() return { close=function() end } end }
  end
  if noRequire then plugin.require = nil end
  env.init(plugin)
  return shown, required, ran, env
end

describe("the plugin's start", function()
  it("shows one status on an app older than fork API 9 that offers only Quit, and opens no tab", function()
    local shown, required, ran = start(8)
    assert.equal(1, #shown)
    local status = shown[1]
    assert.is_true(status.args.notitlebar)
    assert.same({ "Nordtal Designer needs an update: run this line in Terminal, then start the app again." },
                status:labels())
    local entries, buttons = {}, {}
    for _, w in ipairs(status.widgets) do
      if w.kind == "entry" then table.insert(entries, w.args) end
      if w.kind == "button" then table.insert(buttons, w.args) end
    end
    assert.same({ "curl -fsSL https://raw.githubusercontent.com/nordtal/designer/main/install.sh | bash" },
                { entries[1].text })
    assert.equal(1, #entries)
    assert.equal(1, #buttons)
    assert.equal("Quit", buttons[1].text)
    -- The strings come through plugin.require(), whose chunks the app trusts
    assert.same({ "language" }, required)

    buttons[1].onclick()
    assert.same({ "Exit" }, ran)
  end)

  it("loads the strings itself on an app without plugin.require()", function()
    local shown = start(3, nil, true)
    assert.same({ "Nordtal Designer needs an update: run this line in Terminal, then start the app again." },
                shown[1]:labels())
  end)

  it("gives the PowerShell line on Windows", function()
    local shown = start(8, { windows=true })
    local text
    for _, w in ipairs(shown[1].widgets) do
      if w.kind == "entry" then text = w.args.text end
    end
    assert.equal("irm https://raw.githubusercontent.com/nordtal/designer/main/install.ps1 | iex", text)
    assert.same({ "Nordtal Designer needs an update: run this line in PowerShell, then start the app again." },
                shown[1]:labels())
  end)

  it("opens the Nordtal tab without a status on fork API 9", function()
    local shown, required = start(9)
    assert.same({}, shown)
    assert.equal("language", required[1])
    assert.equal("tab", required[2])
  end)
end)
