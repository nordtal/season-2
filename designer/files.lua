-- The files of the pack that no other part of the tree shows, such as
-- models, block states and shaders, in the properties panel: the rows
-- of a file and of Files, what a delete confirms and the tasks of the
-- resource pack that write a change. A file is edited as text in the
-- system's text editor, and describePack checks it by the rules of its
-- folder, which stay in that task.

local files = {}

-- Whether the panel of info is this module's
function files.handles(info)
  local kind = info and info.kind
  return kind == "files" or kind == "file"
end

-- The rows of info as properties.rows gives them, each with its label.
-- A row whose label is false continues the row above it.
function files.rows(info, tr)
  local list = {}
  local function button(key, field)
    table.insert(list, { key=key, label=tr("files." .. key), value=tr("files." .. key), field=field, button=true })
  end
  if info.kind == "files" then
    button("new", "newFile")
    return list
  end
  local e = info.entry
  table.insert(list, { key="file", label=tr("files.file"), value=e.file })
  table.insert(list, { key="kind", label=tr("files.kind"), value=tr("files.kind_" .. tostring(e.kind or "file")) })
  local problems = e.problems or {}
  if #problems == 0 then
    table.insert(list, { key="check", label=tr("files.check"), value=tr("files.no_problems") })
  else
    for i, problem in ipairs(problems) do
      table.insert(list, { key="check", label=i == 1 and tr("files.problems") or false, value=tostring(problem) })
    end
  end
  button("edit", "edit")
  table.insert(list, { key="delete", value=tr("files.delete"), field="delete" })
  return list
end

-- The lines that confirm the delete of the file of info
function files.confirm(info, tr)
  local lines = { tr("files.delete_question", info.entry.id) }
  if tostring(info.entry.id):find("^minecraft:") then
    table.insert(lines, tr("files.delete_vanilla_note"))
  end
  return lines
end

-- The task that writes field of the file of info, or nil
function files.jobs(info, field)
  if field == "delete" and info.kind == "file" then
    return { "deleteFile", { asset=info.entry.id } }
  end
  return nil
end

-- The command that opens path in a text editor of os ("macos",
-- "windows" or "linux"): macOS's default text editor, else the
-- application the system chose for the file's type
function files.command(os, path)
  if os == "macos" then
    return { "/usr/bin/open", "-t", path }
  elseif os == "windows" then
    return { "explorer", path }
  end
  return { "xdg-open", path }
end

-- The paths of watched whose text changed since it was last read, as
-- read(path) gives it now. Each takes its new text in watched, and a
-- file that is gone is no longer watched.
function files.changed(watched, read)
  local out = {}
  for path, text in pairs(watched) do
    local now = read(path)
    if now == nil then
      watched[path] = nil
    elseif now ~= text then
      watched[path] = now
      table.insert(out, path)
    end
  end
  table.sort(out)
  return out
end

return files
