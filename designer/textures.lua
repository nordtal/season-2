-- Opens a texture of the pack as a sprite and saves it as its
-- .aseprite source under resource-pack/art/ and its PNG. api holds
-- sprites, open, isFile, filePath, makeAllDirectories and Image.

local textures = {}

-- The sprite of a texture: one that is open already, else its source,
-- else its PNG. nil and the PNG's path while the PNG is missing.
function textures.open(texture, paths, api)
  for _, sprite in ipairs(api.sprites()) do
    if sprite.filename == paths.aseprite or sprite.filename == paths.png then
      return sprite
    end
  end
  if texture.asepriteExists and api.isFile(paths.aseprite) then
    return (api.open(paths.aseprite))
  end
  if api.isFile(paths.png) then
    return (api.open(paths.png))
  end
  return nil, paths.png
end

-- Writes the sprite to its source, which the first save creates, and
-- the visible layers of its first frame to the PNG.
function textures.save(sprite, texture, paths, api)
  api.makeAllDirectories(api.filePath(paths.aseprite))
  sprite:saveAs(paths.aseprite)
  local image = api.Image(sprite.spec)
  image:drawSprite(sprite, 1)
  image:saveAs{ filename=paths.png, palette=sprite.palettes[1] }
  texture.asepriteExists = true
end

return textures
