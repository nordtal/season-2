local sounds = require("sounds")

local function tr(id, ...) return table.concat({ id, ... }, " ") end

local ping = { id="nordtal:ui.ping", subtitle="subtitles.nordtal.ui.ping", subtitles={ en_us="Ping", fr_fr="Bip" },
               sounds={ { name="nordtal:ui/ping", file="src/assets/nordtal/sounds/ui/ping.ogg", volume=0.5, stream=true },
                        { name="nordtal:ui.other", type="event" } } }
local anvil = { id="minecraft:block.anvil.land", sounds={ { name="minecraft:random/anvil_land" } } }

local function event(e) return { kind="sound", entry=e, languages={ "de_de", "en_us" } } end
local function sound(e, at) return { kind="sound_file", entry=e.sounds[at + 1], event=e, at=at } end

-- The rows as "key field: value"
local function lines(rows)
  local out = {}
  for _, row in ipairs(rows) do
    table.insert(out, row.key .. " " .. tostring(row.field) .. ": " .. row.value .. (row.button and " [button]" or ""))
  end
  return out
end

describe("sounds", function()
  it("handles the sounds root, an event and each of its sounds", function()
    assert.is_true(sounds.handles({ kind="sounds" }))
    assert.is_true(sounds.handles(event(ping)))
    assert.is_true(sounds.handles(sound(ping, 0)))
    assert.is_false(sounds.handles({ kind="item" }))
    assert.is_false(sounds.handles(nil))
  end)

  it("offers a new sound event on the root", function()
    assert.same({ "new newSound: sounds.new [button]" }, lines(sounds.rows({ kind="sounds" }, tr)))
  end)

  it("shows an event's subtitle in each of the pack's languages and any other it has, then its actions", function()
    assert.same({
      "event nil: nordtal:ui.ping",
      "subtitle subtitle:de_de: sounds.no_subtitle",
      "subtitle subtitle:en_us: Ping",
      "subtitle subtitle:fr_fr: Bip",
      "key nil: subtitles.nordtal.ui.ping",
      "count nil: sounds.count 2",
      "play play: sounds.play [button]",
      "add_file addFile: sounds.add_file [button]",
      "add_name addName: sounds.add_name [button]",
      "delete delete: sounds.delete",
    }, lines(sounds.rows(event(ping), tr)))
    -- The count's label is no count
    assert.equal("sounds.files", sounds.rows(event(ping), tr)[6].label)
  end)

  it("shows whether a vanilla event replaces vanilla's sounds, and its subtitle only where it replaces", function()
    assert.same({
      "event nil: minecraft:block.anvil.land",
      "replace replace: sounds.keeps",
      "count nil: sounds.count_one",
      "play play: sounds.play [button]",
      "add_file addFile: sounds.add_file [button]",
      "add_name addName: sounds.add_name [button]",
      "delete delete: sounds.delete",
    }, lines(sounds.rows(event(anvil), tr)))
    local replacing = { id=anvil.id, replace=true, sounds=anvil.sounds }
    local rows = lines(sounds.rows(event(replacing), tr))
    assert.equal("replace replace: sounds.replaces", rows[2])
    assert.equal("subtitle subtitle:de_de: sounds.no_subtitle", rows[3])
  end)

  it("shows a sound's file and fields, with Minecraft's defaults for those it leaves out", function()
    assert.same({
      "name nil: nordtal:ui/ping",
      "file nil: src/assets/nordtal/sounds/ui/ping.ogg",
      "volume field:volume: 0.5",
      "pitch field:pitch: sounds.default 1.0",
      "weight field:weight: sounds.default 1",
      "stream field:stream: sounds.on",
      "play play: sounds.play [button]",
      "remove remove: sounds.remove [button]",
    }, lines(sounds.rows(sound(ping, 0), tr)))
    assert.equal("file nil: sounds.of_event", lines(sounds.rows(sound(ping, 1), tr))[2])
    assert.equal("file nil: sounds.of_minecraft", lines(sounds.rows(sound(anvil, 0), tr))[2])
  end)

  it("shows a fraction as it is where math.tointeger floors it, as in Aseprite", function()
    local tointeger = math.tointeger
    math.tointeger = function(n) return math.floor(n) end
    local rows = lines(sounds.rows(sound({ id="nordtal:ui.ping", sounds={ { name="a", volume=0.6, weight=2.0 } } }, 0), tr))
    math.tointeger = tointeger
    assert.equal("volume field:volume: 0.6", rows[3])
    assert.equal("weight field:weight: 2", rows[5])
  end)

  it("asks whether to delete an event and says what goes with it", function()
    assert.same({ "sounds.delete_question nordtal:ui.ping", "sounds.delete_note" }, sounds.confirm(event(ping), tr))
    assert.same({ "sounds.delete_question minecraft:block.anvil.land", "sounds.delete_vanilla_note" },
      sounds.confirm(sound(anvil, 0), tr))
  end)

  it("asks for a subtitle in one language, and deletes it with an empty text", function()
    local form = sounds.form(event(ping), "subtitle:en_us", tr)
    assert.equal("sounds.subtitle_of nordtal:ui.ping en_us", form.title)
    assert.same({ kind="entry", id="value", label="en_us", text="Ping" }, form.widgets[1])
    assert.same({ "subtitleSound", { sound="nordtal:ui.ping", language="de_de", value="Pling" } },
      sounds.jobs(event(ping), "subtitle:de_de", { value="Pling" }))
    -- Another language still has a text, so only this one is emptied
    assert.same({ "subtitleSound", { sound="nordtal:ui.ping", language="en_us", value="" } },
      sounds.jobs(event(ping), "subtitle:en_us", { value="" }))
    local alone = { id=ping.id, subtitles={ en_us="Ping", de_de="" }, sounds=ping.sounds }
    assert.same({ "subtitleSound", { sound="nordtal:ui.ping", delete=true } },
      sounds.jobs(event(alone), "subtitle:en_us", { value="" }))
    assert.is_nil(sounds.jobs(event(ping), "subtitle:en_us", { value="Ping" }))
    assert.is_nil(sounds.jobs(event(ping), "subtitle:de_de", { value="" }))
  end)

  it("switches whether an override replaces vanilla's sounds", function()
    local form = sounds.form(event(anvil), "replace", tr)
    assert.same({ kind="check", id="replace", text="sounds.replace_check", selected=false }, form.widgets[1])
    assert.same({ "editSound", { sound=anvil.id, replace=true } }, sounds.jobs(event(anvil), "replace", { replace=true }))
    assert.is_nil(sounds.jobs(event(anvil), "replace", { replace=false }))
  end)

  it("adds a sound from an audio file or by name, and removes one", function()
    local form = sounds.form(event(ping), "addFile", tr)
    assert.same({ kind="file", id="file", label="sounds.file", open=true, filetypes=sounds.filetypes }, form.widgets[1])
    assert.equal("ogg", sounds.filetypes[1])
    assert.same({ kind="label", id="note", text="sounds.ogg_note" }, form.widgets[2])
    assert.same({ "editSound", { sound=ping.id, file="/tmp/a.ogg" } }, sounds.jobs(event(ping), "addFile", { file="/tmp/a.ogg" }))
    assert.is_nil(sounds.jobs(event(ping), "addFile", { file="" }))
    assert.same({ "editSound", { sound=ping.id, name="random/click" } },
      sounds.jobs(event(ping), "addName", { name=" random/click " }))
    assert.is_nil(sounds.jobs(event(ping), "addName", { name=" " }))
    assert.equal("sounds.remove_confirm nordtal:ui/ping", sounds.form(sound(ping, 0), "remove", tr).widgets[1].text)
    assert.same({ "editSound", { sound=ping.id, at=1, remove=true } }, sounds.jobs(sound(ping, 1), "remove", {}))
    assert.same({ "deleteSound", { sound=ping.id } }, sounds.jobs(sound(ping, 1), "delete", {}))
  end)

  it("writes a field as JSON: a number as typed, null for an empty box, and a text the task refuses", function()
    local form = sounds.form(sound(ping, 0), "field:volume", tr)
    assert.equal("sounds.volume", form.title)
    assert.same({ kind="entry", id="value", label="sounds.volume", text="0.5" }, form.widgets[1])
    assert.equal("sounds.volume_note", form.widgets[2].text)
    assert.equal("", sounds.form(sound(ping, 0), "field:pitch", tr).widgets[1].text)
    local function fields(field, value) return sounds.jobs(sound(ping, 0), field, { value=value }) end
    assert.same({ "editSound", { sound=ping.id, at=0, fields='{"volume":0.8}' } }, fields("field:volume", "0.8"))
    assert.equal('{"pitch":1.5}', fields("field:pitch", " 1,5 ")[2].fields)
    assert.equal('{"volume":null}', fields("field:volume", "")[2].fields)
    assert.equal('{"weight":"lots \\"of\\""}', fields("field:weight", 'lots "of"')[2].fields)
    assert.is_nil(fields("field:volume", "0.5"))
    assert.is_nil(fields("field:pitch", ""))
    assert.equal('{"volume":0.25}', fields("field:volume", ".25")[2].fields)
    assert.equal('{"weight":5}', fields("field:weight", "5.")[2].fields)
    assert.equal('{"pitch":"0x10"}', fields("field:pitch", "0x10")[2].fields)
    assert.equal('{"volume":"1e3"}', fields("field:volume", "1e3")[2].fields)
  end)

  it("streams a sound with a check, and clears the field once it is off", function()
    local form = sounds.form(sound(ping, 0), "field:stream", tr)
    assert.same({ kind="check", id="value", text="sounds.stream_check", selected=true }, form.widgets[1])
    assert.equal('{"stream":null}', sounds.jobs(sound(ping, 0), "field:stream", { value=false })[2].fields)
    assert.is_nil(sounds.jobs(sound(ping, 0), "field:stream", { value=true }))
    assert.equal('{"stream":true}', sounds.jobs(sound(anvil, 0), "field:stream", { value=true })[2].fields)
    local off = { id=ping.id, sounds={ { name="nordtal:ui/ping", stream=false } } }
    assert.equal('{"stream":true}', sounds.jobs(sound(off, 0), "field:stream", { value=true })[2].fields)
  end)

  it("plays an event's first sound, or the sound chosen", function()
    assert.same({ sound=ping.id }, sounds.play(event(ping)))
    assert.same({ sound=ping.id, at=1 }, sounds.play(sound(ping, 1)))
  end)

  it("plays the WAV with the system's own player, and on Linux with PulseAudio's or else ALSA's", function()
    assert.same({ { "afplay", "/c/a.wav" } }, sounds.players("macos", "/c/a.wav"))
    assert.same({ { "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(New-Object Media.SoundPlayer 'C:\\it''s\\a.wav').PlaySync()" } },
      sounds.players("windows", "C:\\it's\\a.wav"))
    assert.same({ { "paplay", "/c/a.wav" }, { "aplay", "-q", "/c/a.wav" } }, sounds.players("linux", "/c/a.wav"))
  end)
end)
