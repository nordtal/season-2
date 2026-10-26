-- The Nordtal plugin of Nordtal Designer. The app's bootstrap loads
-- this file from the clone and calls init(plugin) and exit(plugin).
-- plugin.path is this folder, plugin.bootstrap holds the clone and
-- the environment its tools run with.

-- plugin.require() is in version 4 of the fork's API, the width of
-- Dialog:tree{} in version 5
local forkApi = 5

local ctl, listener

local function write(path, text)
  local file = assert(io.open(path, "wb"))
  file:write(text)
  file:close()
end

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
  -- An older app has no plugin.require(), so the alert that asks for
  -- an update loads the strings' module itself
  local old = (app.forkApiVersion or 0) < forkApi
  local language = old and dofile(join(plugin.path, "language.lua")) or plugin.require("language")
  local tr = language.load(plugin.path, app.preferences.general.language, read, join)
  if old then
    app.alert{ title=tr("tab.title"), text=tr("update.text") }
    return
  end

  ctl = plugin.require("tab").open{
    api={
      Dialog=Dialog,
      Rectangle=Rectangle,
      screen=function() return app.window.width, app.window.height end,
      Process=Process,
      Timer=Timer,
      Image=Image,
      Color=Color,
      Sprite=Sprite,
      alert=app.alert,
      windows=app.os.windows,
      join=join,
      read=read,
      write=write,
      getenv=os.getenv,
      decode=json.decode,
      encode=json.encode,
      launch=function(url) app.command.Launch{ type="url", path=url } end,
      sprites=function() return app.sprites end,
      activeSprite=function() return app.sprite end,
      open=app.open,
      isFile=fs.isFile,
      filePath=fs.filePath,
      makeAllDirectories=fs.makeAllDirectories,
      fitScreen=function() app.command.FitScreen() end,
    },
    modules={
      pack=plugin.require("pack"),
      tree=plugin.require("tree"),
      textures=plugin.require("textures"),
      panel=plugin.require("panel"),
      properties=plugin.require("properties"),
      items=plugin.require("items"),
      wizard=plugin.require("wizard"),
      drafts=plugin.require("drafts"),
      conflicts=plugin.require("conflicts"),
    },
    bootstrap=plugin.bootstrap,
    preferences=plugin.preferences,
    tr=tr,
  }
  listener = app.events:on("beforecommand", function(ev) ctl.onBeforeCommand(ev) end)

  plugin:newCommand{
    id="NordtalNewTexture",
    title=tr("command.new_texture"),
    group="file_new",
    onclick=function() ctl.create() end,
  }

  plugin:newCommand{
    id="NordtalPublish",
    title=tr("command.publish"),
    group="file_save",
    onclick=function() ctl.publish() end,
  }

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
