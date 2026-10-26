local pack = require("pack")
local dkjson = require("dkjson")

-- Like app.fs.joinPath and normalizePath: empty and . names drop out, .. removes the name before it
local function join(...)
  local names = {}
  for name in table.concat({ ... }, "/"):gmatch("[^/]+") do
    if name == ".." then
      table.remove(names)
    elseif name ~= "." then
      table.insert(names, name)
    end
  end
  return (select(1, ...):sub(1, 1) == "/" and "/" or "") .. table.concat(names, "/")
end

local function fakeProcess(started)
  return function(args)
    local p = { args=args }
    function p:start() table.insert(started, self) end
    return p
  end
end

local function api(started, files, windows)
  return {
    Process=fakeProcess(started),
    windows=windows,
    join=join,
    read=function(path) return files[path] end,
    decode=dkjson.decode,
  }
end

describe("pack", function()
  it("runs describePack with the clone's gradlew and the bootstrap's environment", function()
    local started = {}
    local env = { JAVA_HOME="/jdk" }
    pack.describe({ clone="/c", env=env }, api(started, {}), function() end)
    assert.equal(1, #started)
    assert.same({ "/bin/sh", "/c/gradlew", "-q", ":resource-pack:describePack" }, started[1].args.cmd)
    assert.equal("/c", started[1].args.cwd)
    assert.equal(env, started[1].args.env)
  end)

  it("runs gradlew.bat on Windows", function()
    local started = {}
    pack.describe({ clone="C:/c", env={} }, api(started, {}, true), function() end)
    assert.same({ "C:/c/gradlew.bat", "-q", ":resource-pack:describePack" }, started[1].args.cmd)
  end)

  it("reads the model the task wrote", function()
    local started, got = {}, nil
    local files = { ["/c/resource-pack/build/pack-model.json"]='{"fonts":[{"font":"nordtal:gui"}],"items":[],"overrides":[]}' }
    pack.describe({ clone="/c", env={} }, api(started, files), function(model, err) got = { model, err } end)
    started[1].args.onexit(0)
    assert.equal("nordtal:gui", got[1].fonts[1].font)
    assert.is_nil(got[2])
  end)

  it("reports what gradlew printed to stderr when it fails", function()
    local started, got = {}, nil
    pack.describe({ clone="/c", env={} }, api(started, {}), function(model, err) got = { model, err } end)
    started[1].args.onstderr("FAILURE: Build failed")
    started[1].args.onstderr("glyphs.json > nordtal:gui: overlaps")
    started[1].args.onexit(1)
    assert.is_nil(got[1])
    assert.same({ "FAILURE: Build failed", "glyphs.json > nordtal:gui: overlaps" }, got[2])
  end)

  it("reports a model it cannot read", function()
    local started, got = {}, nil
    local files = { ["/c/resource-pack/build/pack-model.json"]="{" }
    pack.describe({ clone="/c", env={} }, api(started, files), function(model, err) got = { model, err } end)
    started[1].args.onexit(0)
    assert.is_nil(got[1])
    assert.equal(1, #got[2])
  end)

  it("turns the objects of Aseprite's json.decode into plain tables", function()
    -- Like a JsonObj: no fields of its own, # counts, pairs lists only an object's members
    local function jsonObj(value)
      if type(value) ~= "table" then return value end
      local isArray = value[1] ~= nil
      return setmetatable({}, {
        __index=function(_, k)
          if isArray then return jsonObj(value[k]) end
          if type(k) == "string" then return jsonObj(value[k]) end
        end,
        __len=function()
          local n = 0
          for _ in pairs(value) do n = n + 1 end
          return n
        end,
        __pairs=function()
          if isArray then return function() end, nil, nil end
          local key
          return function()
            local v
            key, v = next(value, key)
            return key, jsonObj(v)
          end, nil, nil
        end,
      })
    end
    local started, got = {}, nil
    local a = api(started, { ["/c/resource-pack/build/pack-model.json"]="x" })
    a.decode = function()
      return jsonObj({ fonts={ { font="nordtal:gui", blocks={} } }, items={}, overrides={} })
    end
    pack.describe({ clone="/c", env={} }, a, function(model) got = model end)
    started[1].args.onexit(0)
    assert.same({ fonts={ { font="nordtal:gui", blocks={} } }, items={}, overrides={} }, got)
    assert.is_nil(getmetatable(got.fonts[1]))
  end)

  it("gives the absolute paths of a texture's PNG and source", function()
    local paths = pack.paths("/c", { png="src/assets/nordtal/textures/a.png", aseprite="art/assets/nordtal/textures/a.aseprite" }, join)
    assert.same({ png="/c/resource-pack/src/assets/nordtal/textures/a.png",
                  aseprite="/c/resource-pack/art/assets/nordtal/textures/a.aseprite" }, paths)
  end)

  it("refuses a PNG outside resource-pack/src and a source outside resource-pack/art", function()
    local art = "art/assets/a.aseprite"
    for _, case in ipairs({
      { png="src/../../../Library/a.png", aseprite=art, refused="/Library/a.png" },
      { png="/Library/a.png", aseprite=art, refused="/c/resource-pack/Library/a.png" },
      { png="src/assets/../../srcx/a.png", aseprite=art, refused="/c/resource-pack/srcx/a.png" },
      { png="src/assets/a.png", aseprite="src/assets/a.aseprite", refused="/c/resource-pack/src/assets/a.aseprite" },
      { png="src/assets/a.png", aseprite="art/../../a.aseprite", refused="/c/a.aseprite" },
    }) do
      local paths, refused = pack.paths("/c", case, join)
      assert.is_nil(paths)
      assert.equal(case.refused, refused)
    end
  end)
end)
