-- The dialog that resolves the conflict of a draft with main: both
-- versions of its texture side by side, the version of its files to
-- keep, and the side of each field that both changed.

local conflicts = {}

-- The size of the compare canvas, in points, and the space between
-- a caption and its image
local compareWidth, compareHeight, gap = 320, 180, 10
-- How many characters of a field's value the dialog shows
local valueLength = 40

-- The value as the dialog shows it, cut to valueLength characters
local function short(value)
  if utf8.len(value) <= valueLength then
    return value
  end
  return value:sub(1, utf8.offset(value, valueLength + 1) - 1) .. "…"
end

-- The side of a square of the checkerboard behind an image, in points
local check = 8

-- Draws images, each { image, caption }, side by side on a checkerboard
-- of the colors light and dark, each at the same whole scale that fits
-- both into its half
local function paint(gc, images, color, light, dark)
  gc.color = color.face
  gc:fillRect{ x=0, y=0, width=gc.width, height=gc.height }
  local half = gc.width // #images
  local top = gc:measureText("Ag").height + gap
  local width, height = 1, 1
  for _, shown in ipairs(images) do
    if shown.image then
      width, height = math.max(width, shown.image.width), math.max(height, shown.image.height)
    end
  end
  local scale = math.max(1, math.min((half - 8) // width, (gc.height - top) // height))
  for i, shown in ipairs(images) do
    local x = (i - 1) * half
    gc.color = color.text
    gc:fillText(shown.caption, x, 0)
    local image = shown.image
    if image then
      local w, h = image.width * scale, image.height * scale
      for cy = 0, h - 1, check do
        for cx = 0, w - 1, check do
          gc.color = (cx + cy) // check % 2 == 0 and light or dark
          gc:fillRect{ x=x + cx, y=top + cy, width=math.min(check, w - cx), height=math.min(check, h - cy) }
        end
      end
      gc:drawImage(image, { x=0, y=0, width=image.width, height=image.height }, { x=x, y=top, width=w, height=h })
    end
  end
end

-- Asks how to resolve the conflict of context.draft. context holds
-- Dialog, tr, Color and the images clone and main of its texture,
-- either nil.
-- Returns { keep, fields }, where keep is "clone" or "main" while
-- files conflict and fields names a side per field, or nil.
function conflicts.ask(context)
  local tr, conflict = context.tr, context.draft.conflict
  local d = context.Dialog{ title=tr("conflict.title", context.draft.label) }
  if context.clone or context.main then
    local light = context.Color and context.Color{ gray=255 }
    local dark = context.Color and context.Color{ gray=204 }
    local images = { { image=context.clone, caption=tr("conflict.clone") },
                     { image=context.main, caption=tr("conflict.main") } }
    d:canvas{ id="compare", width=compareWidth, height=compareHeight,
              onpaint=function(ev) paint(ev.context, images, ev.context.theme.color, light, dark) end }
    d:newrow()
  end
  if #conflict.files > 0 then
    d:radio{ id="keep_clone", label=tr("conflict.files"), text=tr("conflict.clone"), selected=true }
    d:radio{ id="keep_main", text=tr("conflict.main") }
  end
  for i, field in ipairs(conflict.fields) do
    d:radio{ id="clone" .. i, label=field.name, text=tr("conflict.clone_value", short(field.clone)), selected=true }
    d:radio{ id="main" .. i, text=tr("conflict.main_value", short(field.main)) }
  end
  d:button{ id="ok", text=tr("edit.ok"), focus=true }
  d:button{ id="cancel", text=tr("edit.cancel") }
  d:show()
  local data = d.data
  if not data.ok then
    return nil
  end
  local answer = { fields={} }
  if #conflict.files > 0 then
    answer.keep = data.keep_main and "main" or "clone"
  end
  for i, field in ipairs(conflict.fields) do
    answer.fields[field.name] = data["main" .. i] and "main" or "clone"
  end
  return answer
end

return conflicts
