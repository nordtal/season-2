local reveal = require("reveal")

describe("reveal", function()
  local texture = { id="nordtal:badges/star.png", png="src/assets/nordtal/textures/badges/star.png" }
  local context = {
    language="de_de",
    namespaces={ "minecraft", "nordtal" },
    item=function(id) return id == "nordtal:lamp" and { id=id, file="src/assets/nordtal/items/lamp.json" } or nil end,
    holds=function(path, key)
      return key == "menu.title" and (path == "src/assets/nordtal/lang/de_de.json" or path == "src/assets/minecraft/lang/en_us.json")
        or key == "item.nordtal.lamp" and path == "src/assets/nordtal/lang/en_us.json"
    end,
  }

  it("gives the PNG of an entry with a texture", function()
    for _, kind in ipairs({ "glyph", "cell", "sheet", "override" }) do
      assert.equal(texture.png, reveal.file({ kind=kind, entry={}, texture=texture }, context))
    end
  end)

  it("gives glyphs.json for a font, a glyph block and a glyph without a texture", function()
    assert.equal("glyphs.json", reveal.file({ kind="font", entry={ font="minecraft:default" } }, context))
    assert.equal("glyphs.json", reveal.file({ kind="block", entry={} }, context))
    assert.equal("glyphs.json", reveal.file({ kind="glyph", entry={ code="20" } }, context))
  end)

  it("gives an item's definition, also for a part of it and for an item with a texture", function()
    local file = "src/assets/nordtal/items/lamp.json"
    assert.equal(file, reveal.file({ kind="item", entry={ id="nordtal:lamp", file=file }, texture=texture }, context))
    assert.equal(file, reveal.file({ kind="definition", item="nordtal:lamp", at="model" }, context))
    assert.is_nil(reveal.file({ kind="definition", item="nordtal:gone", at="model" }, context))
  end)

  it("gives the language file that holds a text, the designer's language first, then English", function()
    assert.equal("src/assets/nordtal/lang/de_de.json",
      reveal.file({ kind="text", entry={ key="menu.title" }, languages={ "de_de", "en_us" } }, context))
    assert.equal("src/assets/nordtal/lang/en_us.json",
      reveal.file({ kind="text", entry={ key="item.nordtal.lamp" }, languages={ "de_de", "en_us" } }, context))
  end)

  it("tries another region of the designer's language before English", function()
    local pt = { language="pt_pt", namespaces={ "minecraft" },
                 holds=function(path) return path == "src/assets/minecraft/lang/pt_br.json"
                                          or path == "src/assets/minecraft/lang/en_us.json" end }
    assert.equal("src/assets/minecraft/lang/pt_br.json",
      reveal.file({ kind="text", entry={ key="menu.title" }, languages={ "en_us", "pt_br" } }, pt))
  end)

  it("gives the folder of new language files for a text that no file holds", function()
    assert.equal("src/assets/minecraft/lang", reveal.file({ kind="text", entry={ key="gone" } }, context))
  end)

  it("gives the sound's file, else the sounds.json of its event's namespace", function()
    local event = { id="minecraft:entity.villager.ambient" }
    assert.equal("src/assets/minecraft/sounds.json", reveal.file({ kind="sound", entry=event }, context))
    assert.equal("src/assets/minecraft/sounds.json",
      reveal.file({ kind="sound_file", entry={ name="minecraft:mob/villager/idle1" }, event=event }, context))
    assert.equal("src/assets/nordtal/sounds/ui/ping.ogg",
      reveal.file({ kind="sound_file", entry={ name="nordtal:ui/ping", file="src/assets/nordtal/sounds/ui/ping.ogg" },
                    event={ id="nordtal:ui.ping" } }, context))
  end)

  it("offers nothing for a root, a category or no entry", function()
    for _, kind in ipairs({ "root", "category", "texts", "sounds" }) do
      assert.is_nil(reveal.file({ kind=kind, entry={} }, context))
      assert.is_false(reveal.offered({ kind=kind, entry={} }, context.item))
    end
    assert.is_nil(reveal.file(nil, context))
    assert.is_true(reveal.offered({ kind="text", entry={ key="gone" } }, context.item))
  end)

  describe("command", function()
    local files = { ["/p/a/b.png"]=true }
    local dirs = { ["/p"]=true, ["/p/a"]=true }
    local fs = {
      isFile=function(path) return files[path] == true end,
      isDirectory=function(path) return dirs[path] == true end,
      folderOf=function(path) return path:match("^(.*)/[^/]*$") or "" end,
    }

    it("selects a file in Finder and Explorer, and opens its folder on Linux", function()
      assert.same({ "/usr/bin/open", "-R", "/p/a/b.png" }, reveal.command("macos", "/p/a/b.png", fs))
      assert.same({ "explorer", "/select,", "/p/a/b.png" }, reveal.command("windows", "/p/a/b.png", fs))
      assert.same({ "xdg-open", "/p/a" }, reveal.command("linux", "/p/a/b.png", fs))
    end)

    it("opens a folder as it is, and the nearest folder above a path that is missing", function()
      assert.same({ "/usr/bin/open", "/p/a" }, reveal.command("macos", "/p/a", fs))
      assert.same({ "explorer", "/p" }, reveal.command("windows", "/p/gone/c.png", fs))
      assert.same({ "xdg-open", "/p/a" }, reveal.command("linux", "/p/a/gone.png", fs))
    end)
  end)

  it("names the language file of the app's language", function()
    assert.equal("de_de", reveal.language("de"))
    assert.equal("en_us", reveal.language("en"))
    assert.is_nil(reveal.language(nil))
  end)
end)
