-- The app trusts the plugin's calls only while it sees their whole
-- stack, and a tail call drops its caller from it
describe("the plugin's sources", function()
  it("make no tail call", function()
    local calls = {}
    for _, file in ipairs({ "init.lua", "language.lua", "pack.lua", "panel.lua", "properties.lua", "tab.lua",
                            "textures.lua", "tree.lua", "wizard.lua", "drafts.lua", "minecraft.lua" }) do
      local n = 0
      for line in io.lines(file) do
        n = n + 1
        -- A call that is the whole returned value, on one line or
        -- continued on the next
        local callee, args, rest = line:match("return%s+([%w_%.:]+)%s*(%(.*)$")
        if callee and callee ~= "function" then
          local balanced = args:match("^%b()")
          rest = balanced and args:sub(#balanced + 1) or ""
          if not balanced or rest:match("^%s*$") or rest:match("^%s*end%s*$") then
            table.insert(calls, file .. ":" .. n)
          end
        end
      end
    end
    assert.same({}, calls)
  end)
end)
