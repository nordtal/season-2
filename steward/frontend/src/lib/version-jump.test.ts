import { describe, expect, it } from "vitest"

import { versionJump } from "@/lib/version-jump"

describe("versionJump", () => {
  it("reads the version out of two filenames that differ in nothing else", () => {
    expect(versionJump("Chunky-Bukkit-1.5.3.jar", "Chunky-Bukkit-1.6.0.jar", "1.6.0")).toEqual({
      from: "1.5.3",
      to: "1.6.0",
      exact: true,
    })
  })

  /** A naive common prefix ends inside `13` and prints `3.0 → 4.0`, a jump that never happened. */
  it("does not stop in the middle of a number", () => {
    expect(versionJump("packetevents-spigot-2.13.0.jar", "packetevents-spigot-2.14.0.jar", "2.14.0+spigot")).toEqual({
      from: "2.13.0",
      to: "2.14.0",
      exact: true,
    })
  })

  /** `26.2` is on both sides, so only the part after it is the jump. */
  it("names only what actually changed", () => {
    expect(versionJump("paper-26.2-118.jar", "paper-26.2-131.jar", "26.2 build 131")).toEqual({
      from: "118",
      to: "131",
      exact: true,
    })
  })

  it("falls back to the filename rather than inventing a version", () => {
    /** Two names with nothing to compare, where an ugly filename beats a made up number. */
    const jump = versionJump("CoreProtect.jar", "coreprotect-22.4.jar", "22.4")
    expect(jump).toEqual({ from: "CoreProtect.jar", to: "22.4", exact: false })
  })

  it("says nothing at all when nothing is installed", () => {
    expect(versionJump(undefined, "Chunky-Bukkit-1.6.0.jar", "1.6.0")).toBeNull()
  })

  it("says nothing at all when there is no newer file", () => {
    expect(versionJump("Chunky-Bukkit-1.5.3.jar", undefined, undefined)).toBeNull()
  })

  it("refuses a hash against a filename, which is what the resource pack is", () => {
    /** A pack's installed identity is a SHA-1 and its wanted one a zip, so this must not read as a version jump. */
    const jump = versionJump("c0bac3a03dad681347cbe4a3bc932aff8ffd8203", "nordtal-resource-pack-0.9.4.zip", "0.9.4")
    expect(jump).toEqual({
      from: "c0bac3a03dad681347cbe4a3bc932aff8ffd8203",
      to: "0.9.4",
      exact: false,
    })
  })

  it("does not print a jump from a version to itself", () => {
    const jump = versionJump("Chunky-Bukkit-1.6.0.jar", "Chunky-Bukkit-1.6.0.jar", "1.6.0")
    expect(jump).toEqual({ from: "Chunky-Bukkit-1.6.0.jar", to: "1.6.0", exact: false })
  })
})
