-- The Nordtal tab: the pack tree, the embedded editor and the
-- properties of the selected entry, side by side. The properties
-- stay beside the editor, on the side away from the tree.
--
-- tab.open{ api, modules, bootstrap, preferences, tr } builds the tab
-- and reads the pack. api holds the Aseprite functions that pack and
-- textures name, plus Dialog, Rectangle, screen, alert and activeSprite.
-- modules holds pack, tree, textures, panel, properties and wizard. preferences.treeSide is
-- "left" or "right", and preferences.coupled is false once the
-- designer decoupled pixel size and display height.
--
-- A click on a value of the panel asks for a new one in a form, and
-- OK runs the task that writes it. The tasks run one at a time, and
-- after them the tab reads the pack again. ctl.create() opens the
-- wizard for a new texture, which opens in the editor once created.

local tab = {}

-- The widths of the tree and the properties panel, in points. The
-- editor takes the rest, and a longer node of the tree scrolls.
local treeWidth = 150
local panelWidth = 120

function tab.open(context)
  local api, modules, tr = context.api, context.modules, context.tr
  local preferences, clone = context.preferences, context.bootstrap.clone
  local properties = modules.properties
  local ctl = {}
  local dlg, nodes, index, lists, selected, closed, model
  -- The textures of the client jar, read once
  local vanilla
  local status = { text=tr("status.loading"), visible=true }
  -- What the tab opened, by the filenames of its PNG and its source:
  -- { id, paths } with the id of the entry's node
  local opened = {}
  -- The Gradle runs that wait, each a function(done) that starts one
  -- and returns its Process, and the one that runs as { process }
  local queue, running = {}, nil
  -- The reason why the last task for the selected entry was refused
  local refused
  -- Where the panel last drew its fields, its margin, how far the
  -- wheel scrolled it and the height of a line
  local drawn
  local pad = 4
  local scroll, lineHeight = 0, 0

  local function setStatus(text)
    status = { text=text or "", visible=text ~= nil }
    dlg:modify{ id="status", text=status.text, visible=status.visible }
  end

  local function showProperties()
    dlg:repaint()
  end

  -- The rows of the selected entry as the panel lays them out
  local function rows()
    local list = {}
    for i, line in ipairs(refused or {}) do
      table.insert(list, { text=i == 1 and tr("properties.refused") or nil, value=line })
    end
    local info = index and selected and index[selected]
    for _, row in ipairs(properties.rows(info, tr, { coupled=preferences.coupled })) do
      if row.key == "coupled" then
        table.insert(list, { text=tr("properties.coupled"), value=row.value, field=row.field, kind="switch" })
      elseif row.key == "delete" then
        table.insert(list, { text=row.value, field=row.field, kind="button" })
      else
        table.insert(list, { text=tr("properties." .. row.key), value=row.value, field=row.field })
      end
    end
    return list
  end

  -- Draws the properties of the selected entry on the theme's face:
  -- keys in the color of its section labels and values below them, a
  -- value that a click edits in a sunken box
  local function paintProperties(ev)
    local gc = ev.context
    local color = gc.theme.color
    gc.color = color.face
    gc:fillRect{ x=0, y=0, width=gc.width, height=gc.height }
    local function measure(text) return gc:measureText(text).width end
    lineHeight = gc:measureText("Ag").height + 2
    local width = gc.width - 2 * pad
    local list = rows()
    if #list == 0 then
      list = { { value=tr("properties.none") } }
    end
    drawn = modules.panel.layout(list, width, measure, lineHeight)
    scroll = math.max(0, math.min(scroll, drawn.height + 2 * pad - gc.height))
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

  -- Draws what a callback changed. Aseprite hands a Process's events
  -- to Lua without a message of its own and draws nothing until the
  -- next one, which a timer's tick gives.
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

  -- Reads the pack again and shows its nodes
  local function describe(done)
    local process, problem = modules.pack.describe(context.bootstrap, api, function(read, err)
      if closed then
        return
      end
      if read then
        local ok, built, builtIndex = pcall(modules.tree.build, read, tr)
        if ok then
          nodes, index, model = built, builtIndex, read
          lists = { statuses=read.statuses, scalings=read.scalings }
          dlg:modify{ id="tree", nodes=nodes }
          if selected and not index[selected] then
            ctl.clear()
          end
          setStatus(nil)
          showProperties()
          done()
          return
        end
        err = { tostring(built) }
      end
      -- The status label draws one line
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
        if not closed then finish(...) end
        done()
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

  -- Runs job, a { task, options }, then after() once it succeeded and
  -- finally() in any case, and reads the pack again. A refusal shows
  -- its reason in the panel while its entry stays selected, and in the
  -- status line too when shown is true.
  local function write(job, after, finally, shown)
    local id = selected
    refused = nil
    setStatus(tr("status.writing"))
    enqueue(task(job), function(ok, err)
      if ok then
        if after then after() end
        if finally then finally() end
        ctl.reload()
      else
        for i = #queue, 1, -1 do
          if queue[i] == describe then table.remove(queue, i) end
        end
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

  -- Runs the task that a save of the sprite of record implies: a
  -- glyph's display height for a new pixel height, an override's
  -- animation. The model takes the new size and animation only once
  -- the task succeeded. A save while the task waits or runs is taken
  -- up after it, so no task runs twice on the same model.
  local function follow(sprite, record)
    local info = index and index[record.id]
    if not info then
      return
    end
    if record.writing then
      record.again = true
      return
    end
    local durations = {}
    for i, frame in ipairs(sprite.frames) do durations[i] = math.floor(frame.duration * 1000 + 0.5) end
    local job = properties.saved(info, { width=sprite.width, height=sprite.height, durations=durations },
      preferences.coupled)
    if not job then
      info.texture.width, info.texture.height = sprite.width, sprite.height
      return
    end
    local width, height = sprite.width, sprite.height
    record.writing = true
    write(job, function()
      info.texture.width, info.texture.height = width, height
      if info.kind == "override" then
        info.entry.animation = #durations > 1 or nil
        info.entry.frames = nil
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
    local info = index and index[record.id]
    local texture = info and info.texture or record.texture
    local override = info and info.kind == "override"
    local ok, err = pcall(modules.textures.save, sprite, texture, record.paths, api, override)
    if not ok then
      setStatus(tr("status.save_failed") .. " " .. tostring(err))
      return
    end
    setStatus(nil)
    follow(sprite, record)
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

  -- Edits field of the selected entry
  function ctl.edit(field)
    local info = index and selected and index[selected]
    if not info then
      return
    end
    if running or #queue > 0 then
      setStatus(tr("status.busy"))
      return
    end
    if field == "coupled" then
      preferences.coupled = preferences.coupled == false
      showProperties()
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
        if selected == id then
          dlg:modify{ id="editor", sprite=false }
          ctl.clear()
        end
        if sprite then sprite:close() end
      end)
      return
    end
    local data = ask(properties.form(info, field, tr, lists or {}))
    if not data then
      return
    end
    local job = properties.jobs(info, field, data, tr)
    if job and job.resize then
      local size = job.resize
      local sprite = dlg.data.editor
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

  -- Scrolls the panel three lines a step. The next paint keeps it
  -- between its first and its last row.
  local function wheel(ev)
    scroll = math.max(0, scroll + ev.deltaY * 3 * lineHeight)
    showProperties()
  end

  local function build(sprite)
    dlg = api.Dialog{ title=tr("tab.title"), tab=true, closable=false }
    local function addTree()
      dlg:tree{ id="tree", nodes=nodes or {}, width=treeWidth, hexpand=false,
                onchange=function(ev) ctl.select(ev.node) end }
    end
    local own = dlg
    local function addEditor()
      dlg:editor{ id="editor", sprite=sprite, onchange=function()
        -- The sprite left without the tab: it was closed, or embedded
        -- elsewhere. A dialog that setSide replaced has nothing to clear.
        if own == dlg then ctl.clear() end
      end }
    end
    local function addPanel()
      dlg:canvas{ id="properties", width=panelWidth, hexpand=false, onpaint=paintProperties, onmousedown=click,
                  onwheel=wheel }
    end
    local order = { addTree, addEditor, addPanel }
    if preferences.treeSide == "right" then
      order = { addPanel, addEditor, addTree }
    end
    for i, add in ipairs(order) do
      if i > 1 then dlg:samerow() end
      add()
    end
    dlg:newrow()
    dlg:label{ id="status", text=status.text, visible=status.visible }
    if selected then
      dlg.data = { tree=selected }
    end
    showProperties()
    dlg:show()
  end

  -- Shows the entry of a node and opens its texture in the editor
  function ctl.select(id)
    selected = id
    refused = nil
    scroll = 0
    setStatus(nil)
    showProperties()
    local info = index and index[id]
    if not (info and info.texture) then
      return
    end
    local paths, outside = modules.pack.paths(clone, info.texture, api.join)
    local sprite, missing
    if paths then
      local frames = info.kind == "override" and info.entry.frames or nil
      sprite, missing = modules.textures.open(info.texture, paths, api, frames)
    end
    if not sprite then
      -- The editor shows no texture of another entry than the selected one
      dlg:modify{ id="editor", sprite=false }
      setStatus(paths and tr("status.missing", missing) or tr("status.outside", outside))
      return
    end
    local record = { id=id, texture=info.texture, paths=paths }
    opened[paths.png], opened[paths.aseprite] = record, record
    dlg:modify{ id="editor", sprite=sprite }
    -- A texture of a few pixels fills the editor
    api.fitScreen()
  end

  -- Opens the wizard for a new texture. Its tasks run in the queue,
  -- and what it created opens once the pack was read again.
  function ctl.create()
    modules.wizard.open{
      api=api,
      tr=tr,
      model=model or {},
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
        end, function(textures, err)
          setStatus(nil)
          vanilla = textures
          done(textures, textures == nil and modules.pack.refusal(err) or nil)
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

  -- Moves the tree to "left" or "right" of the editor
  function ctl.setSide(side)
    preferences.treeSide = side
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
    queue = {}
    local process = running and running.process
    if process and process.isRunning then
      process:stop()
    end
    running = nil
    dlg:close()
  end

  build(nil)
  ctl.reload()
  return ctl
end

return tab
