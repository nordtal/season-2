import { describe, expect, it } from "vitest"

import { type NavigatorLike, modifierLabel, shortcutLabel, usesCommandKey } from "@/lib/keys"

/** A Navigator with the three fields that this code reads. */
function navigatorWith(fields: NavigatorLike): NavigatorLike {
  return fields
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
    /**
     * `navigator.platform` is deprecated, and a browser that stopped answering it must not turn a Mac into a Ctrl.
     */
    expect(usesCommandKey(navigatorWith({ platform: "", userAgentData: { platform: "macOS" } }))).toBe(true)
  })

  it("falls back to Ctrl rather than throwing where there is no navigator at all", () => {
    /** Without a DOM the helper answers and does not throw and take the page with it. */
    expect(usesCommandKey(undefined)).toBe(false)
    expect(modifierLabel(navigatorWith({}))).toBe("Ctrl")
  })
})
