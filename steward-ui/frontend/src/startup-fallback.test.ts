import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

/**
 * The inline script in `index.html` that reports a startup that never happened (steward/79).
 *
 * It is deliberately not a module under `src/` - a module is one more file the bundle can fail to
 * fetch, and the whole point of this script is to still run when the bundle has. So it is tested
 * the way it ships: extracted out of the real `index.html` and executed as-is, rather than as a
 * copy kept in step by hand. A copy here is exactly the drift `NothingIsGermanTest`'s own history
 * warns about - the day this test and the markup disagree, this reads the file that is wrong.
 */

const indexHtml = readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../index.html"), "utf8")

/** The one inline, non-module `<script>` - `<script type="module" ...>` never matches this. */
function inlineScriptSource(): string {
  const match = indexHtml.match(/<script>([\s\S]*?)<\/script>/)
  if (!match) {
    throw new Error("index.html has no plain inline <script> for this test to run")
  }
  return match[1]
}

function runInlineScript(): void {
  // eslint-disable-next-line no-new-func -- this is the real script, not a rewrite of it.
  new Function(inlineScriptSource())()
}

describe("index.html's inline startup reporter", () => {
  let reload: ReturnType<typeof vi.fn>

  beforeEach(() => {
    window.sessionStorage.clear()
    document.body.innerHTML =
      '<div id="startup-fallback">' + '<p id="startup-fallback-message">Steward has not started yet.</p>' + "</div>"
    reload = vi.fn()
    // jsdom's own reload() logs "Not implemented: navigation" and does nothing - replaced so a
    // test can tell whether it was asked for at all.
    Object.defineProperty(window, "location", {
      value: { ...window.location, reload },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    window.sessionStorage.clear()
  })

  function message(): string | null {
    return document.getElementById("startup-fallback-message")?.textContent ?? null
  }

  function fireResourceError(element: HTMLElement): void {
    document.head.appendChild(element)
    element.dispatchEvent(new Event("error"))
  }

  it("reloads once, silently, when the module bundle fails to load", () => {
    runInlineScript()
    const script = document.createElement("script")
    script.src = "http://localhost/assets/index-deadbeef.js"
    fireResourceError(script)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(window.sessionStorage.getItem("steward:reloaded-after-startup-error")).toBe("1")
    // The silent path: nothing is written over the plain sentence that is already there.
    expect(message()).toBe("Steward has not started yet.")
  })

  it("reloads once for a missing stylesheet the same way as for a missing script", () => {
    runInlineScript()
    const link = document.createElement("link")
    link.rel = "stylesheet"
    link.href = "http://localhost/assets/index-deadbeef.css"
    fireResourceError(link)

    expect(reload).toHaveBeenCalledTimes(1)
  })

  it("gives up and says so once a reload has already been tried this session", () => {
    window.sessionStorage.setItem("steward:reloaded-after-startup-error", "1")
    runInlineScript()
    const script = document.createElement("script")
    script.src = "http://localhost/assets/index-deadbeef.js"
    fireResourceError(script)

    expect(reload).not.toHaveBeenCalled()
    expect(message()).toContain("even after reloading once")
  })

  it("never loops: a second failure after the reload does not ask for a third one", () => {
    runInlineScript()
    const first = document.createElement("script")
    first.src = "http://localhost/assets/index-deadbeef.js"
    fireResourceError(first)
    expect(reload).toHaveBeenCalledTimes(1)

    // The reload itself is what a real browser would do here; this test stands in a fresh
    // execution of the same inline script for it, on the document state the first run left behind.
    const second = document.createElement("script")
    second.src = "http://localhost/assets/index-deadbeef.js"
    fireResourceError(second)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(message()).toContain("even after reloading once")
  })

  it("ignores a failed icon or manifest - cosmetic, and not what steward/79 is about", () => {
    runInlineScript()
    const icon = document.createElement("link")
    icon.rel = "icon"
    icon.href = "http://localhost/icon.png"
    fireResourceError(icon)

    expect(reload).not.toHaveBeenCalled()
    expect(message()).toBe("Steward has not started yet.")
  })

  it("reveals the panel the moment it has something to say, without waiting out the fade-in", () => {
    // The panel is hidden by a CSS delay so a healthy start never flashes it (index.html's own
    // <style>). An animation that never runs would leave it hidden forever, and hidden is the one
    // failure direction that matters here - so the reporter reveals it directly rather than
    // trusting the animation it cannot see.
    runInlineScript()
    const panel = document.getElementById("startup-fallback") as HTMLElement
    expect(panel.style.opacity).toBe("")

    const event = new Event("error") as ErrorEvent
    Object.defineProperty(event, "message", { value: "something inside React threw" })
    window.dispatchEvent(event)

    expect(panel.style.opacity).toBe("1")
    expect(panel.style.animation).toBe("none")
  })

  it("reports a thrown error from a bundle that did load, without reloading over it", () => {
    runInlineScript()
    const event = new Event("error") as ErrorEvent
    Object.defineProperty(event, "message", { value: "something inside React threw" })
    window.dispatchEvent(event)

    expect(reload).not.toHaveBeenCalled()
    expect(message()).toBe("Steward hit an error before it could start: something inside React threw.")
  })

  it("reports an unhandled promise rejection the same way", () => {
    runInlineScript()
    const event = new Event("unhandledrejection") as PromiseRejectionEvent
    Object.defineProperty(event, "reason", { value: new Error("a query rejected") })
    window.dispatchEvent(event)

    expect(message()).toBe("Steward hit an error before it could start: a query rejected.")
  })
})
