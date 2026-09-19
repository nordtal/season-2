import { describe, expect, it } from "vitest"

import { removalSentence } from "@/components/steward/plugins"
import type { ServicePlugin } from "@/lib/api"

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

  it("promises nothing about the disk for a plugin that is only pre-booked", () => {
    const sentence = removalSentence(plugin({ running: false, fileName: undefined }))

    expect(sentence).not.toMatch(/plugins\/\S+\//)
    expect(sentence).toContain("not installed yet")
  })
})
