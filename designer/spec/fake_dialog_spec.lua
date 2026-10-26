local fake = require("spec.fake_dialog")

describe("the fake Dialog", function()
  it("refuses a side panel on a side other than left or right, as the fork's API does", function()
    local Dialog = fake()
    assert.has_error(function() Dialog{ tab=true }:panel{ id="side", side="top" } end)
    local dlg = Dialog{ tab=true }:panel{ id="side", side="right" }
    assert.has_error(function() dlg:modify{ id="side", side="top" } end)
  end)

  it("refuses a splitter outside a side panel and a second one, as the fork's API does", function()
    local Dialog = fake()
    assert.has_error(function() Dialog{ tab=true }:splitter{} end)
    local dlg = Dialog{ tab=true }:panel{ id="side" }:splitter{}
    assert.has_error(function() dlg:splitter{} end)
  end)
end)
