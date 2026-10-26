local textures = require("textures")

local paths = { png="/c/resource-pack/src/assets/nordtal/textures/badges/star.png",
                aseprite="/c/resource-pack/art/assets/nordtal/textures/badges/star.aseprite" }

local function fakeSprite(filename, log)
  local sprite = { filename=filename, spec={ width=7, height=7 }, palettes={ "palette" } }
  function sprite:saveAs(fn)
    table.insert(log, { "sprite:saveAs", fn })
    self.filename = fn
  end
  return sprite
end

local function api(files, open, log)
  local opened = {}
  return {
    sprites=function() return open end,
    isFile=function(fn) return files[fn] == true end,
    open=function(fn)
      table.insert(opened, fn)
      local sprite = fakeSprite(fn, log)
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
end)
