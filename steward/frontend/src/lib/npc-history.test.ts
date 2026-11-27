import { afterEach, describe, expect, it } from "vitest"

import { isCanvasShortcutTarget } from "@/lib/npc-history"

describe("canvas keyboard shortcut targets", () => {
  afterEach(() => {
    document.body.innerHTML = ""
  })

  it("allows shortcuts on document body and canvas nodes, and blocks interactive controls", () => {
    document.body.innerHTML = `
      <header>
        <button id="head-btn" type="button">Versions</button>
      </header>
      <div class="npc-flow">
        <div data-canvas-toolbar>
          <button id="toolbar-btn" type="button">Fit</button>
        </div>
        <div class="react-flow__pane" id="pane">
          <div class="react-flow__node" id="node">
            <button id="node-btn" type="button">Add</button>
          </div>
        </div>
      </div>
      <aside>
        <div id="aside-div">
          <button id="picker" role="combobox" type="button">Then</button>
        </div>
      </aside>
    `
    expect(isCanvasShortcutTarget(document.body)).toBe(true)
    expect(isCanvasShortcutTarget(document.getElementById("pane"))).toBe(true)
    expect(isCanvasShortcutTarget(document.getElementById("node"))).toBe(true)

    expect(isCanvasShortcutTarget(document.getElementById("head-btn"))).toBe(false)
    expect(isCanvasShortcutTarget(document.getElementById("toolbar-btn"))).toBe(false)
    expect(isCanvasShortcutTarget(document.getElementById("node-btn"))).toBe(false)
    expect(isCanvasShortcutTarget(document.getElementById("picker"))).toBe(false)
    expect(isCanvasShortcutTarget(document.getElementById("aside-div"))).toBe(false)
  })
})
