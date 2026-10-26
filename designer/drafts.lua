-- The drafts of the pack model: the changes of the clone that are in
-- no pull request yet, and the dialog that publishes or discards them.

local drafts = {}

-- How many lines of a refusal the publish dialog shows; the last one
-- holds the rest
local refusalLines = 6
-- The width the publish dialog takes at least, so a long title shows
local dialogWidth = 480

-- The id of the draft that the tree's entry info is, as the pack
-- model names it beside its texture or entry, or nil when the entry
-- is no draft. A cell is part of its sheet's draft.
function drafts.id(info)
  if not info or info.kind == "cell" then
    return nil
  end
  if info.texture then
    return info.texture.draft
  end
  return info.entry and info.entry.draft or nil
end

-- The commit title for the drafts list: what they do to the pack and
-- up to three of their names
function drafts.title(list)
  local states, names = {}, {}
  for i, draft in ipairs(list) do
    states[draft.state] = true
    if i <= 3 then table.insert(names, draft.label) end
  end
  local verb = "changes"
  if states.added and not states.changed and not states.deleted then
    verb = "adds"
  elseif states.deleted and not states.changed and not states.added then
    verb = "removes"
  end
  local more = #list - #names
  local text
  if more > 0 then
    text = table.concat(names, ", ") .. " and " .. more .. " more"
  elseif #names > 1 then
    text = table.concat(names, ", ", 1, #names - 1) .. " and " .. names[#names]
  else
    text = names[1] or ""
  end
  return "feat(resource-pack): the pack " .. verb .. " " .. text
end

-- Asks which drafts of context.list to publish or discard, with a
-- check per draft and an editable title that follows the drafts
-- chosen until it is edited. context holds Dialog, tr, list, check
-- and confirm. check(title) returns true, or false and the lines of
-- its reason, which the dialog shows before it asks again.
-- confirm(text) returns whether the designer confirms a discard.
-- wrap(text) breaks a refusal into lines. Rectangle and screen, which
-- gives the window's width and height, widen the dialog.
-- Returns { publish=ids, title }, { discard=ids } or nil when cancelled.
function drafts.ask(context)
  local tr, list = context.tr, context.list
  local d = context.Dialog{ title=tr("publish.title") }
  local generated, edited

  local function chosen()
    local out = {}
    for i, draft in ipairs(list) do
      if d.data["draft" .. i] then table.insert(out, draft) end
    end
    return out
  end

  local function retitle()
    if edited then return end
    generated = drafts.title(chosen())
    d:modify{ id="title", text=generated }
  end

  for i, draft in ipairs(list) do
    local state = tr("drafts." .. draft.state)
    local text = draft.review and tr("publish.draft_review", draft.label, state, draft.review)
      or tr("publish.draft", draft.label, state)
    d:check{ id="draft" .. i, text=text, selected=true, onclick=retitle }
    d:newrow()
  end
  generated = drafts.title(list)
  d:entry{ id="title", label=tr("publish.message"), text=generated,
           onchange=function() edited = d.data.title ~= generated end }
  for i = 1, refusalLines do
    d:label{ id="refusal" .. i, text="", visible=false }
    d:newrow()
  end
  d:button{ id="publish", text=tr("publish.publish"), focus=true }
  d:button{ id="discard", text=tr("publish.discard") }
  d:button{ id="cancel", text=tr("edit.cancel") }

  local function refuse(lines)
    local text = table.concat(lines, " ")
    local wrapped = context.wrap and context.wrap(text) or { text }
    for i = 1, refusalLines do
      local line = i == refusalLines and wrapped[i] and table.concat(wrapped, " ", i) or wrapped[i]
      d:modify{ id="refusal" .. i, text=line or "", visible=line ~= nil }
    end
  end

  -- Takes the dialog's own size, but at least dialogWidth inside the
  -- window, and centres it there
  local function fit()
    if not (context.Rectangle and context.screen) then return end
    local hint = d.sizeHint
    local width, height = context.screen()
    local w = math.max(1, math.min(width - 16, math.max(hint.width, dialogWidth)))
    d.bounds = context.Rectangle((width - w) // 2, (height - hint.height) // 2, w, hint.height)
  end

  while true do
    fit()
    d:show()
    local data = d.data
    if not (data.publish or data.discard) then
      return nil
    end
    local picked = chosen()
    local ids, labels = {}, {}
    for _, draft in ipairs(picked) do
      table.insert(ids, draft.id)
      table.insert(labels, draft.label)
    end
    if #ids == 0 then
      refuse({ tr("publish.none_chosen") })
    elseif data.discard then
      if context.confirm(tr("publish.discard_confirm", table.concat(labels, ", "))) then
        return { discard=ids }
      end
    else
      local ok, reason = context.check(data.title)
      if ok then
        return { publish=ids, title=data.title }
      end
      refuse(reason)
    end
  end
end

return drafts
