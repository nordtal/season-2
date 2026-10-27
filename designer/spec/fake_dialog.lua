-- A Dialog for the specs that records its widgets and every modify,
-- so a spec can call the handlers as Aseprite would.
--
-- local Dialog, dialogs, shown = fake(answer) gives the constructor,
-- every dialog it built and every dialog that was shown, in order.
-- answer is nil, a function(dialog) or a list of them. A list gives
-- each show the next one. dialogs.answer replaces it, and
-- dialogs.bounds(dialog) gives where a shown dialog is. A dialog with
-- the argument tab is a tab and gets no answer.

local kinds = { "button", "canvas", "check", "combobox", "editor", "endtabs", "entry", "file", "label", "number",
                "panel", "radio", "separator", "splitter", "tab", "tree" }

-- The value that a widget of kind starts with in the dialog's data
local starts = {
  check=function(t) return t.selected end,
  combobox=function(t) return t.option end,
  editor=function(t) return t.sprite end,
  entry=function(t) return t.text end,
  radio=function(t) return t.selected or false end,
}

-- Like luaL_checkoption in the fork, a side other than left or right raises
local function checkSide(side)
  assert(side == nil or side == "left" or side == "right", "the side panel's side is \"left\" or \"right\"")
end

return function(answer)
  local dialogs, shown = {}, {}
  dialogs.answer = answer
  local function Dialog(args)
    -- Like Dialog, an assignment to data sets the values it names
    local d = setmetatable({ args=args, widgets={}, handlers={}, kinds={}, values={}, modified={}, shown=0,
                             closed=false, sizeHint={ width=300, height=200 } }, {
      __index=function(t, k) if k == "data" then return rawget(t, "values") end end,
      __newindex=function(t, k, v)
        if k ~= "data" then return rawset(t, k, v) end
        for name, value in pairs(v) do t.values[name] = value end
      end,
    })
    for _, kind in ipairs(kinds) do
      d[kind] = function(self, t)
        t = t or {}
        if kind == "panel" then
          assert(args and args.tab, "Dialog:panel{} needs a Dialog{ tab=true }")
          assert(not self.panelArgs, "a Dialog has one side panel at most")
          checkSide(t.side)
          self.panelArgs = t
        end
        if kind == "splitter" then
          assert(self.panelArgs, "Dialog:splitter{} needs a Dialog:panel{} before it")
          assert(not self.inTabs, "Dialog:splitter{} can't be between Dialog:tab{} and Dialog:endtabs{}")
          assert(not self.split, "a side panel has one splitter at most")
          self.split = true
        end
        if kind == "tab" then self.inTabs = true end
        if kind == "endtabs" then self.inTabs = nil end
        if t.id and self.panelArgs and self.kinds[t.id] and (kind == "panel" or self.kinds[t.id] == "panel") then
          error("the id \"" .. t.id .. "\" is taken by the side panel or a widget")
        end
        table.insert(self.widgets, { kind=kind, args=t })
        if t.id then
          self.handlers[t.id], self.kinds[t.id] = t, kind
          if starts[kind] then self.values[t.id] = starts[kind](t) end
        end
        return self
      end
    end
    function d:samerow() table.insert(self.widgets, { kind="samerow" }) return self end
    function d:newrow() table.insert(self.widgets, { kind="newrow" }) return self end
    function d:repaint() self.repainted = (self.repainted or 0) + 1 end
    function d:close() self.closed = true end
    function d:modifyNode(t)
      self.nodesModified = self.nodesModified or {}
      table.insert(self.nodesModified, t)
    end
    function d:modify(t)
      table.insert(self.modified, t)
      local widget = t.id and self.handlers[t.id]
      if not widget then return end
      if self.kinds[t.id] == "panel" then checkSide(t.side) end
      for k, v in pairs(t) do widget[k] = v end
      if t.sprite ~= nil then self.values[t.id] = t.sprite or nil end
      if t.text and self.kinds[t.id] == "entry" then self.values[t.id] = t.text end
    end
    -- Only the button the answer clicks holds true
    function d:show(showArgs)
      self.shown, self.showArgs = self.shown + 1, showArgs
      table.insert(shown, self)
      if dialogs.bounds then self.bounds = dialogs.bounds(self) end
      local next = dialogs.answer
      if type(next) == "table" then next = table.remove(next, 1) end
      if next and not (args and args.tab) then
        for id, kind in pairs(self.kinds) do
          if kind == "button" then self.values[id] = nil end
        end
        next(self)
      end
      return self
    end
    -- The texts of the labels that show, in their order
    function d:labels()
      local out = {}
      for _, w in ipairs(self.widgets) do
        if w.kind == "label" and w.args.visible ~= false then table.insert(out, w.args.text) end
      end
      return out
    end
    table.insert(dialogs, d)
    return d
  end
  return Dialog, dialogs, shown
end
