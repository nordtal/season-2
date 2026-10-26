-- The pack model of the clone, as :resource-pack:describePack writes
-- it, and the files of a texture in it.

local pack = {}

-- A copy of a decoded JSON value as plain tables. Aseprite's
-- json.decode gives objects that make a new copy of a member on each
-- read and whose pairs() lists no array items.
local function plain(value)
  local kind = type(value)
  if kind ~= "table" and kind ~= "userdata" then
    return value
  end
  local copy = {}
  if kind == "table" and getmetatable(value) == nil then
    for k, v in pairs(value) do copy[k] = plain(v) end
  elseif #value > 0 and value[1] ~= nil then
    for i = 1, #value do copy[i] = plain(value[i]) end
  else
    for k, v in pairs(value) do copy[k] = plain(v) end
  end
  return copy
end

-- Runs the task :resource-pack:<task> in the clone with options as
-- -P properties, sorted by name, and calls done(true, {}, out) or
-- done(false, lines, out) with what it printed to stderr. out holds
-- the lines it printed to stdout, such as the files it wrote. The Gradle
-- wrapper's jar runs on the JDK of the bootstrap's JAVA_HOME, so no
-- shell or batch file reads an option. api holds Process, windows and
-- join. Returns the started Process, or nil and the reason why none
-- started, and then calls nothing back.
function pack.run(bootstrap, api, task, options, done)
  local clone, home = bootstrap.clone, bootstrap.env.JAVA_HOME
  if not home then
    return nil, "the bootstrap gives no JAVA_HOME"
  end
  local cmd = { api.join(home, "bin", api.windows and "java.exe" or "java"),
                "-jar", api.join(clone, "gradle", "wrapper", "gradle-wrapper.jar"),
                "-q", ":resource-pack:" .. task }
  local keys = {}
  for key in pairs(options or {}) do table.insert(keys, key) end
  table.sort(keys)
  for _, key in ipairs(keys) do
    table.insert(cmd, "-P" .. key .. "=" .. tostring(options[key]))
  end

  local err, out = {}, {}
  local process = api.Process{
    cmd=cmd,
    cwd=clone,
    env=bootstrap.env,
    onstdout=function(line) table.insert(out, line) end,
    onstderr=function(line) table.insert(err, line) end,
    onexit=function(code) done(code == 0, code == 0 and {} or err, out) end }
  local started, problem = pcall(process.start, process)
  if not started then
    return nil, tostring(problem)
  end
  return process
end

-- The fields { name, kind } that a refused editItem printed on its
-- lines "needs <name> <kind>" to its stdout out
function pack.needs(out)
  local list = {}
  for _, line in ipairs(out) do
    local name, kind = line:match("^needs (%S+) (%S+)$")
    if name then table.insert(list, { name=name, kind=kind }) end
  end
  return list
end

-- The reason of a failed task: the lines under "What went wrong" in
-- Gradle's report without the task's name, each place a refusal lists
-- on a line of its own. Without the report, every line that is not
-- empty.
function pack.refusal(lines)
  local reason, inside = {}, false
  for _, line in ipairs(lines) do
    if line:match("^%* What went wrong:") then
      inside = true
    elseif inside and line:match("^%* ") then
      break
    elseif inside then
      local text = line:match("^> (.*)$") or (#reason > 0 and line:match("^%s+(%S.*)$"))
      if text then table.insert(reason, text) end
    end
  end
  if #reason > 0 then
    return reason
  end
  for _, line in ipairs(lines) do
    if line:match("%S") then table.insert(reason, line) end
  end
  return reason
end

-- The JSON object in text as plain tables, or nil and the reason
-- that decode gave, or nil when text holds no object
local function object(api, text)
  local decoded, value = pcall(api.decode, text)
  value = decoded and plain(value) or value
  if decoded and type(value) == "table" then return value end
  return nil, not decoded and tostring(value) or nil
end

-- Runs task in the clone and calls done(value) with what it wrote to
-- file under resource-pack/build, or done(nil, lines) with the
-- reason. api holds Process, windows, join, read and decode. Returns
-- what pack.run returns.
local function written(bootstrap, api, task, name, done)
  local process, problem = pack.run(bootstrap, api, task, {}, function(ok, err)
    if not ok then
      done(nil, err)
      return
    end
    local file = api.join(bootstrap.clone, "resource-pack", "build", name)
    local value, reason = object(api, api.read(file) or "")
    if not value then
      done(nil, { reason or file })
      return
    end
    done(value)
  end)
  return process, problem
end

-- Runs describePack and calls done(model) or done(nil, lines)
function pack.describe(bootstrap, api, done)
  local process, problem = written(bootstrap, api, "describePack", "pack-model.json", done)
  return process, problem
end

-- Runs listVanillaTextures and calls done(textures) with the textures
-- of the client jar, each { id, width, height, mcmeta }, or done(nil,
-- lines)
function pack.vanilla(bootstrap, api, done)
  local process, problem = written(bootstrap, api, "listVanillaTextures", "vanilla-textures.json", function(listing, err)
    done(listing and (listing.textures or {}), err)
  end)
  return process, problem
end

-- Runs readItem for the item id and calls done(read) with the line
-- of JSON it printed last, or done(nil, lines) with the reason. api
-- holds Process, windows, join and decode.
function pack.item(bootstrap, api, id, done)
  local process, problem = pack.run(bootstrap, api, "readItem", { item=id }, function(ok, err, out)
    if not ok then
      done(nil, err)
      return
    end
    local value, reason = object(api, out[#out] or "")
    if not value then
      done(nil, { reason or tostring(out[#out]) })
      return
    end
    done(value)
  end)
  return process, problem
end

-- Whether the normalized path lies in the folder dir
local function under(path, dir)
  local after = path:sub(#dir + 1, #dir + 1)
  return path:sub(1, #dir) == dir and (after == "/" or after == "\\")
end

-- The absolute paths { png, aseprite } of a texture of the model, or
-- nil and the path that lies outside its folder: a PNG outside
-- resource-pack/src, a source outside resource-pack/art. join
-- normalizes, so .. in a path of the model counts.
function pack.paths(clone, texture, join)
  local paths = {
    png=join(clone, "resource-pack", texture.png),
    aseprite=join(clone, "resource-pack", texture.aseprite),
  }
  if not under(paths.png, join(clone, "resource-pack", "src")) then
    return nil, paths.png
  end
  if not under(paths.aseprite, join(clone, "resource-pack", "art")) then
    return nil, paths.aseprite
  end
  return paths
end

return pack
