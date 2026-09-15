import { describe, expect, it } from "vitest"

import { isStandalone, measuredHeight, trackAppFrame } from "@/lib/app-frame"

/**
 * The two numbers the shell cannot ask CSS for.
 *
 * What is worth testing here is not "it reads `visualViewport.height`" - that is one line and
 * nothing would be learned. It is the two moments where reading it is wrong: a soft keyboard and a
 * pinch both shrink the visual viewport, and a shell that took either would shrink and stay
 * shrunk. Both come back as `null`, which the tracker spends by keeping the last good number.
 */

function view(over: Partial<{ innerHeight: number; visual: { height: number; scale: number } | null }> = {}) {
  return {
    innerHeight: over.innerHeight ?? 844,
    visualViewport: over.visual === undefined ? { height: 800, scale: 1 } : over.visual,
  }
}

describe("measuredHeight - the window, and the moments that are not the window", () => {
  it("is the visible viewport, which is the one the home screen gets wrong", () => {
    expect(measuredHeight(view(), null)).toBe(800)
  })

  it("falls back to innerHeight where there is no visualViewport", () => {
    expect(measuredHeight(view({ visual: null }), null)).toBe(844)
  })

  it("refuses to measure while a text field has focus - that is the keyboard, not the window", () => {
    const input = document.createElement("input")
    expect(measuredHeight(view({ visual: { height: 420, scale: 1 } }), input)).toBeNull()
  })

  it("refuses the same for a textarea and for anything contenteditable", () => {
    const textarea = document.createElement("textarea")
    expect(measuredHeight(view(), textarea)).toBeNull()

    const editable = document.createElement("div")
    editable.contentEditable = "true"
    // jsdom does not implement isContentEditable off the attribute, so it is set directly.
    Object.defineProperty(editable, "isContentEditable", { value: true })
    expect(measuredHeight(view(), editable)).toBeNull()
  })

  it("measures normally when the focused element is not editable", () => {
    expect(measuredHeight(view(), document.createElement("button"))).toBe(800)
  })

  it("refuses to measure while the page is pinched", () => {
    expect(measuredHeight(view({ visual: { height: 300, scale: 2.5 } }), null)).toBeNull()
  })

  it("treats a zero height as no measurement rather than as a window of no height", () => {
    expect(measuredHeight(view({ visual: { height: 0, scale: 1 } }), null)).toBeNull()
    expect(measuredHeight(view({ innerHeight: 0, visual: null }), null)).toBeNull()
  })
})

describe("trackAppFrame - what lands on the document", () => {
  it("publishes the measurement and keeps the last good one when a measurement is refused", () => {
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    // A pinch: the property must not follow it down.
    visual.scale = 2
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    // Let go of the pinch at a new height, and it follows again.
    visual.scale = 1
    visual.height = 640
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("640px")

    stop()
    visual.height = 100
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("640px")
  })

  it("catches up when the field is left, so a rotation during typing is not kept for ever", () => {
    // The gap this closes: `measuredHeight` says no while a field has focus, and `resize` has
    // already been and gone by the time it is let go. Without a second event the shell keeps a
    // height from before the rotation and nothing ever corrects it.
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })
    const field = document.createElement("input")
    document.body.append(field)

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    field.focus()
    visual.height = 500
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    field.blur()
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("500px")

    stop()
    field.remove()
  })

  it("re-measures when the app comes back, because a resume sends no resize", () => {
    // STEWARD/51, AND THE WHOLE POINT IS THE EVENT THAT DOES NOT ARRIVE. A home-screen app restored
    // after a long pause has the height it was frozen with. `visual.height` is changed here without
    // any `resize` being dispatched - that is not a shortcut, it is the bug: on a resume iOS sends
    // neither `resize` nor a `visualViewport` event, so the shell keeps a wrong number until the
    // page is dragged by hand. Each of the three resume events has to be enough on its own.
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    visual.height = 700
    window.dispatchEvent(new Event("pageshow"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("700px")

    visual.height = 600
    document.dispatchEvent(new Event("visibilitychange"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("600px")

    visual.height = 500
    window.dispatchEvent(new Event("focus"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("500px")

    stop()
    visual.height = 400
    window.dispatchEvent(new Event("pageshow"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("500px")
  })

  it("gives the blurred band no clearance in a browser, which is where there is no band", () => {
    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--blur-clearance")).toBe("0px")
    stop()
  })

  it("gives it clearance on a home screen, found either way iOS answers", () => {
    Object.defineProperty(window.navigator, "standalone", { value: true, configurable: true })
    expect(isStandalone(window)).toBe(true)

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--blur-clearance")).toBe("8px")
    stop()

    Object.defineProperty(window.navigator, "standalone", { value: undefined, configurable: true })
  })
})
