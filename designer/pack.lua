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

-- Runs describePack in the clone and calls done(model) or done(nil,
-- lines) with the reason. api holds Process, windows, join, read and
-- decode.
function pack.describe(bootstrap, api, done)
  local clone = bootstrap.clone
  local cmd
  if api.windows then
    cmd = { api.join(clone, "gradlew.bat") }
  else
    cmd = { "/bin/sh", api.join(clone, "gradlew") }
  end
  table.insert(cmd, "-q")
  table.insert(cmd, ":resource-pack:describePack")

  local err = {}
  local process = api.Process{
    cmd=cmd,
    cwd=clone,
    env=bootstrap.env,
    onstderr=function(line) table.insert(err, line) end,
    onexit=function(code)
      if code ~= 0 then
        done(nil, err)
        return
      end
      local file = api.join(clone, "resource-pack", "build", "pack-model.json")
      local ok, model = pcall(api.decode, api.read(file) or "")
      model = ok and plain(model) or model
      if not ok or type(model) ~= "table" then
        done(nil, { ok and file or tostring(model) })
        return
      end
      done(model)
    end }
  process:start()
  return process
end

-- The absolute paths { png, aseprite } of a texture of the model
function pack.paths(clone, texture, join)
  return {
    png=join(clone, "resource-pack", texture.png),
    aseprite=join(clone, "resource-pack", texture.aseprite),
  }
end

return pack
