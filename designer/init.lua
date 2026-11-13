-- The Nordtal plugin of Nordtal Designer. The app's bootstrap loads
-- this file from the clone and calls init(plugin) and exit(plugin).
-- plugin.path is this folder, plugin.bootstrap holds the clone and
-- the environment its tools run with.

-- plugin.require() is in version 4 of the fork's API, the width of
-- Dialog:tree{} in version 5, Dialog:panel{} in version 6,
-- Dialog:splitter{} in version 7, in version 8 the bootstrap's kiosk
-- keeps every sprite in the Nordtal tab, which the save before another
-- entry opens relies on, and in version 9 the splitter puts the
-- properties beside the tree, the tree scrolls with the mouse wheel and
-- a wheel event says whether it comes from a trackpad
local forkApi = 9

-- The install lines of the README of nordtal/designer, which also update
local installLines = {
  macos="curl -fsSL https://raw.githubusercontent.com/nordtal/designer/main/install.sh | bash",
  windows="irm https://raw.githubusercontent.com/nordtal/designer/main/install.ps1 | iex",
}

local ctl, listener, outdated

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
  -- An app before fork API 4 has no plugin.require(), so the status
  -- that asks for an update loads the strings' module itself. Any later
  -- app requires it, since a chunk that dofile() loads isn't trusted
  -- and its reading of the strings would raise the security alert.
  local old = (app.forkApiVersion or 0) < forkApi
  local language = plugin.require and plugin.require("language") or dofile(join(plugin.path, "language.lua"))
  local tr = language.load(plugin.path, app.preferences.general.language, read, join)
  if old then
    -- One status in place of the Nordtal tab, like the bootstrap's
    -- failures, but it can't be closed and offers only Quit, since the
    -- app's version changes only with a restart. The install line is
    -- an entry, so it can be copied.
    local windows = app.os and app.os.windows
    outdated = Dialog{ notitlebar=true }
    outdated:label{ text=tr(windows and "update.text_windows" or "update.text") }
    outdated:newrow()
    outdated:entry{ text=windows and installLines.windows or installLines.macos }
    outdated:newrow()
    outdated:button{ text=tr("update.quit"), focus=true, onclick=function() app.command.Exit() end }
    outdated:show{ wait=false }
    return
  end

  ctl = plugin.require("tab").open{
    api={
      Dialog=Dialog,
      Rectangle=Rectangle,
      screen=function() return app.window.width, app.window.height end,
      Process=Process,
      Timer=Timer,
      WebSocket=WebSocket,
      messages=WebSocketMessageType,
      chooseFolder=fs.chooseFolder,
      setClipboard=function(text) app.clipboard.text = text end,
      Image=Image,
      Color=Color,
      Sprite=Sprite,
      alert=app.alert,
      tip=function(text) app.tip{ text=text, duration=5 } end,
      windows=app.os.windows,
      os=app.os.macos and "macos" or app.os.windows and "windows" or "linux",
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
      isDirectory=fs.isDirectory,
      listFiles=fs.listFiles,
      filePath=fs.filePath,
      language=app.preferences.general.language,
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
      minecraft=plugin.require("minecraft"),
      sounds=plugin.require("sounds"),
      reveal=plugin.require("reveal"),
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
    id="NordtalNewSound",
    title=tr("command.new_sound"),
    group="file_new",
    onclick=function() ctl.createSound() end,
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
  if outdated then
    outdated:close()
  end
  if listener then
    app.events:off(listener)
  end
  if ctl then
    ctl.close()
  end
end
