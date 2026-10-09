import { afterEach, describe, expect, it, vi } from "vitest"

import { isStandalone, measuredHeight, screenHeight, trackAppFrame, visibleBand } from "@/lib/app-frame"

/** The two moments reading the visual viewport is wrong: a keyboard and a pinch, both answered with `null`. */

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

  it("takes a measurement that is LARGER than the last one, because neither refusal can grow a window", () => {
    /** Both refusals ignore only a shrinking viewport; a bigger one is taken even while a field has focus. */
    const input = document.createElement("input")
    expect(measuredHeight(view({ visual: { height: 900, scale: 1 } }), input, 700)).toBe(900)
    expect(measuredHeight(view({ visual: { height: 900, scale: 2.5 } }), null, 700)).toBe(900)
  })

  it("still refuses a smaller one, which is the shape a keyboard and a pinch actually have", () => {
    const input = document.createElement("input")
    expect(measuredHeight(view({ visual: { height: 500, scale: 1 } }), input, 800)).toBeNull()
    expect(measuredHeight(view({ visual: { height: 500, scale: 2.5 } }), null, 800)).toBeNull()
  })

  it("treats a zero height as no measurement rather than as a window of no height", () => {
    expect(measuredHeight(view({ visual: { height: 0, scale: 1 } }), null)).toBeNull()
    expect(measuredHeight(view({ innerHeight: 0, visual: null }), null)).toBeNull()
  })
})

/** An iPhone 16 Pro's screen in points, on a home screen unless a test says otherwise. */
function phone(over: Partial<{ innerWidth: number; standalone: boolean | undefined }> = {}) {
  return {
    innerWidth: over.innerWidth ?? 402,
    screen: { width: 402, height: 874 },
    navigator: { standalone: "standalone" in over ? over.standalone : true },
  }
}

describe("screenHeight - the height iOS cannot shrink", () => {
  it("is the screen's long side for an iOS home screen app held upright", () => {
    expect(screenHeight(phone())).toBe(874)
  })

  it("is the short side once the window is as wide as the long side, since iOS never swaps screen.width", () => {
    expect(screenHeight(phone({ innerWidth: 874 }))).toBe(402)
  })

  it("is nothing in Safari or on another platform's home screen, where the screen includes bars the page lacks", () => {
    expect(screenHeight(phone({ standalone: false }))).toBeNull()
    expect(screenHeight(phone({ standalone: undefined }))).toBeNull()
  })

  it("is nothing for a window narrower than the screen, an iPad's split view, whose height is not the screen's", () => {
    expect(screenHeight(phone({ innerWidth: 320 }))).toBeNull()
  })
})

describe("trackAppFrame - what lands on the document", () => {
  // Three of these tests drive the poll with fake timers; the rest must not inherit them.
  afterEach(() => {
    vi.useRealTimers()
  })

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
    /** A field in focus refuses the `resize`, so `focusout` has to measure again. */
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

  it("puts the document back at the top once the field is left, since iOS leaves it pushed up by the keyboard", () => {
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })
    const scrollTo = vi.spyOn(window, "scrollTo").mockImplementation(() => {})
    const field = document.createElement("input")
    document.body.append(field)

    const stop = trackAppFrame(window)
    field.focus()
    Object.defineProperty(window, "scrollY", { value: 48, configurable: true })
    window.dispatchEvent(new Event("resize"))
    expect(scrollTo).not.toHaveBeenCalled()

    field.blur()
    expect(scrollTo).toHaveBeenCalledWith(0, 0)

    stop()
    field.remove()
    scrollTo.mockRestore()
    Object.defineProperty(window, "scrollY", { value: 0, configurable: true })
  })

  it("follows a window that GROWS while a field has focus, instead of staying short below it", () => {
    /** A window grown with the cursor in a field must grow the shell at once, not when the field is left. */
    const visual = { height: 700, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })
    const field = document.createElement("input")
    document.body.append(field)

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("700px")

    field.focus()
    visual.height = 1000
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("1000px")

    /** A viewport shrinking under a focused field is still the keyboard and keeps the last good number. */
    visual.height = 600
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("1000px")

    field.blur()
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("600px")

    stop()
    field.remove()
  })

  it("re-measures when the app comes back, because a resume sends no resize", () => {
    /** No `resize` is dispatched, since iOS sends none on a resume; each resume event must suffice alone. */
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

  it("re-measures on a timer while visible, because a resume can arrive with no event at all", () => {
    /** The resume events are not always enough, so the height is also polled, again without any event. */
    vi.useFakeTimers()
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    visual.height = 700
    vi.advanceTimersByTime(1000)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("700px")

    stop()
    visual.height = 600
    vi.advanceTimersByTime(5000)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("700px")
  })

  it("writes nothing while the height is unchanged, so the poll is not a style change per second", () => {
    vi.useFakeTimers()
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })

    const stop = trackAppFrame(window)
    const setProperty = vi.spyOn(document.documentElement.style, "setProperty")

    vi.advanceTimersByTime(10_000)
    expect(setProperty.mock.calls.filter(([name]) => name === "--app-height")).toEqual([])

    visual.height = 640
    vi.advanceTimersByTime(1000)
    expect(setProperty.mock.calls.filter(([name]) => name === "--app-height")).toEqual([["--app-height", "640px"]])

    setProperty.mockRestore()
    stop()
  })

  it("measures nothing and schedules nothing while the page is hidden", () => {
    /** A hidden window is not polled, since iOS throttles its timers and nobody is looking. */
    vi.useFakeTimers()
    const visual = { height: 800, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true })
    document.dispatchEvent(new Event("visibilitychange"))
    visual.height = 600
    vi.advanceTimersByTime(10_000)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("800px")

    // Coming back is the resume: at once, and the poll is running again afterwards.
    Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true })
    document.dispatchEvent(new Event("visibilitychange"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("600px")

    visual.height = 550
    vi.advanceTimersByTime(1000)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("550px")

    stop()
  })

  it("keeps a home screen app at the screen's height when iOS leaves every viewport number short", () => {
    /** WebKit's layout viewport stays short after the keyboard or a long pause, and the visual viewport agrees. */
    const visual = { height: 874, scale: 1, addEventListener() {}, removeEventListener() {} }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })
    Object.defineProperty(window, "innerWidth", { value: 402, configurable: true })
    Object.defineProperty(window, "screen", { value: { width: 402, height: 874 }, configurable: true })
    Object.defineProperty(window.navigator, "standalone", { value: true, configurable: true })

    const stop = trackAppFrame(window)
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("874px")

    visual.height = 800
    window.dispatchEvent(new Event("resize"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("874px")

    // Turned on its side, the window is as wide as the long side and the short side is the height.
    Object.defineProperty(window, "innerWidth", { value: 874, configurable: true })
    window.dispatchEvent(new Event("orientationchange"))
    expect(document.documentElement.style.getPropertyValue("--app-height")).toBe("402px")

    stop()
    Object.defineProperty(window.navigator, "standalone", { value: undefined, configurable: true })
    Object.defineProperty(window, "innerWidth", { value: 1024, configurable: true })
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

/** iPhone figures: an 844px window, a 336px keyboard, and iOS panning 120px further to show a field. */
describe("visibleBand - where a bottom sheet stands while the keyboard is open", () => {
  it("is the whole window, nothing under it, without a keyboard", () => {
    expect(visibleBand({ innerHeight: 844, visualViewport: { height: 844, scale: 1, offsetTop: 0 } })).toEqual({
      height: 844,
      below: 0,
    })
  })

  it("puts the keyboard under it", () => {
    expect(visibleBand({ innerHeight: 844, visualViewport: { height: 508, scale: 1, offsetTop: 0 } })).toEqual({
      height: 508,
      below: 336,
    })
  })

  it("takes a pan off what lies under it, so a sheet meets the keyboard rather than floating a band above it", () => {
    expect(visibleBand({ innerHeight: 844, visualViewport: { height: 508, scale: 1, offsetTop: 120 } })).toEqual({
      height: 508,
      below: 216,
    })
  })

  it("is nothing while pinched, and the window where there is no visual viewport", () => {
    expect(visibleBand({ innerHeight: 844, visualViewport: { height: 300, scale: 2, offsetTop: 40 } })).toBeNull()
    expect(visibleBand({ innerHeight: 844, visualViewport: null })).toEqual({ height: 844, below: 0 })
  })
})

describe("trackAppFrame - the band a sheet stands on", () => {
  it("publishes the visible height and the keyboard inset, and follows a pan, which fires no resize", () => {
    const listeners = new Map<string, () => void>()
    const visual = {
      height: 844,
      scale: 1,
      offsetTop: 0,
      addEventListener(type: string, listener: () => void) {
        listeners.set(type, listener)
      },
      removeEventListener() {},
    }
    Object.defineProperty(window, "visualViewport", { value: visual, configurable: true })
    Object.defineProperty(window, "innerHeight", { value: 844, configurable: true })
    const style = document.documentElement.style

    const stop = trackAppFrame(window)
    expect([style.getPropertyValue("--visible-height"), style.getPropertyValue("--keyboard-inset")]).toEqual([
      "844px",
      "0px",
    ])

    visual.height = 508
    listeners.get("resize")?.()
    expect([style.getPropertyValue("--visible-height"), style.getPropertyValue("--keyboard-inset")]).toEqual([
      "508px",
      "336px",
    ])

    visual.offsetTop = 120
    listeners.get("scroll")?.()
    expect(style.getPropertyValue("--keyboard-inset")).toBe("216px")
    stop()
  })
})
