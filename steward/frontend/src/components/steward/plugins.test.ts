import { describe, expect, it } from "vitest"

import { groupPlugins, pluginStatus, removalSentence, versionLine, versionOf } from "@/components/steward/plugins"
import type { AvailableChange, ServicePlugin } from "@/lib/api"

/**
 * The removal confirmation names the plugin's data folder, which it deletes too.
 *
 * The folder cannot be derived from the jar: `Chunky-Bukkit-<version>.jar` makes `plugins/Chunky/`.
 */
const plugin = (over: Partial<ServicePlugin> = {}): ServicePlugin => ({
  name: "Chunky",
  group: "added",
  running: true,
  removable: true,
  fileName: "Chunky-Bukkit-1.5.3.jar",
  dataFolder: "Chunky",
  artifact: "chunky-pregenerator",
  ...over,
})

describe("what the confirmation promises before a plugin is removed", () => {
  it("names the jar and the data folder, and the folder is the descriptor's name", () => {
    const sentence = removalSentence(plugin())

    expect(sentence).toContain("Chunky-Bukkit-1.5.3.jar")
    expect(sentence).toContain("plugins/Chunky/")
  })

  it("names no folder when steward could not read one out of the jar", () => {
    const sentence = removalSentence(plugin({ dataFolder: undefined }))

    /** An unknown folder must not be named, not even by a fallback; `plugins/` alone is the honest sentence. */
    expect(sentence).not.toMatch(/plugins\/\S+\//)
    expect(sentence).toContain("could not be read")
    expect(sentence).toContain("Chunky-Bukkit-1.5.3.jar")
  })

  it("promises nothing about the disk for a plugin that is not installed", () => {
    const sentence = removalSentence(plugin({ running: false, fileName: undefined }))

    expect(sentence).not.toMatch(/plugins\/\S+\//)
    expect(sentence).toContain("not installed yet")
  })
})

describe("reading the version out of a jar's name", () => {
  it("is what follows the last dash of the stem", () => {
    expect(versionOf("packetevents-spigot-2.14.0.jar")).toBe("2.14.0")
    expect(versionOf("Chunky-Bukkit-1.5.3.jar")).toBe("1.5.3")
    expect(versionOf("nodash.jar")).toBeUndefined()
    expect(versionOf(undefined)).toBeUndefined()
  })

  it("is no version when that part is a word", () => {
    expect(versionOf("WorldEditDisplay-2.6.0-paper.jar")).toBeUndefined()
    expect(versionOf("voxy-server-side-paper.jar")).toBeUndefined()
  })
})

describe("the version a row shows", () => {
  const texts = (over: Partial<ServicePlugin>) => versionLine(plugin(over)).map((piece) => piece.text)

  it("is the release's alone when the jar is the one it ships", () => {
    expect(texts({ fileName: "smp-0.16.1.jar", release: "0.16.1" })).toEqual(["0.16.1"])
  })

  it("labels both when the jar and the release differ", () => {
    expect(texts({ fileName: "packetevents-spigot-2.14.0.jar", release: "0.16.1" })).toEqual([
      "jar 2.14.0",
      "release v0.16.1",
    ])
  })

  it("shows the whole file name when its name carries no version", () => {
    expect(texts({ fileName: "voxy-server-side-paper.jar", release: undefined })).toEqual([
      "voxy-server-side-paper.jar",
    ])
  })
})

const change = (over: Partial<AvailableChange>): AvailableChange => ({
  service: "smp",
  held: false,
  artifact: "chunky",
  status: "UP_TO_DATE",
  work: false,
  failure: false,
  installed: "Chunky-Bukkit-1.5.3.jar",
  ...over,
})

describe("what the update check says on a row", () => {
  it("matches by service and installed file, not by name", () => {
    expect(pluginStatus("smp", plugin(), [change({ service: "limbo", status: "OUTDATED" })])).toBeUndefined()
    expect(pluginStatus("smp", plugin(), [change({})])).toEqual({ tone: "idle", text: "up to date" })
  })

  it("names both versions of an update", () => {
    const outdated = change({ status: "OUTDATED", fileName: "Chunky-Bukkit-1.5.4.jar" })
    expect(pluginStatus("smp", plugin(), [outdated])).toEqual({ tone: "warn", text: "1.5.3 → 1.5.4" })
  })

  it("shows no update that a run would not install", () => {
    const held = change({ status: "OUTDATED", work: true, held: true, fileName: "Chunky-Bukkit-1.5.4.jar" })
    expect(pluginStatus("smp", plugin(), [held])).toEqual({ tone: "idle", text: "held back" })
  })

  it("says there is no build for this Minecraft version", () => {
    expect(pluginStatus("smp", plugin(), [change({ status: "UNSUPPORTED" })], "26.2")?.text).toBe("no 26.2 build")
  })

  it("says nothing when the check could not tell, or has not answered", () => {
    expect(pluginStatus("smp", plugin(), [change({ status: "UNRESOLVED" })])).toBeUndefined()
    expect(pluginStatus("smp", plugin(), undefined)).toBeUndefined()
  })
})

describe("the three lists", () => {
  it("keeps the order Nordtal, Preinstalled, Added and leaves an empty one out", () => {
    const groups = groupPlugins([
      plugin({ name: "b", group: "added" }),
      plugin({ name: "smp", group: "nordtal" }),
      plugin({ name: "A", group: "added" }),
    ])
    expect(groups.map(([group, rows]) => [group, rows.map((row) => row.name)])).toEqual([
      ["nordtal", ["smp"]],
      ["added", ["A", "b"]],
    ])
  })

  it("puts a Nordtal plugin with a rank before the alphabet", () => {
    const groups = groupPlugins([
      plugin({ name: "SMP", group: "nordtal", rank: 1 }),
      plugin({ name: "Display Tags", group: "nordtal", rank: 0 }),
      plugin({ name: "Hunger Games", group: "nordtal", rank: 4 }),
    ])
    expect(groups[0][1].map((row) => row.name)).toEqual(["Display Tags", "SMP", "Hunger Games"])
  })
})
