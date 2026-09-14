import { describe, expect, it } from "vitest"

import { modifierLabel, shortcutLabel, usesCommandKey } from "@/lib/keys"

/** Enough of a Navigator for the three fields this reads. */
function navigatorWith(fields: Partial<Navigator> & { userAgentData?: { platform?: string } }) {
  return fields as Navigator
}

describe("which modifier to print", () => {
  it("says Ctrl on a machine that is not a Mac", () => {
    expect(usesCommandKey(navigatorWith({ platform: "Win32" }))).toBe(false)
    expect(modifierLabel(navigatorWith({ platform: "Linux x86_64" }))).toBe("Ctrl")
    expect(shortcutLabel("K", navigatorWith({ platform: "Linux x86_64" }))).toBe("Ctrl+K")
  })

  it("says ⌘ on a Mac, an iPhone and an iPad", () => {
    for (const platform of ["MacIntel", "iPhone", "iPad", "macOS"]) {
      expect(usesCommandKey(navigatorWith({ platform }))).toBe(true)
    }
    expect(shortcutLabel("K", navigatorWith({ platform: "MacIntel" }))).toBe("⌘K")
  })

  it("prefers the user-agent hint where there is one", () => {
    // `navigator.platform` is deprecated; a browser that has stopped answering it must not turn
    // every Mac into a Ctrl.
    expect(usesCommandKey(navigatorWith({ platform: "", userAgentData: { platform: "macOS" } })))
      .toBe(true)
  })

  it("falls back to Ctrl rather than throwing where there is no navigator at all", () => {
    // Server-side rendering is not done here, but a test environment without a DOM is, and a label
    // helper that throws would take the whole page with it.
    expect(usesCommandKey(undefined)).toBe(false)
    expect(modifierLabel(navigatorWith({}))).toBe("Ctrl")
  })
})
