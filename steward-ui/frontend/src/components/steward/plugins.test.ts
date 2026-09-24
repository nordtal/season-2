import { describe, expect, it } from "vitest"

import { groupPlugins, pluginStatus, removalSentence, versionOf } from "@/components/steward/plugins"
import type { AvailableChange, ServicePlugin } from "@/lib/api"

/**
 * season-2-ops/129: removing a plugin deletes its data folder too, and the confirmation has to
 * **name that folder**.
 *
 * Till chose the deletion against the objection that `plugins/<name>/` is the only hand-kept thing
 * in the installation, and the named confirmation is the other half of that decision rather than a
 * softening of it. So this is the sentence under test, not a rendering detail: the folder's name
 * cannot be derived from the jar's - Chunky ships as `Chunky-Bukkit-<version>.jar` and makes
 * `plugins/Chunky/` - and a dialog that guessed it would be asking about the wrong directory.
 */
const plugin = (over: Partial<ServicePlugin> = {}): ServicePlugin => ({
  name: "Chunky",
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

  it("names no folder when the worker could not read one out of the jar", () => {
    const sentence = removalSentence(plugin({ dataFolder: undefined }))

    // What must be absent is a *named* directory - `plugins/undefined/`, or worse, a fallback to
    // the artefact id, which would point the admin at a folder nobody verified exists. Saying the
    // word `plugins/` while naming nothing under it is the honest sentence, so the assertion is
    // the shape `plugins/<something>/` and not the bare prefix.
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
})

describe("what the update check says on a row", () => {
  const change = (over: Partial<AvailableChange>): AvailableChange => ({
    service: "smp",
    artifact: "chunky",
    status: "UP_TO_DATE",
    work: false,
    failure: false,
    installed: "Chunky-Bukkit-1.5.3.jar",
    ...over,
  })

  it("matches by service and installed file, not by name", () => {
    expect(pluginStatus("smp", plugin(), [change({ service: "limbo", status: "OUTDATED" })])).toBeUndefined()
    expect(pluginStatus("smp", plugin(), [change({})])).toEqual({ tone: "idle", text: "up to date" })
  })

  it("names both versions of an update", () => {
    const outdated = change({ status: "OUTDATED", fileName: "Chunky-Bukkit-1.5.4.jar" })
    expect(pluginStatus("smp", plugin(), [outdated])).toEqual({ tone: "warn", text: "1.5.3 → 1.5.4" })
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
    expect(groups.map(([group, rows]) => [group, rows.map((it) => it.name)])).toEqual([
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
    expect(groups[0][1].map((it) => it.name)).toEqual(["Display Tags", "SMP", "Hunger Games"])
  })
})
