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
  it("runs describePack with the clone's Gradle wrapper on the bootstrap's JDK and environment", function()
    local started = {}
    local env = { JAVA_HOME="/jdk" }
    pack.describe({ clone="/c", env=env }, api(started, {}), function() end)
    assert.equal(1, #started)
    assert.same({ "/jdk/bin/java", "-jar", "/c/gradle/wrapper/gradle-wrapper.jar", "-q", ":resource-pack:describePack" },
      started[1].args.cmd)
    assert.equal("/c", started[1].args.cwd)
    assert.equal(env, started[1].args.env)
  end)

  it("puts the folder of the bootstrap's git first on PATH and keeps the rest of its environment", function()
    local started = {}
    local env = { JAVA_HOME="/jdk", GH_CONFIG_DIR="/gh" }
    local a = api(started, {})
    a.getenv = function(name) return name == "PATH" and "/usr/bin:/bin" or nil end
    pack.run({ clone="/c", env=env, git="/app/mingit/cmd/git" }, a, "describePack", {}, function() end)
    assert.same({ JAVA_HOME="/jdk", GH_CONFIG_DIR="/gh", PATH="/app/mingit/cmd:/usr/bin:/bin" }, started[1].args.env)
    assert.is_nil(env.PATH)
    local windows = api(started, {}, true)
    windows.getenv = function() return "C:\\Windows" end
    pack.run({ clone="C:/c", env=env, git="C:\\app\\mingit\\cmd\\git.exe" }, windows, "describePack", {}, function() end)
    assert.equal("C:\\app\\mingit\\cmd;C:\\Windows", started[2].args.env.PATH)
  end)

  it("starts java.exe on Windows and passes text that a batch file could not take", function()
    local started = {}
    pack.run({ clone="C:/c", env={ JAVA_HOME="C:/jdk" } }, api(started, {}, true), "editGlyph",
      { glyph="star", about="Stern (gold) & 100%" }, function() end)
    assert.same({ "C:/jdk/bin/java.exe", "-jar", "C:/c/gradle/wrapper/gradle-wrapper.jar", "-q", ":resource-pack:editGlyph",
                  "-Pabout=Stern (gold) & 100%", "-Pglyph=star" }, started[1].args.cmd)
  end)

  it("gives the reason when the process cannot start, and calls nothing back", function()
    local called = false
    local a = api({}, {})
    a.Process = function(args)
      return { args=args, start=function() error("cannot start 'java'") end }
    end
    local process, problem = pack.run({ clone="/c", env={ JAVA_HOME="/jdk" } }, a, "describePack", {}, function() called = true end)
    assert.is_nil(process)
    assert.truthy(problem:find("cannot start 'java'", 1, true))
    assert.is_false(called)
    process, problem = pack.describe({ clone="/c", env={} }, a, function() called = true end)
    assert.is_nil(process)
    assert.truthy(problem:find("JAVA_HOME", 1, true))
    assert.is_false(called)
  end)

  it("reads the model the task wrote", function()
    local started, got = {}, nil
    local files = { ["/c/resource-pack/build/pack-model.json"]='{"fonts":[{"font":"nordtal:gui"}],"items":[],"overrides":[]}' }
    pack.describe({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, files), function(model, err) got = { model, err } end)
    started[1].args.onexit(0)
    assert.equal("nordtal:gui", got[1].fonts[1].font)
    assert.is_nil(got[2])
  end)

  it("reports what gradlew printed to stderr when it fails", function()
    local started, got = {}, nil
    pack.describe({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, {}), function(model, err) got = { model, err } end)
    started[1].args.onstderr("FAILURE: Build failed")
    started[1].args.onstderr("glyphs.json > nordtal:gui: overlaps")
    started[1].args.onexit(1)
    assert.is_nil(got[1])
    assert.same({ "FAILURE: Build failed", "glyphs.json > nordtal:gui: overlaps" }, got[2])
  end)

  it("hands what the task printed to stdout to done, also when it fails", function()
    local started, got = {}, {}
    local a = api(started, {})
    pack.run({ clone="/c", env={ JAVA_HOME="/jdk" } }, a, "createItem", { item="nordtal:wand" },
      function(ok, err, out) table.insert(got, { ok, err, out }) end)
    pack.run({ clone="/c", env={ JAVA_HOME="/jdk" } }, a, "createItem", { item="nordtal:Wand" },
      function(ok, err, out) table.insert(got, { ok, err, out }) end)
    started[1].args.onstdout("/c/resource-pack/src/assets/nordtal/items/wand.json")
    started[1].args.onexit(0)
    started[2].args.onstdout("refused path")
    started[2].args.onstderr("* What went wrong:")
    started[2].args.onexit(1)
    assert.same({ { true, {}, { "/c/resource-pack/src/assets/nordtal/items/wand.json" } },
                  { false, { "* What went wrong:" }, { "refused path" } } }, got)
  end)

  it("reads the textures of the client jar that listVanillaTextures wrote", function()
    local started, got = {}, nil
    local files = { ["/c/resource-pack/build/vanilla-textures.json"]=
      '{"version":"26.2","textures":[{"id":"minecraft:block/stone.png","width":16,"height":16}]}' }
    pack.vanilla({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, files), function(textures, err) got = { textures, err } end)
    assert.equal(":resource-pack:listVanillaTextures", started[1].args.cmd[5])
    started[1].args.onexit(0)
    assert.same({ { id="minecraft:block/stone.png", width=16, height=16 } }, got[1])
    assert.is_nil(got[2])
  end)

  it("reads an item definition from the last line that readItem printed", function()
    local started, got, err = {}, nil, nil
    pack.item({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, {}), "nordtal:lamp", function(read, e) got, err = read, e end)
    assert.equal(":resource-pack:readItem -Pitem=nordtal:lamp", table.concat(started[1].args.cmd, " ", 5))
    started[1].args.onstdout("Reading the client jar")
    started[1].args.onstdout('{"item":"nordtal:lamp","definition":{"model":{"type":"minecraft:empty"}},"problems":[]}')
    started[1].args.onexit(0)
    assert.same({ item="nordtal:lamp", definition={ model={ type="minecraft:empty" } }, problems={} }, got)
    assert.is_nil(err)

    pack.item({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, {}), "nordtal:none", function(read, e) got, err = read, e end)
    started[2].args.onstderr("> Unknown item nordtal:none")
    started[2].args.onexit(1)
    assert.is_nil(got)
    assert.same({ "> Unknown item nordtal:none" }, err)
  end)

  it("reports a line readItem printed that holds no object", function()
    local started, got, err = {}, nil, nil
    pack.item({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, {}), "nordtal:lamp", function(read, e) got, err = read, e end)
    started[1].args.onstdout("42")
    started[1].args.onexit(0)
    assert.is_nil(got)
    assert.same({ "42" }, err)
  end)

  it("reads the fields that a refused editItem still needs, with their kinds", function()
    assert.same({ { name="base", kind="id" }, { name="when", kind="json" } },
      pack.needs({ "needs base id", "/c/x.json", "needs when json", "refused id" }))
    assert.same({}, pack.needs({ "refused id" }))
  end)

  it("reports a model it cannot read", function()
    local started, got = {}, nil
    local files = { ["/c/resource-pack/build/pack-model.json"]="{" }
    pack.describe({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, files), function(model, err) got = { model, err } end)
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
    pack.describe({ clone="/c", env={ JAVA_HOME="/jdk" } }, a, function(model) got = model end)
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
  it("runs a task of the resource pack with its options in a fixed order", function()
    local started, got = {}, nil
    pack.run({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, {}), "editGlyph", { glyph="star", about="a star", height=7 },
      function(ok, err) got = { ok, err } end)
    assert.same({ "/jdk/bin/java", "-jar", "/c/gradle/wrapper/gradle-wrapper.jar", "-q", ":resource-pack:editGlyph",
                  "-Pabout=a star", "-Pglyph=star", "-Pheight=7" }, started[1].args.cmd)
    started[1].args.onexit(0)
    assert.same({ true, {} }, got)
  end)

  it("reports what a failed task printed to stderr", function()
    local started, got = {}, nil
    pack.run({ clone="/c", env={ JAVA_HOME="/jdk" } }, api(started, {}), "deleteGlyph", { glyph="star" },
      function(ok, err) got = { ok, err } end)
    started[1].args.onstderr("FAILURE: Build failed")
    started[1].args.onexit(1)
    assert.same({ false, { "FAILURE: Build failed" } }, got)
  end)

  it("takes the reason of a refusal out of Gradle's report, with each place it lists", function()
    assert.same({ "admin is still used in 2 places:",
                  "database/src/main/resources/messages/database/de.properties:25",
                  "proxy/src/main/java/eu/nordtal/season/proxy/command/Replies.java:30" }, pack.refusal({
      "",
      "FAILURE: Build failed with an exception.",
      "",
      "* What went wrong:",
      "Execution failed for task ':resource-pack:renameGlyph' (registered by plugin 'nordtal.resource-pack').",
      "> admin is still used in 2 places:",
      "      database/src/main/resources/messages/database/de.properties:25",
      "      proxy/src/main/java/eu/nordtal/season/proxy/command/Replies.java:30",
      "",
      "* Try:",
      "> Run with --stacktrace option to get the stack trace.",
    }))
  end)

  it("gives every line of a failure without Gradle's report", function()
    assert.same({ "error: no java" }, pack.refusal({ "", "error: no java" }))
  end)
end)
