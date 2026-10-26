-- Opens a texture of the pack as a sprite and saves it as its
-- .aseprite source under resource-pack/art/ and its PNG. api holds
-- sprites, open, isFile, filePath, makeAllDirectories, Image,
-- Rectangle and Sprite.

local textures = {}

-- A new sprite with one frame per entry of frames, each { x, y,
-- width, height, duration } in the PNG's sprite png, which it closes,
-- and with its transparent index.
-- Its filename is the source, so the first save writes it.
local function animation(png, frames, source, api)
  local whole = api.Image(png.spec)
  whole:drawSprite(png, 1)
  local first = frames[1]
  local sprite = api.Sprite(first.width, first.height, png.colorMode)
  sprite:setPalette(png.palettes[1])
  sprite.transparentColor = png.transparentColor
  for i, f in ipairs(frames) do
    local frame = i == 1 and sprite.frames[1] or sprite:newEmptyFrame()
    frame.duration = f.duration / 1000
    sprite:newCel(sprite.layers[1], i, api.Image(whole, api.Rectangle(f.x, f.y, f.width, f.height)), { x=0, y=0 })
  end
  png:close()
  sprite.filename = source
  return sprite
end

-- The sprite of a texture: one that is open already, else its source,
-- else its PNG, cut into the frames the model gives for its animation. nil and the PNG's
-- path while the PNG is missing.
function textures.open(texture, paths, api, frames)
  for _, sprite in ipairs(api.sprites()) do
    if sprite.filename == paths.aseprite or sprite.filename == paths.png then
      return sprite
    end
  end
  if texture.asepriteExists and api.isFile(paths.aseprite) then
    return (api.open(paths.aseprite))
  end
  if api.isFile(paths.png) then
    local png = api.open(paths.png)
    if png and frames and #frames > 0 then
      return (animation(png, frames, paths.aseprite, api))
    end
    return png
  end
  return nil, paths.png
end

-- Writes the sprite to its source, which the first save creates, and
-- to the PNG the visible layers of its first frame, or of every frame
-- in a column from the top when column is true.
function textures.save(sprite, texture, paths, api, column)
  api.makeAllDirectories(api.filePath(paths.aseprite))
  sprite:saveAs(paths.aseprite)
  local image
  if column then
    local spec = sprite.spec
    spec.height = sprite.height * #sprite.frames
    image = api.Image(spec)
    for i = 1, #sprite.frames do
      image:drawSprite(sprite, i, { x=0, y=(i - 1) * sprite.height })
    end
  else
    image = api.Image(sprite.spec)
    image:drawSprite(sprite, 1)
  end
  image:saveAs{ filename=paths.png, palette=sprite.palettes[1] }
  texture.asepriteExists = true
end

return textures
