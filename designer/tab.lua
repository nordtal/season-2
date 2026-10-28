-- The Nordtal tab: the embedded editor below the Minecraft bar, and a
-- side panel with the pack tree above the properties of the selected
-- entry and a splitter between them. The main window docks the panel
-- outside the colour bar, or outside the tools when
-- preferences.treeSide is "right".
--
-- tab.open{ api, modules, bootstrap, preferences, tr } builds the tab
-- and reads the pack. api holds the Aseprite functions that pack and
-- textures name, plus Dialog, Rectangle, screen, alert, activeSprite
-- and write. modules holds pack, tree, textures, panel, properties,
-- items, wizard, drafts, conflicts and minecraft. preferences.treeSide
-- is "left" or "right", preferences.coupled is false once the
-- designer decoupled pixel size and display height, and
-- preferences.liveSync is false once live sync was switched off.
--
-- A click on a value of the panel asks for a new one in a form, and
-- OK runs the task that writes it. The tasks run one at a time, and
-- after them the tab reads the pack again. ctl.create() opens the
-- wizard for a new texture, which opens in the editor once created.
--
-- A selected item reads its definition, whose models, cases, entries
-- and tints the tree then shows below the item's node. The panel edits
-- the node selected there, and the first texture of its model that the
-- pack holds opens in the editor.
--
-- A bar along the top of the tab shows the Minecraft instance the pack
-- is installed into, which describeInstance reads after the pack, the
-- state of the bridge mod and the status. With live sync on, every
-- save installs the pack there, and a client with the bridge mod
-- reloads it. Below the entry the panel lists its previews, which go
-- to that client, or without Fabric are commands to copy.
--
-- ctl.publish() asks which drafts to publish or discard. A publish
-- runs the commit hook on its title first, and a pack with drafts or
-- pull requests asks GitHub for main and their state after the first
-- read and every five minutes.

local tab = {}

-- The side panel's id and its width in points, until the designer
-- drags it to another. The editor takes the rest, and a longer node of
-- the tree scrolls.
local sidePanel = "nordtal_pack"
local sideWidth = 150

-- The characters a line of text in the wizard and the publish dialog
-- holds at most
local lineWidth = 48

function tab.open(context)
  local api, modules, tr = context.api, context.modules, context.tr
  local preferences, clone = context.preferences, context.bootstrap.clone
  local properties = modules.properties
  local ctl = {}

  -- Shows text in the labels id1 to id<count> of a dialog
  local function labels(dialog, id, count, text, add)
    modules.panel.labels(dialog, id, count, text, add, lineWidth, utf8.len)
  end

  local dlg, nodes, index, lists, selected, closed, model
  -- The textures and items of the client jar, read once
  local vanilla
  -- The text of the status, nil while there is none
  local status = tr("status.loading")
  -- What the tab opened, by the filenames of its PNG and its source:
  -- { id, paths, frames } with the id of the entry's node and the count
  -- of frames of its PNG when it was opened or last saved
  local opened = {}
  -- The Gradle runs that wait, each a function(done) that starts one
  -- and returns its Process, and the one that runs as { process }
  local queue, running = {}, nil
  -- The reason why the last task for the selected entry was refused
  local refused
  -- The definition of the item last selected, as readItem printed it
  local definition
  -- Opens a texture in the editor, defined below
  local open
  -- Where the panel and the bar last drew their fields, their margin,
  -- how far the wheel scrolled the panel and the height of a line
  local drawn, barDrawn
  local pad = 4
  local scroll, lineHeight = 0, 0
  -- synced says whether the tab asked for the pull requests since it
  -- opened, every holds the timer that asks again, and syncing says
  -- whether such a run waits
  local synced, every, syncing
  -- sync asks GitHub for the pull requests; it is defined below
  local sync
  -- The node that opens again once the pack was read
  local reopen
  -- What the status says once the pack was read again, in place of nothing
  local notice
  -- The Minecraft instance as describeInstance printed it, the
  -- connection to its bridge mod with the port and token it was made
  -- for, and whether an installPack waits in the queue
  local instance, bridge, bridgeFor, installing
  -- Whether a click on Refused reads the instance again
  local retrying
  -- The status that the bar showed cut and then as a tip
  local tippedStatus
  -- What renderGlyphPreviews wrote since the pack was read, by font
  -- and code
  local rendered = {}

  -- Repaints the panel and the bar
  local function showProperties()
    dlg:repaint()
  end

  -- Sets the status. A status that the bar cuts shows whole as a tip
  -- once its paint has measured it.
  local function setStatus(text)
    status = text
    tippedStatus = nil
    showProperties()
  end

  -- Builds the tab again, defined below
  local rebuild

  -- The rows of the selected entry as the panel lays them out
  local function entryRows()
    local list = {}
    for i, line in ipairs(refused or {}) do
      table.insert(list, { text=i == 1 and tr("properties.refused") or nil, value=line })
    end
    local info = index and selected and index[selected]
    if info and definition and (info.kind == "definition" or info.kind == "item" and info.entry.id == definition.item) then
      for _, row in ipairs(modules.items.rows(definition, info.at or "", info.part or "item", tr)) do
        table.insert(list, row)
      end
      if info.kind == "item" then
        for _, row in ipairs(modules.drafts.rows(model, info, tr)) do
          table.insert(list, row)
        end
      end
      return list
    end
    for _, row in ipairs(properties.rows(info, tr, { coupled=preferences.coupled })) do
      if row.key == "coupled" then
        table.insert(list, { text=tr("properties.coupled"), value=row.value, field=row.field, kind="switch" })
      elseif row.key == "delete" then
        table.insert(list, { text=row.value, field=row.field, kind="button" })
      else
        table.insert(list, { text=tr("properties." .. row.key), value=row.value, field=row.field })
      end
    end
    for _, row in ipairs(modules.drafts.rows(model, info, tr)) do
      table.insert(list, row)
    end
    return list
  end

  -- The font and code that renderGlyphPreviews takes for info, or nil
  local function glyphKey(info)
    return info and info.font and info.entry and info.entry.code and info.font.font .. "/" .. info.entry.code or nil
  end

  -- The rows of the selected entry and below them its previews
  local function rows()
    local list = entryRows()
    if #list == 0 then
      list = { { value=tr("properties.none") } }
    end
    local info = index and selected and index[selected]
    local key = glyphKey(info)
    local previews, render = modules.minecraft.previews(info, key and rendered[key], definition, tr)
    local section = modules.minecraft.rows({ instance=instance, previews=previews, render=render }, tr, api.encode)
    table.move(section, 1, #section, #list + 1, list)
    return list
  end

  -- Draws the properties of the selected entry on the theme's face:
  -- keys in the color of its section labels and values below them, a
  -- value that a click edits in a sunken box. A panel taller than its
  -- canvas narrows for a scroll bar along its right edge.
  local function paintProperties(ev)
    local gc = ev.context
    local color = gc.theme.color
    gc.color = color.face
    gc:fillRect{ x=0, y=0, width=gc.width, height=gc.height }
    local function measure(text) return gc:measureText(text).width end
    local textHeight = gc:measureText("Ag").height
    lineHeight = textHeight + 2
    local width = gc.width - 2 * pad
    local list = rows()
    local size = gc.theme.dimension.mini_scrollbar_size
    drawn = modules.panel.layout(list, width, measure, lineHeight, textHeight)
    if drawn.height + 2 * pad > gc.height then
      drawn = modules.panel.layout(list, width - size, measure, lineHeight, textHeight)
    end
    scroll = math.max(0, math.min(scroll, drawn.height + 2 * pad - gc.height))
    local bar = modules.panel.scrollbar(drawn.height + 2 * pad, gc.height, scroll, size)
    if bar then
      local x = gc.width - size
      gc:drawThemeRect("mini_scrollbar_bg", { x=x, y=0, width=size, height=gc.height })
      gc:drawThemeRect("mini_scrollbar_thumb", { x=x, y=bar.thumb, width=size, height=bar.height })
    end
    for _, op in ipairs(drawn.ops) do
      local x, y = op.x + pad, op.y + pad - scroll
      if op.op == "text" then
        gc.color = op.key and color.separator_label or color.text
        gc:fillText(op.text, x, y)
      elseif op.op == "box" then
        gc:drawThemeRect("sunken_normal", { x=x, y=y, width=op.width, height=op.height })
      elseif op.op == "button" then
        gc:drawThemeRect("button_normal", { x=x, y=y, width=op.width, height=op.height })
      elseif op.op == "check" then
        gc:drawThemeImage(op.checked and "check_selected" or "check_normal", { x=x, y=y })
      end
    end
  end

  -- The colours of the bar's words, which the theme has none for.
  -- paintBar mixes off, the grey, from two thirds of the theme's text
  -- colour and one third of its face colour.
  local tones = {
    good=api.Color{ r=0x2e, g=0x8b, b=0x3c },
    wait=api.Color{ r=0xb0, g=0x78, b=0x00 },
    bad=api.Color{ r=0xc8, g=0x32, b=0x32 },
  }

  -- The height of the bar, which a canvas keeps from its creation. It
  -- starts as a guess for the theme's font, and a paint that finds it
  -- too low sets the height it measured and builds the tab again once.
  local barHeight, grown = 32, false

  -- Draws the Minecraft bar and the status on one line, centred on the
  -- canvas's height. A canvas too low for it builds the tab again once.
  local function paintBar(ev)
    local gc = ev.context
    local color = gc.theme.color
    gc.color = color.face
    gc:fillRect{ x=0, y=0, width=gc.width, height=gc.height }
    local function measure(text) return gc:measureText(text).width end
    local textHeight = gc:measureText("Ag").height
    local text, face = color.text, color.face
    tones.off = api.Color{ r=(2 * text.red + face.red) // 3, g=(2 * text.green + face.green) // 3,
                           b=(2 * text.blue + face.blue) // 3 }
    local items = modules.minecraft.bar({ instance=instance, liveSync=preferences.liveSync ~= false,
                                          bridge=retrying and "connecting" or bridge and bridge.state }, tr)
    if status then
      table.insert(items, { kind="text", text=status, color="status", shrink="end", least=12, divider=true })
    end
    barDrawn = modules.panel.bar(items, gc.width - 2 * pad, measure, textHeight + 2, textHeight)
    barDrawn.top = (gc.height - barDrawn.height) // 2
    if barDrawn.height + 2 * pad > gc.height and not grown then
      grown, barHeight = true, barDrawn.height + 2 * pad
      local timer
      timer = api.Timer{ interval=0, ontick=function()
        timer:stop()
        if not closed then rebuild() end
      end }
      timer:start()
    end
    for _, op in ipairs(barDrawn.ops) do
      local x, y = op.x + pad, op.y + barDrawn.top
      if op.op == "text" then
        gc.color = op.color == "status" and color.status_bar_text or tones[op.color] or color.text
        gc:fillText(op.text, x, y)
        if op.color == "status" and op.cut and tippedStatus ~= status then
          tippedStatus = status
          local timer
          timer = api.Timer{ interval=0, ontick=function()
            timer:stop()
            if status == tippedStatus then api.tip(status) end
          end }
          timer:start()
        end
      elseif op.op == "divider" then
        gc.color = tones.off
        gc:fillRect{ x=x, y=y, width=1, height=op.height }
      elseif op.op == "box" then
        gc:drawThemeRect("sunken_normal", { x=x, y=y, width=op.width, height=op.height })
      elseif op.op == "button" then
        gc:drawThemeRect("button_normal", { x=x, y=y, width=op.width, height=op.height })
      elseif op.op == "check" then
        gc:drawThemeImage(op.checked and "check_selected" or "check_normal", { x=x, y=y })
      end
    end
  end

  -- Draws what a callback changed. Aseprite hands the events of a
  -- Process or a WebSocket to Lua without a message of its own and
  -- draws nothing until the next one, which a timer's tick gives.
  local function redraw()
    local timer
    timer = api.Timer{ interval=0, ontick=function() timer:stop() end }
    timer:start()
  end

  -- Starts the next Gradle run that waits, unless one runs. A run that
  -- could not start calls done at once, and the next one starts.
  local function pump()
    if running or closed or #queue == 0 then
      return
    end
    local job = table.remove(queue, 1)
    local this = {}
    running = this
    this.process = job(function()
      redraw()
      if running == this then
        running = nil
        pump()
      end
    end)
  end

  -- The nodes of the definition read last, below its item's node, and
  -- their entries in the index. nil when the item has no node.
  local function attach()
    local id = definition and "item/" .. definition.item
    if not (index and index[id]) then
      return nil
    end
    local children, nested = modules.items.nodes(definition, tr)
    for key, info in pairs(nested) do index[key] = info end
    return children, id
  end

  -- The node of id among nodes
  local function findNode(list, id)
    for _, node in ipairs(list or {}) do
      if node.id == id then return node end
      local found = findNode(node.children, id)
      if found then return found end
    end
    return nil
  end

  -- Reads the pack again and shows its nodes
  local function describe(done)
    local process, problem = modules.pack.describe(context.bootstrap, api, function(read, err)
      if closed then
        return
      end
      if read then
        local ok, built, builtIndex = pcall(modules.tree.build, read, tr, modules.drafts.marks(read))
        if ok then
          nodes, index, model = built, builtIndex, read
          rendered = {}
          local children, id = attach()
          if children then
            local node = findNode(nodes, id)
            node.children, node.expanded = children, true
          end
          lists = { statuses=read.statuses, scalings=read.scalings }
          dlg:modify{ id="tree", nodes=nodes }
          if selected and not index[selected] then
            ctl.clear()
          end
          setStatus(nil)
          local again = reopen
          reopen = nil
          if again and index[again] then
            dlg.data = { tree=again }
            ctl.select(again)
          end
          if notice then setStatus(notice) end
          notice = nil
          showProperties()
          if not synced then
            synced = true
            ctl.readInstance()
            sync()
          end
          done()
          return
        end
        err = { tostring(built) }
      end
      -- The bar shows the status on one line
      setStatus(tr("status.failed") .. " " .. table.concat(err, " "))
      done()
    end)
    if not process then
      setStatus(tr("status.not_started") .. " " .. problem)
      done()
    end
    return process
  end

  function ctl.reload()
    for i = #queue, 1, -1 do
      if queue[i] == describe then table.remove(queue, i) end
    end
    table.insert(queue, describe)
    if not running then
      setStatus(tr("status.loading"))
    end
    pump()
  end

  -- Queues start(finish), which starts a Gradle run with a function
  -- of pack and returns what it returns. finish(...) gets the run's
  -- result unless the tab closed meanwhile, and failed(problem) the
  -- reason when no run started.
  local function enqueue(start, finish, failed)
    table.insert(queue, function(done)
      local process, problem = start(function(...)
        local ok, raised = true, nil
        if not closed then ok, raised = pcall(finish, ...) end
        done()
        if not ok then error(raised, 0) end
      end)
      if not process then
        setStatus(tr("status.not_started") .. " " .. problem)
        failed(problem)
        done()
      end
      return process
    end)
    pump()
  end

  -- The start of a run of job, a { task, options }, for enqueue
  local function task(job)
    return function(finish)
      local process, problem = modules.pack.run(context.bootstrap, api, job[1], job[2], finish)
      return process, problem
    end
  end

  -- Closes sprite once a write changed its file, after it leaves the
  -- editor if shown is true, and returns shown
  local function drop(sprite, shown)
    if shown then dlg:modify{ id="editor", sprite=false } end
    if sprite then sprite:close() end
    return shown
  end

  -- Runs job, a { task, options }, then after() once it succeeded and
  -- finally() in any case, and reads the pack again. A refusal shows
  -- its reason in the panel while its entry stays selected, and in the
  -- status line too when shown is true. A refusal that names fields
  -- the task needs calls needed(fields) instead. The status line shows
  -- the notices that the task printed once the pack was read again.
  local function write(job, after, finally, shown, needed)
    local id = selected
    refused = nil
    setStatus(tr("status.writing"))
    enqueue(task(job), function(ok, err, out)
      if ok then
        local texts, unplayed = {}, {}
        for _, n in ipairs(modules.pack.notices(out or {})) do
          if n.frame then table.insert(unplayed, n.frame) else table.insert(texts, tr("status." .. n.key)) end
        end
        if #unplayed > 0 then
          table.insert(texts, tr(#unplayed == 1 and "status.unplayed" or "status.unplayed_many", table.concat(unplayed, ", ")))
        end
        if #texts > 0 then notice = table.concat(texts, " ") end
        if after then after() end
        if finally then finally() end
        ctl.reload()
        return
      end
      -- No read follows that would show a notice of an earlier write
      notice = nil
      for i = #queue, 1, -1 do
        if queue[i] == describe then table.remove(queue, i) end
      end
      local needs = needed and modules.pack.needs(out or {}) or {}
      if #needs > 0 then
        setStatus(nil)
        if finally then finally() end
        needed(needs)
      else
        local reason = modules.pack.refusal(err)
        setStatus(shown and tr("status.refused") .. " " .. table.concat(reason, " ") or nil)
        if selected == id then
          refused = reason
        end
        if finally then finally() end
        showProperties()
      end
    end, function()
      if finally then finally() end
    end)
  end

  -- Reads the definition of the item id and shows its nodes below the
  -- item's, then calls after() once it was read
  local function readDefinition(id, after)
    enqueue(function(finish)
      local process, problem = modules.pack.item(context.bootstrap, api, id, finish)
      return process, problem
    end, function(read, err)
      -- A read for an item that is no longer selected is dropped
      local node = "item/" .. id
      if selected ~= node and (selected or ""):sub(1, #node + 1) ~= node .. "#" then return end
      if not read then
        refused = modules.pack.refusal(err)
        showProperties()
        return
      end
      local function show(item, children)
        local shown = findNode(nodes, item)
        if shown then shown.children, shown.expanded = children, true end
        dlg:modifyNode{ id="tree", node=item, children=children or {}, expanded=children and true or nil }
      end
      local old = definition and definition.item ~= id and index and index["item/" .. definition.item]
      if old then
        show("item/" .. definition.item, nil)
      end
      definition = read
      local children, item = attach()
      if children then
        show(item, children)
      end
      showProperties()
      if after then after() end
    end, function() end)
  end

  -- Shows what describeInstance printed, and connects to the bridge
  -- mod of an instance with Fabric whose settings it read
  local function showInstance(read)
    instance = read
    local wanted = read.instance and read.fabric and read.bridge and read.port and read.token
      and read.port .. " " .. read.token or nil
    if wanted ~= bridgeFor then
      if bridge then bridge.close() end
      bridge, bridgeFor = nil, wanted
      if wanted then
        bridge = modules.minecraft.bridge{ WebSocket=api.WebSocket, types=api.messages, encode=api.encode,
          decode=function(text) return modules.pack.object(api, text) end, port=read.port, token=read.token,
          changed=function() showProperties() redraw() end }
      end
    end
    showProperties()
  end

  -- Reads the chosen Minecraft instance, then calls after(read) with
  -- whether it was read
  function ctl.readInstance(after)
    enqueue(function(finish)
      local process, problem = modules.pack.printed(context.bootstrap, api, "describeInstance", {}, finish)
      return process, problem
    end, function(read, err)
      if read then
        showInstance(read)
      else
        -- An instance that could not be read can still be chosen
        instance = instance or {}
        setStatus(tr("minecraft.failed") .. " " .. table.concat(modules.pack.refusal(err), " "))
      end
      if after then after(read ~= nil) end
    end, function()
      if after then after(false) end
    end)
  end

  -- Runs job, a { task, options }, for the instance with status in the
  -- status line, and after() once it succeeded
  local function instanceTask(job, status, after)
    setStatus(status)
    enqueue(task(job), function(ok, err, out)
      if not ok then
        setStatus(tr("status.refused") .. " " .. table.concat(modules.pack.refusal(err), " "))
        return
      end
      setStatus(nil)
      after(out)
    end, function() end)
  end

  -- Installs the pack into the instance and reloads it in a connected
  -- client. A save while an install waits adds none.
  local function installPack()
    if installing then
      return
    end
    installing = true
    enqueue(function(finish)
      installing = nil
      setStatus(tr("minecraft.installing"))
      local process, problem = task({ "installPack", {} })(finish)
      return process, problem
    end, function(ok, err)
      if not ok then
        setStatus(tr("status.refused") .. " " .. table.concat(modules.pack.refusal(err), " "))
      elseif bridge and bridge.state == "connected" then
        bridge.send({ op="reload" }, function(reloaded, problem)
          setStatus(reloaded and tr("minecraft.installed") or tr("minecraft.not_reloaded") .. " " .. tostring(problem or ""))
        end)
      else
        setStatus(tr("minecraft.installed_f3t"))
      end
    end, function() installing = nil end)
  end

  -- Sends the requests of a preview to the connected client
  local function preview(requests)
    if not (bridge and bridge.state == "connected") then
      setStatus(tr("minecraft.not_connected"))
      return
    end
    for _, request in ipairs(requests) do
      bridge.send(request, function(ok, problem)
        setStatus(ok and tr("minecraft.shown") or tr("minecraft.not_shown") .. " " .. tostring(problem or ""))
      end)
    end
  end

  -- Renders the previews of the selected glyph
  local function render()
    local info = index and selected and index[selected]
    local key = glyphKey(info)
    if not key then
      return
    end
    setStatus(tr("minecraft.rendering"))
    enqueue(function(finish)
      local process, problem = modules.pack.file(context.bootstrap, api, ":pack-rendering:renderGlyphPreviews",
        { font=info.font.font, code=info.entry.code }, finish)
      return process, problem
    end, function(read, err)
      if not read then
        setStatus(tr("status.refused") .. " " .. table.concat(modules.pack.refusal(err), " "))
        return
      end
      setStatus(nil)
      rendered[key] = read
      showProperties()
    end, function() end)
  end

  -- Takes the action of an item of the Minecraft bar or of a preview in
  -- the panel
  local function minecraftAction(field)
    local action = field.minecraft
    if action == "live" then
      preferences.liveSync = preferences.liveSync == false
      showProperties()
    elseif action == "copy" then
      api.setClipboard(field.text)
      setStatus(tr("minecraft.copied"))
    elseif action == "preview" then
      preview(field.requests)
    elseif action == "render" then
      render()
    elseif action == "retry" then
      -- The bar shows Connecting while the read runs, and the refused
      -- bridge again when it fails
      if retrying then
        return
      end
      local before = bridgeFor
      retrying, bridgeFor = true, false
      setStatus(tr("minecraft.checking"))
      ctl.readInstance(function(read)
        retrying = nil
        if read then
          if status == tr("minecraft.checking") then setStatus(nil) end
        else
          bridgeFor = before
        end
        showProperties()
      end)
    elseif action == "pack" then
      installPack()
    elseif action == "bridge" then
      instanceTask({ "installBridgeMod", { gh=context.bootstrap.gh } }, tr("minecraft.installing_bridge"), function()
        ctl.readInstance()
        table.insert(queue, function(done)
          setStatus(tr("minecraft.bridge_installed"))
          done()
        end)
        pump()
      end)
    elseif action == "choose" then
      local folder = api.chooseFolder{ title=tr("minecraft.choose"), path=instance and instance.instance or nil }
      if folder then
        instanceTask({ "chooseMinecraftInstance", { minecraftInstance=folder } }, tr("minecraft.checking"),
          function() ctl.readInstance() end)
      end
    end
  end

  -- Runs the task that a save of the sprite of record implies: a
  -- glyph's display height for a new pixel height, an override's
  -- animation. The model takes the new size and animation only once
  -- the task succeeded. A save while the task waits or runs is taken
  -- up after it, so no task runs twice on the same model.
  local function follow(sprite, record)
    local info = record.info or index and index[record.id]
    if not (info and info.texture) then
      return
    end
    if record.writing then
      record.again = true
      return
    end
    local durations = {}
    for i, frame in ipairs(sprite.frames) do durations[i] = math.floor(frame.duration * 1000 + 0.5) end
    local job = properties.saved(info, { width=sprite.width, height=sprite.height, durations=durations,
      opened=record.frames }, preferences.coupled)
    if not job then
      info.texture.width, info.texture.height = sprite.width, sprite.height
      return
    end
    local width, height = sprite.width, sprite.height
    record.writing = true
    write(job, function()
      info.texture.width, info.texture.height = width, height
      if properties.animated(info) then
        info.entry.animation = #durations > 1 or nil
        info.entry.frames = nil
        record.frames = #durations
      end
    end, function()
      record.writing = nil
      if record.again then
        record.again = nil
        follow(sprite, record)
      end
    end, true)
  end

  -- Saves a sprite the tab opened as its source and its PNG, and runs
  -- what follows from it
  local function save(sprite, record)
    local info = record.info or index and index[record.id]
    local texture = info and info.texture or record.texture
    local column = info and properties.animated(info)
    local ok, err = pcall(modules.textures.save, sprite, texture, record.paths, api, column)
    if not ok then
      setStatus(tr("status.save_failed") .. " " .. tostring(err))
      return
    end
    setStatus(nil)
    follow(sprite, record)
    if preferences.liveSync ~= false and instance and instance.instance then
      installPack()
    end
    showProperties()
  end

  -- Asks for the values of a form { title, widgets }, nil when cancelled
  local function ask(form)
    local d = api.Dialog{ title=form.title }
    for _, w in ipairs(form.widgets) do
      local args = {}
      for k, v in pairs(w) do
        if k ~= "kind" then args[k] = v end
      end
      d[w.kind](d, args)
    end
    d:button{ id="ok", text=tr("edit.ok"), focus=true }
    d:button{ id="cancel", text=tr("edit.cancel") }
    d:show()
    local data = d.data
    return data.ok and data or nil
  end

  -- The texture entry e of a definition's model as the info that a
  -- save of it follows up
  local function definitionTexture(e)
    return { kind="texture", entry=e, texture=e.texture }
  end

  -- Takes the action of a row of an item definition: opens a texture,
  -- or asks for what the action needs and runs its task. A task that
  -- names fields it still needs asks for them and runs again. After it
  -- the item's definition is read again and the node stays selected
  -- while it is there.
  local function editDefinition(action, data)
    if action.op == "texture" then
      open(selected, action.entry.texture, action.entry.frames, definitionTexture(action.entry))
      return
    end
    data = data or {}
    while true do
      local form = modules.items.form(definition, action, data, tr)
      if not form then break end
      local answer = ask(form)
      if not answer then return end
      for key, value in pairs(answer) do data[key] = value end
    end
    local job = modules.items.job(definition, action, data, api.decode)
    if not job then
      return
    end
    local id, item = selected, definition.item
    write(job, function()
      readDefinition(item, function()
        if selected == id and index[id] then
          dlg.data = { tree=id }
          ctl.select(id)
        end
      end)
    end, nil, nil, function(needs)
      modules.items.needs(data, needs)
      editDefinition(action, data)
    end)
  end

  -- Shows text in the status line once the pack was read again
  local function reloadThen(text)
    ctl.reload()
    table.insert(queue, function(done)
      setStatus(text)
      done()
    end)
    pump()
  end

  -- Runs discardDraft for each of ids. The selected entry's sprite
  -- closes once its draft is discarded.
  local function discard(ids)
    local shown = index and selected and modules.drafts.id(index[selected])
    for _, id in ipairs(ids) do
      local sprite = dlg.data.editor
      write({ "discardDraft", { draft=id } }, function()
        if id ~= shown then return end
        if drop(sprite, selected and index[selected] and modules.drafts.id(index[selected]) == id) then
          ctl.clear()
        end
      end, nil, true)
    end
  end

  -- The image of the PNG at path, or nil where there is none
  local function image(path)
    return api.isFile(path) and api.Image{ fromFile=path } or nil
  end

  -- Writes main's version of the files of draft's conflict, asks how to
  -- resolve it beside both versions of its texture, and resolves it.
  -- The texture closes when main's version replaces it, and opens again
  -- once the pack was read.
  local function resolve(draft)
    local id = selected
    enqueue(task({ "readConflict", { draft=draft.id } }), function(ok, err)
      if not ok then
        refused = modules.pack.refusal(err)
        showProperties()
        return
      end
      local png
      for _, file in ipairs(draft.conflict.files) do
        if file:match("%.png$") then png = file end
      end
      local answer = modules.conflicts.ask{ Dialog=api.Dialog, tr=tr, Color=api.Color, draft=draft,
        clone=png and image(api.join(clone, "resource-pack", png)),
        main=png and image(api.join(clone, "resource-pack", "build", "conflict", png)) }
      if not answer then
        return
      end
      local sprite = selected == id and dlg.data.editor or nil
      write({ "resolveConflict", { draft=draft.id, keep=answer.keep,
                                   fields=next(answer.fields) and api.encode(answer.fields) or nil } }, function()
        if answer.keep ~= "main" then return end
        notice = tr("status.yours", api.join(clone, "resource-pack", "build", "conflict", "yours"))
        if drop(sprite, selected == id) then reopen = id end
      end, nil, true)
    end, function() end)
  end

  -- Runs .githooks/commit-msg on title through git, as a commit would,
  -- and returns true, or false and the lines of its refusal
  local function checkTitle(title)
    local file = api.join(clone, "resource-pack", "build", "publish-message.txt")
    api.makeAllDirectories(api.filePath(file))
    api.write(file, title .. "\n")
    local err = {}
    local process = api.Process{ cmd={ context.bootstrap.git or "git", "hook", "run", "commit-msg", "--", file },
                                 cwd=clone, env=context.bootstrap.env,
                                 onstderr=function(line) table.insert(err, line) end }
    local started, problem = pcall(process.start, process)
    if not started then
      return false, { tostring(problem) }
    end
    local code = process:wait()
    return code == 0, err
  end

  -- Asks which drafts to publish or discard and runs the tasks for the
  -- answer. publishDrafts runs the pack's check before it sends
  -- anything.
  function ctl.publish()
    if running or #queue > 0 then
      setStatus(tr("status.busy"))
      return
    end
    local list = model and model.drafts or {}
    if #list == 0 then
      api.alert{ title=tr("publish.title"), text=tr("publish.no_drafts") }
      return
    end
    local answer = modules.drafts.ask{ Dialog=api.Dialog, tr=tr, list=list, check=checkTitle, confirm=function(text)
      return api.alert{ title=tr("publish.discard"), text=text, buttons={ tr("properties.discard"), tr("edit.cancel") } } == 1
    end, labels=labels, Rectangle=api.Rectangle, screen=api.screen }
    if not answer then
      return
    end
    if answer.discard then
      discard(answer.discard)
      return
    end
    setStatus(tr("status.publishing"))
    enqueue(task({ "publishDrafts", { drafts=table.concat(answer.publish, ","), title=answer.title, gh=context.bootstrap.gh } }),
      function(ok, err, out)
        if ok then
          reloadThen(tr("status.published", out[#out] or ""))
        else
          -- A publish that failed may still have moved code points
          reloadThen(tr("status.refused") .. " " .. table.concat(modules.pack.refusal(err), " "))
        end
      end, function() end)
  end

  -- Runs syncReviews while the pack has drafts or pull requests. The
  -- tab reads the pack again when main moved or a pull request changed,
  -- and says which one changed.
  function sync()
    if syncing or not (model and (#(model.reviews or {}) > 0 or #(model.drafts or {}) > 0)) then
      return
    end
    syncing = true
    enqueue(task({ "syncReviews", { gh=context.bootstrap.gh } }), function(ok, err, out)
      syncing = nil
      if not ok then
        setStatus(tr("status.sync_failed") .. " " .. table.concat(modules.pack.refusal(err), " "))
        return
      end
      -- What changed the pack, and what only the status says
      local ended, unanswered, moved = {}, {}, false
      for _, line in ipairs(out) do
        local state, number = line:match("^(%a+) (%d+)$")
        if state then
          table.insert(state == "unanswered" and unanswered or ended, tr("status." .. state, number))
        end
        if line == "restored" then table.insert(ended, tr("status.restored")) end
        moved = moved or line == "moved"
      end
      table.move(unanswered, 1, #unanswered, #ended + 1, ended)
      local text = table.concat(ended, " ")
      if #ended > #unanswered or (moved and #ended > 0) then
        reloadThen(text)
      elseif moved then
        ctl.reload()
      elseif #ended > 0 then
        setStatus(text)
      end
    end, function() syncing = nil end)
  end

  -- Edits field of the selected entry, or takes an action of the
  -- Minecraft bar or of a preview, which needs no entry and queues its
  -- tasks
  function ctl.edit(field)
    if type(field) == "table" and field.minecraft then
      minecraftAction(field)
      return
    end
    local info = index and selected and index[selected]
    if not info then
      return
    end
    if running or #queue > 0 then
      setStatus(tr("status.busy"))
      return
    end
    if type(field) == "table" then
      editDefinition(field)
      return
    end
    if field == "coupled" then
      preferences.coupled = preferences.coupled == false
      showProperties()
      return
    end
    if field == "discard" then
      local id = modules.drafts.id(info)
      local draft = modules.drafts.find(model, id)
      local yes = api.alert{ title=tr("properties.discard"), text=tr("properties.discard_confirm", draft and draft.label or id),
                             buttons={ tr("properties.discard"), tr("edit.cancel") } }
      if yes == 1 then
        discard({ id })
      end
      return
    end
    if field == "resolve" then
      resolve(modules.drafts.find(model, modules.drafts.id(info)))
      return
    end
    if field == "github" then
      local review = modules.drafts.reviewOf(model, info)
      if review and review.url:sub(1, #"https://github.com/") == "https://github.com/" then
        api.launch(review.url)
      end
      return
    end
    if field == "delete" then
      local yes = api.alert{ title=tr("properties.delete"), text=tr("properties.delete_confirm", properties.label(info)),
                             buttons={ tr("properties.delete"), tr("edit.cancel") } }
      if yes ~= 1 then
        return
      end
      local id, sprite = selected, dlg.data.editor
      write(properties.jobs(info, field, {}, tr), function()
        if drop(sprite, selected == id) then ctl.clear() end
      end)
      return
    end
    local data = ask(properties.form(info, field, tr, lists or {}))
    if not data then
      return
    end
    local sprite = dlg.data.editor
    local job = properties.jobs(info, field, data, tr, sprite)
    if job and job.resize then
      local size = job.resize
      local record = sprite and opened[sprite.filename]
      if not record then
        return
      end
      if size.lossy then
        local yes = api.alert{ title=tr("properties.size"),
                               text={ tr("properties.resize_confirm", sprite.width, sprite.height, size.width, size.height),
                                      tr("properties.resize_ratio"), tr("properties.resize_lossy") },
                               buttons={ tr("properties.resize"), tr("edit.cancel") } }
        if yes ~= 1 then
          return
        end
      end
      sprite:resize(size.width, size.height)
      save(sprite, record)
      return
    end
    if job then
      write(job)
    end
  end

  local function click(ev)
    local field = drawn and modules.panel.hit(drawn, ev.x - pad, ev.y - pad + scroll)
    if field then
      ctl.edit(field)
    end
  end

  local function clickBar(ev)
    local field = barDrawn and modules.panel.hit(barDrawn, ev.x - pad, ev.y - barDrawn.top)
    if field then
      ctl.edit(field)
    end
  end

  -- Shows the tip of what the pointer moved onto in the bar, once. A
  -- canvas tells no leave, so a move over the properties panel forgets
  -- the item last tipped.
  local tipped
  local function hover(ev)
    local tip = barDrawn and modules.panel.hit(barDrawn, ev.x - pad, ev.y - barDrawn.top, "tip")
    if tip ~= tipped then
      tipped = tip
      if tip then api.tip(tip) end
    end
  end

  -- Scrolls the panel three lines a step. The next paint keeps it
  -- between its first and its last row.
  local function wheel(ev)
    scroll = math.max(0, scroll + ev.deltaY * 3 * lineHeight)
    showProperties()
  end

  local function build(sprite)
    dlg = api.Dialog{ title=tr("tab.title"), tab=true, closable=false }
    dlg:canvas{ id="bar", height=barHeight, hexpand=true, vexpand=false, onpaint=paintBar, onmousedown=clickBar, onmousemove=hover }
    dlg:newrow()
    local own = dlg
    dlg:editor{ id="editor", sprite=sprite, onchange=function()
      -- The sprite left without the tab: it was closed, or embedded
      -- elsewhere. A dialog that rebuild replaced has nothing to clear.
      if own == dlg then ctl.clear() end
    end }
    -- The widgets from the panel on go into it, one below the other
    dlg:panel{ id=sidePanel, side=preferences.treeSide == "right" and "right" or "left", width=sideWidth }
    dlg:tree{ id="tree", nodes=nodes or {}, onchange=function(ev) ctl.select(ev.node) end }
    dlg:splitter{}
    dlg:canvas{ id="properties", onpaint=paintProperties, onmousedown=click, onwheel=wheel,
                onmousemove=function() tipped = nil end }
    if selected then
      dlg.data = { tree=selected }
    end
    showProperties()
    dlg:show()
  end

  -- Opens texture in the editor for the node id. frames are the
  -- durations of an animated texture's frames, and info is what a save
  -- of it follows up when the node's own info is not.
  function open(id, texture, frames, info)
    local paths, outside = modules.pack.paths(clone, texture, api.join)
    local sprite, missing
    if paths then
      sprite, missing = modules.textures.open(texture, paths, api, frames)
    end
    if not sprite then
      -- The editor shows no texture of another entry than the selected one
      dlg:modify{ id="editor", sprite=false }
      setStatus(paths and tr("status.missing", missing) or tr("status.outside", outside))
      return
    end
    local record = { id=id, texture=texture, paths=paths, info=info, frames=frames and #frames or nil }
    opened[paths.png], opened[paths.aseprite] = record, record
    dlg:modify{ id="editor", sprite=sprite }
    -- A texture of a few pixels fills the editor
    api.fitScreen()
  end

  -- Shows the entry of a node and opens its texture in the editor. An
  -- item reads its definition, and a node of it opens the first
  -- texture of its model that the pack holds.
  function ctl.select(id)
    selected = id
    refused = nil
    scroll = 0
    setStatus(nil)
    showProperties()
    local info = index and index[id]
    if info and info.kind == "item" then
      readDefinition(info.entry.id)
    end
    local texture, followed = info and info.texture, info
    if info and info.kind == "definition" then
      local e = modules.items.textures(definition, info.at, info.part)[1]
      texture, followed = e and e.texture, e and definitionTexture(e)
    end
    if texture then
      open(id, texture, properties.animated(followed) and followed.entry.frames or nil, followed ~= info and followed or nil)
    else
      dlg:modify{ id="editor", sprite=false }
    end
  end

  -- Opens the wizard for a new texture. Its tasks run in the queue,
  -- and what it created opens once the pack was read again.
  function ctl.create()
    modules.wizard.open{
      api=api,
      tr=tr,
      model=model or {},
      labels=labels,
      run=function(job, done)
        setStatus(tr("status.writing"))
        enqueue(task(job), function(ok, err, out)
          setStatus(nil)
          done(ok, ok and {} or modules.pack.refusal(err), out)
        end, function(problem)
          done(false, { problem }, {})
        end)
      end,
      listing=function(done)
        if vanilla then
          done(vanilla)
          return
        end
        setStatus(tr("status.reading_jar"))
        enqueue(function(finish)
          local process, problem = modules.pack.vanilla(context.bootstrap, api, finish)
          return process, problem
        end, function(listing, err)
          setStatus(nil)
          vanilla = listing
          done(listing, listing == nil and modules.pack.refusal(err) or nil)
        end, function(problem)
          done(nil, { problem })
        end)
      end,
      created=function(job)
        ctl.reload()
        table.insert(queue, function(done)
          local id = modules.wizard.target(index, job)
          if id then
            dlg.data = { tree=id }
            ctl.select(id)
          end
          done()
        end)
        pump()
      end,
    }
  end

  -- Selects no entry, so that a click on the last one opens it again.
  -- An ID that no node has selects none.
  function ctl.clear()
    selected = nil
    refused = nil
    dlg.data = { tree="" }
    showProperties()
  end

  -- Moves the side panel to "left" or "right" of the window
  function ctl.setSide(side)
    preferences.treeSide = side
    dlg:modify{ id=sidePanel, side=side }
  end

  function rebuild()
    local sprite = dlg.data.editor
    dlg:close()
    build(sprite)
    if sprite then
      api.fitScreen()
    end
  end

  -- Saves a sprite the tab opened as its source and its PNG instead
  -- of Aseprite's own save
  function ctl.onBeforeCommand(ev)
    if ev.name ~= "SaveFile" then
      return
    end
    local sprite = api.activeSprite()
    local record = sprite and opened[sprite.filename]
    if not record then
      return
    end
    ev.stopPropagation()
    save(sprite, record)
  end

  function ctl.close()
    closed = true
    every:stop()
    queue = {}
    local process = running and running.process
    if process and process.isRunning then
      process:stop()
    end
    running = nil
    if bridge then bridge.close() end
    dlg:close()
  end

  build(nil)
  ctl.reload()
  every = api.Timer{ interval=300, ontick=sync }
  every:start()
  return ctl
end

return tab
