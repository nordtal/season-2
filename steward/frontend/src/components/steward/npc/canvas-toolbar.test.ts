import { describe, expect, it } from "vitest"

import { clampViewportToNodes } from "@/components/steward/npc/canvas-toolbar"

describe("clampViewportToNodes", () => {
  const freeArea = { left: 0, right: 1000, top: 60, bottom: 800 }
  const nodes = [
    { x: 24, y: 24, width: 260, height: 140 },
    { x: 744, y: 208, width: 260, height: 140 },
  ]

  it("returns null when at least one node intersects the free area by 48px", () => {
    expect(clampViewportToNodes({ x: 0, y: 60, zoom: 1 }, nodes, freeArea)).toBeNull()
  })

  it("clamps a diagonal top-right pan so the nearest node stays visible in the free area", () => {
    const clamped = clampViewportToNodes({ x: -1200, y: 1200, zoom: 1 }, nodes, freeArea)
    expect(clamped).not.toBeNull()
    const visible = nodes.some((node) => {
      const left = node.x * clamped!.zoom + clamped!.x
      const right = (node.x + node.width) * clamped!.zoom + clamped!.x
      const top = node.y * clamped!.zoom + clamped!.y
      const bottom = (node.y + node.height) * clamped!.zoom + clamped!.y
      const overlapX = Math.min(right, freeArea.right) - Math.max(left, freeArea.left)
      const overlapY = Math.min(bottom, freeArea.bottom) - Math.max(top, freeArea.top)
      return overlapX >= 47.5 && overlapY >= 47.5
    })
    expect(visible).toBe(true)
  })
})
