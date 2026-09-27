import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { runInThisContext } from "node:vm"

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { asElement } from "@/lib/test-elements"

/** The inline script in `index.html` that reports a failed startup, run as it ships rather than as a copy. */

const indexHtml = readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../index.html"), "utf8")

/** The one inline, non-module `<script>`; a `type="module"` one never matches. */
function inlineScriptSource(): string {
  const match = indexHtml.match(/<script>([\s\S]*?)<\/script>/)
  if (!match) {
    throw new Error("index.html has no plain inline <script> for this test to run")
  }
  return match[1]
}

function runInlineScript(): void {
  /** Run in the global context, as a browser runs an inline script. */
  runInThisContext(inlineScriptSource())
}

function message(): string | null {
  return document.getElementById("startup-fallback-message")?.textContent ?? null
}

function fireResourceError(element: HTMLElement): void {
  document.head.appendChild(element)
  element.dispatchEvent(new Event("error"))
}

describe("index.html's inline startup reporter", () => {
  let reload: ReturnType<typeof vi.fn>

  beforeEach(() => {
    window.sessionStorage.clear()
    document.body.innerHTML =
      '<div id="startup-fallback"><p id="startup-fallback-message">Steward has not started yet.</p></div>'
    reload = vi.fn<() => void>()
    /** jsdom's `reload()` does nothing, so it is replaced to record whether it was asked for. */
    Object.defineProperty(window, "location", {
      value: { reload },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    window.sessionStorage.clear()
  })

  it("reloads once, silently, when the module bundle fails to load", () => {
    runInlineScript()
    const script = document.createElement("script")
    script.src = "http://localhost/assets/index-deadbeef.js"
    fireResourceError(script)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(window.sessionStorage.getItem("steward:reloaded-after-startup-error")).toBe("1")
    // The silent path: nothing is written over the sentence already there.
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

    /** A fresh run of the same script stands in for the reload, on the state the first left behind. */
    const second = document.createElement("script")
    second.src = "http://localhost/assets/index-deadbeef.js"
    fireResourceError(second)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(message()).toContain("even after reloading once")
  })

  it("ignores a failed icon or manifest - cosmetic, and not what this guards against", () => {
    runInlineScript()
    const icon = document.createElement("link")
    icon.rel = "icon"
    icon.href = "http://localhost/icon.png"
    fireResourceError(icon)

    expect(reload).not.toHaveBeenCalled()
    expect(message()).toBe("Steward has not started yet.")
  })

  it("reveals the panel the moment it has something to say, without waiting out the fade-in", () => {
    /** The reporter reveals the panel itself, since an animation that never runs would leave it hidden. */
    runInlineScript()
    const panel = asElement(document.getElementById("startup-fallback"))
    expect(panel.style.opacity).toBe("")

    const event = new ErrorEvent("error", { message: "something inside React threw" })
    window.dispatchEvent(event)

    expect(panel.style.opacity).toBe("1")
    expect(panel.style.animation).toBe("none")
  })

  it("reports a thrown error from a bundle that did load, without reloading over it", () => {
    runInlineScript()
    const event = new ErrorEvent("error", { message: "something inside React threw" })
    window.dispatchEvent(event)

    expect(reload).not.toHaveBeenCalled()
    expect(message()).toBe("Steward hit an error before it could start: something inside React threw.")
  })

  it("reports an unhandled promise rejection the same way", () => {
    runInlineScript()
    const event = new PromiseRejectionEvent("unhandledrejection", {
      promise: Promise.resolve(),
      reason: new Error("a query rejected"),
    })
    window.dispatchEvent(event)

    expect(message()).toBe("Steward hit an error before it could start: a query rejected.")
  })
})
