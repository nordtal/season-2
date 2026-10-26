-- The Minecraft bar of the Nordtal tab and the previews of the
-- selected entry in the properties panel. The bar holds the instance
-- the pack is installed into, live sync and the bridge mod. A preview
-- is a list of requests of the bridge mod's protocol, which
-- bridge-mod/README.md documents. Without Fabric the panel offers the
-- vanilla commands that show the same.

local minecraft = {}

-- The most characters a command typed into the chat may have
minecraft.LIMIT = 256

-- text with each character beyond ASCII as an SNBT escape. A command
-- is then plain ASCII: its text is what is copied, and its length in
-- bytes is the length the chat counts.
local function ascii(text)
  return (text:gsub(utf8.charpattern, function(char)
    local code = utf8.codepoint(char)
    if code < 0x80 then
      return char
    end
    return code > 0xFFFF and string.format("\\U%08X", code) or string.format("\\u%04X", code)
  end))
end

-- The commands that show request. Minecraft reads a text component
-- as SNBT, which accepts its JSON and the escapes of ascii. A bare
-- string is written as an object, since json.encode writes tables only.
function minecraft.commands(request, encode)
  local op = request.op
  local component = type(request.text) == "string" and { text=request.text } or request.text
  local text = component ~= nil and ascii(encode(component)) or nil
  if op == "chat" then
    return { "/tellraw @s " .. text }
  elseif op == "actionbar" or op == "title" then
    return { "/title @s " .. op .. " " .. text }
  elseif op == "bossbar" then
    return { "/bossbar remove nordtal:preview", "/bossbar add nordtal:preview " .. text,
             "/bossbar set nordtal:preview players @s" }
  elseif op == "chest" then
    return { "/give @s chest[custom_name=" .. text .. "]" }
  elseif op == "give" then
    return { "/give @s " .. request.item }
  elseif op == "block" then
    return { "/setblock ^ ^ ^2 " .. request.block .. " keep" }
  end
  return {}
end

-- The places a glyph alone is shown in, and the request of each
local raw = { "chat", "actionbar", "title", "bossbar", "chest" }

-- The previews of the entry info, each { label, requests }, and
-- whether a glyph's previews are still to be rendered. rendered is
-- what renderGlyphPreviews wrote for it, definition what readItem
-- printed for the selected item.
function minecraft.previews(info, rendered, definition, tr)
  local list = {}
  local function add(label, ...)
    table.insert(list, { label=label, requests={ ... } })
  end
  local kind = info and info.kind
  if (kind == "glyph" or kind == "cell") and info.entry.code then
    if not rendered then
      return list, true
    end
    for _, op in ipairs(raw) do add(tr("minecraft." .. op), { op=op, text=rendered.glyph }) end
    if rendered.menu then add(tr("minecraft.menu"), { op="chest", text=rendered.menu, rows=3 }) end
    if rendered.hud then add(tr("minecraft.hud"), { op="bossbar", text=rendered.hud }) end
    if rendered.board then add(tr("minecraft.board"), { op="chat", text=rendered.board }) end
  elseif (kind == "item" and definition and info.entry.id == definition.item or kind == "definition" and definition) then
    for _, state in ipairs(definition.states or {}) do
      add(state.label ~= "" and tr("minecraft.give", state.label) or tr("minecraft.give_plain"), { op="give", item=state.give })
    end
  elseif kind == "override" then
    if info.entry.block then add(tr("minecraft.place", info.entry.block), { op="block", block=info.entry.block }) end
    if info.entry.item then add(tr("minecraft.give", info.entry.item), { op="give", item=info.entry.item }) end
  end
  return list, false
end

-- The colour of each state of the bridge, as the tab names it
local tones = { connected="good", connecting="wait", unreachable="wait", refused="bad", none="off" }

-- The items of the Minecraft bar for view: { instance, liveSync,
-- bridge }. instance is what describeInstance printed, nil before its
-- first read, and bridge the state of the connection. The instance
-- box shows the folder name and has the whole path as its tip. A field
-- is a table whose minecraft names the action.
function minecraft.bar(view, tr)
  if not view.instance then
    return {}
  end
  local chosen = view.instance.instance
  local list = {
    { kind="box", text=chosen and chosen:match("([^/\\]+)[/\\]*$") or chosen or tr("minecraft.no_instance"), tip=chosen,
      field={ minecraft="choose" }, shrink="start", least=4 },
    { kind="switch", text=tr("minecraft.live_sync"), value=view.liveSync == true, field={ minecraft="live" },
      tip=tr("minecraft.live_sync_tip") },
  }
  if not chosen then
    return list
  end
  table.insert(list, { kind="button", text=tr("minecraft.install_pack"), field={ minecraft="pack" } })
  if not view.instance.fabric then
    table.insert(list, { kind="text", text=tr("minecraft.state_no_fabric"), color="off", tip=tr("minecraft.no_fabric") })
    return list
  end
  local jar = view.instance.bridge
  table.insert(list, { kind="button", text=tr(jar and "minecraft.update_bridge" or "minecraft.install_bridge"),
                       field={ minecraft="bridge" } })
  if view.instance.problem then
    table.insert(list, { kind="text", text=tr("minecraft.state_problem"), color="bad", tip=view.instance.problem })
    return list
  end
  -- A jar without a connection reads as unreachable
  local state = view.bridge or (jar and "unreachable" or "none")
  local item = { kind="text", text=tr("minecraft.state_" .. state), color=tones[state], tip=tr("minecraft.bridge_" .. state) }
  if state == "refused" then
    item.kind, item.field = "button", { minecraft="retry" }
  end
  table.insert(list, item)
  return list
end

-- The panel rows of the previews for view: { instance, previews,
-- render }. instance is what describeInstance printed. A field is a
-- table whose minecraft names the action.
function minecraft.rows(view, tr, encode)
  local fabric = view.instance and view.instance.instance and view.instance.fabric
  local list = {}
  if view.render then
    table.insert(list, { text=tr("minecraft.render"), field={ minecraft="render" }, kind="button" })
  end
  local previews = view.previews or {}
  if #previews == 0 then
    return list
  end
  table.insert(list, { text=tr("minecraft.previews"), value=tr(fabric and "minecraft.send" or "minecraft.copy") })
  for _, preview in ipairs(previews) do
    if fabric then
      table.insert(list, { text=preview.label, field={ minecraft="preview", requests=preview.requests }, kind="button" })
    else
      -- The label is the key of the first command, so it sits close to its box
      local label = preview.label
      for _, request in ipairs(preview.requests) do
        for _, command in ipairs(minecraft.commands(request, encode)) do
          if #command > minecraft.LIMIT then
            table.insert(list, { text=label, value=tr("minecraft.too_long", #command) })
          else
            table.insert(list, { text=label, value=command, field={ minecraft="copy", text=command } })
          end
          label = nil
        end
      end
    end
  end
  return list
end

-- A connection to the bridge mod on the loopback address: { state,
-- send(request, done), close() }. state is "connecting", "connected",
-- "unreachable" or "refused", and changed() is called on each change.
-- The socket connects again by itself after a close. A refusal
-- closes it for good, and a new bridge() connects again. args holds
-- WebSocket, types (WebSocketMessageType), encode, decode (text to a
-- plain table, or nil), port, token and changed.
function minecraft.bridge(args)
  local bridge = { state="connecting" }
  local waiting, last = {}, 0
  local function set(state, err)
    bridge.state, bridge.error = state, err
    args.changed()
  end
  local socket
  socket = args.WebSocket{
    url="ws://127.0.0.1:" .. math.tointeger(args.port),
    minreconnectwait=1,
    maxreconnectwait=10,
    onreceive=function(kind, data, err)
      if kind == args.types.OPEN then
        set("connecting")
        -- A client gone since the open raises here; its close follows
        pcall(socket.sendText, socket, args.encode({ op="hello", token=args.token }))
      elseif kind == args.types.TEXT then
        local reply = args.decode(data)
        if not reply then
          return
        end
        if reply.id == nil then
          set(reply.ok and "connected" or "refused", reply.error)
          -- IXWebSocket would connect again at once and be refused again
          if not reply.ok then socket:close() end
          return
        end
        local done = waiting[reply.id]
        waiting[reply.id] = nil
        if done then done(reply.ok == true, reply.error) end
        args.changed()
      elseif kind == args.types.CLOSE or kind == args.types.ERROR then
        for id, done in pairs(waiting) do
          waiting[id] = nil
          done(false, err)
        end
        if bridge.state ~= "refused" then set("unreachable", err) end
      end
    end,
  }
  socket:connect()

  -- Sends request and calls done(ok, error) with the reply, or
  -- done(false) at once while the bridge is not connected
  function bridge.send(request, done)
    if bridge.state ~= "connected" then
      done(false, nil)
      return
    end
    last = last + 1
    local frame = { id=last }
    for k, v in pairs(request) do frame[k] = v end
    waiting[last] = done
    local sent, err = pcall(socket.sendText, socket, args.encode(frame))
    if not sent then
      waiting[last] = nil
      done(false, err)
    end
  end

  function bridge.close()
    socket:close()
  end
  return bridge
end

return minecraft
