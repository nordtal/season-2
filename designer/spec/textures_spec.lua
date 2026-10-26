local textures = require("textures")

local paths = { png="/c/resource-pack/src/assets/nordtal/textures/badges/star.png",
                aseprite="/c/resource-pack/art/assets/nordtal/textures/badges/star.aseprite" }

local function fakeSprite(filename, log)
  local sprite = { filename=filename, spec={ width=7, height=7 }, width=7, height=7, colorMode="rgb",
                   palettes={ "palette" } }
  function sprite:saveAs(fn)
    table.insert(log, { "sprite:saveAs", fn })
    self.filename = fn
  end
  return sprite
end

local function api(files, open, log)
  local opened, closed = {}, {}
  return {
    sprites=function() return open end,
    isFile=function(fn) return files[fn] == true end,
    open=function(fn)
      table.insert(opened, fn)
      local sprite = fakeSprite(fn, log)
      function sprite:close() table.insert(closed, self.filename) end
      table.insert(open, sprite)
      return sprite
    end,
    filePath=function(fn) return (fn:match("^(.*)/[^/]*$")) end,
    makeAllDirectories=function(dir) table.insert(log, { "mkdir", dir }) end,
    Image=function(spec)
      local image = { spec=spec }
      function image:drawSprite(sprite, frame) table.insert(log, { "drawSprite", sprite.filename, frame }) end
      function image:saveAs(args) table.insert(log, { "image:saveAs", args.filename, args.palette, self.spec }) end
      return image
    end,
    opened=opened,
    closed=closed,
  }
end

describe("textures", function()
  it("opens the source of a texture that has one", function()
    local a = api({ [paths.aseprite]=true, [paths.png]=true }, {}, {})
    local sprite = textures.open({ asepriteExists=true }, paths, a)
    assert.same({ paths.aseprite }, a.opened)
    assert.equal(paths.aseprite, sprite.filename)
  end)

  it("opens the PNG of a texture that has no source yet", function()
    local a = api({ [paths.png]=true }, {}, {})
    textures.open({ asepriteExists=false }, paths, a)
    assert.same({ paths.png }, a.opened)
  end)

  it("takes a sprite that is open already instead of opening the file again", function()
    local open = { fakeSprite(paths.png, {}) }
    local a = api({ [paths.png]=true }, open, {})
    assert.equal(open[1], textures.open({ asepriteExists=false }, paths, a))
    assert.same({}, a.opened)
  end)

  it("opens nothing while the PNG is missing", function()
    local a = api({}, {}, {})
    local sprite, err = textures.open({ asepriteExists=false }, paths, a)
    assert.is_nil(sprite)
    assert.equal(paths.png, err)
    assert.same({}, a.opened)
  end)

  it("writes the source under art/ and exports frame 1 as the PNG on every save", function()
    local log = {}
    local texture = { asepriteExists=false }
    local sprite = fakeSprite(paths.png, log)
    textures.save(sprite, texture, paths, api({}, { sprite }, log))
    assert.same({
      { "mkdir", "/c/resource-pack/art/assets/nordtal/textures/badges" },
      { "sprite:saveAs", paths.aseprite },
      { "drawSprite", paths.aseprite, 1 },
      { "image:saveAs", paths.png, "palette", sprite.spec },
    }, log)
    assert.is_true(texture.asepriteExists)
  end)
  it("writes every frame of the sprite as a column of the PNG when asked", function()
    local log = {}
    local sprite = fakeSprite(paths.png, log)
    sprite.colorMode = "rgb"
    sprite.frames = { {}, {}, {} }
    local a = api({}, { sprite }, log)
    a.Image = function(width, height, colorMode)
      local image = { size={ width, height, colorMode } }
      function image:drawSprite(s, frame, point) table.insert(log, { "drawSprite", frame, point.x, point.y }) end
      function image:saveAs(args) table.insert(log, { "image:saveAs", args.filename, self.size }) end
      return image
    end
    textures.save(sprite, { asepriteExists=true }, paths, a, true)
    assert.same({
      { "mkdir", "/c/resource-pack/art/assets/nordtal/textures/badges" },
      { "sprite:saveAs", paths.aseprite },
      { "drawSprite", 1, 0, 0 }, { "drawSprite", 2, 0, 7 }, { "drawSprite", 3, 0, 14 },
      { "image:saveAs", paths.png, { 7, 21, "rgb" } },
    }, log)
  end)

  it("opens the PNG of an animation without a source as one frame per frame it plays", function()
    local log = {}
    local a = api({ [paths.png]=true }, {}, log)
    local made
    a.Image = function(from, rect)
      if rect then
        return { from=from, rect=rect }
      end
      local image = { spec=from }
      function image:drawSprite(s, frame) table.insert(log, { "drawSprite", s.filename, frame }) end
      return image
    end
    a.Sprite = function(width, height, colorMode)
      made = { size={ width, height, colorMode }, frames={ { duration=0.1 } }, layers={ "layer" }, cels={} }
      function made:setPalette(palette) self.palette = palette end
      function made:newEmptyFrame() local f = { duration=0.1 } table.insert(self.frames, f) return f end
      function made:newCel(layer, frame, image, point) table.insert(self.cels, { layer, frame, image.rect, point.x, point.y }) end
      return made
    end
    local frames = { { x=0, y=7, width=7, height=7, duration=500 }, { x=0, y=0, width=7, height=7, duration=50 } }
    local sprite = textures.open({ asepriteExists=false }, paths, a, frames)
    assert.equal(made, sprite)
    assert.equal(paths.aseprite, sprite.filename)
    assert.same({ 7, 7, "rgb" }, made.size)
    assert.equal("palette", made.palette)
    assert.same({ 0.5, 0.05 }, { made.frames[1].duration, made.frames[2].duration })
    assert.same({ { "layer", 1, { x=0, y=7, width=7, height=7 }, 0, 0 },
                  { "layer", 2, { x=0, y=0, width=7, height=7 }, 0, 0 } }, made.cels)
    assert.is_true(a.closed[1] == paths.png)
  end)
end)
