-- The Nordtal plugin of Nordtal Designer. The app's bootstrap loads
-- this file from the clone and calls init(plugin) and exit(plugin).
-- plugin.path is this folder, plugin.bootstrap holds the clone and
-- the environment its tools run with.

-- Process{ env } came with version 3 of the fork's API
local forkApi = 3

local ctl, listener

local function read(path)
  local file = io.open(path, "rb")
  if not file then
    return nil
  end
  local text = file:read("a")
  file:close()
  return text
end

function init(plugin)
  if not app.isUIAvailable then
    return
  end
  local fs = app.fs
  local function join(...)
    return (fs.normalizePath(fs.joinPath(...)))
  end
  local tr = plugin.require("language").load(plugin.path, app.preferences.general.language, read, join)
  if (app.forkApiVersion or 0) < forkApi then
    app.alert{ title=tr("tab.title"), text=tr("update.text") }
    return
  end

  ctl = plugin.require("tab").open{
    api={
      Dialog=Dialog,
      Process=Process,
      Image=Image,
      windows=app.os.windows,
      join=join,
      read=read,
      decode=json.decode,
      sprites=function() return app.sprites end,
      activeSprite=function() return app.sprite end,
      open=app.open,
      isFile=fs.isFile,
      filePath=fs.filePath,
      makeAllDirectories=fs.makeAllDirectories,
    },
    modules={
      pack=plugin.require("pack"),
      tree=plugin.require("tree"),
      textures=plugin.require("textures"),
    },
    bootstrap=plugin.bootstrap,
    preferences=plugin.preferences,
    tr=tr,
  }
  listener = app.events:on("beforecommand", function(ev) ctl.onBeforeCommand(ev) end)

  plugin:newCommand{
    id="NordtalTreeRight",
    title=tr("command.tree_right"),
    group="view_controls",
    onchecked=function() return plugin.preferences.treeSide == "right" end,
    onclick=function()
      ctl.setSide(plugin.preferences.treeSide == "right" and "left" or "right")
    end,
  }
end

function exit(plugin)
  if listener then
    app.events:off(listener)
  end
  if ctl then
    ctl.close()
  end
end
