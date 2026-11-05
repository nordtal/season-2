import { describe, expect, it } from "vitest"

import type { Router } from "@/lib/api"
import { nextRead } from "@/lib/queries-season"

const NOW = Date.parse("2026-11-05T12:00:00Z")

function router(opening: Router["opening"], launch?: string): Router {
  return { opening, launch, maintenance: false, mode: "SMP", anchoredGrants: 0 }
}

describe("nextRead", () => {
  it("reads again a second past a launch within the minute", () => {
    expect(nextRead(router("COUNTDOWN", "2026-11-05T12:00:20Z"), NOW)).toBe(21_000)
  })

  it("reads once a minute while the launch is further ahead", () => {
    expect(nextRead(router("COUNTDOWN", "2026-11-05T13:00:00Z"), NOW)).toBe(60_000)
  })

  it("reads every second once the countdown is past but the answer still counts down", () => {
    expect(nextRead(router("COUNTDOWN", "2026-11-05T11:59:00Z"), NOW)).toBe(1_000)
  })

  it("reads once a minute when closed or launched", () => {
    expect(nextRead(router("CLOSED"), NOW)).toBe(60_000)
    expect(nextRead(router("LAUNCHED", "2026-11-05T11:00:00Z"), NOW)).toBe(60_000)
    expect(nextRead(undefined, NOW)).toBe(60_000)
  })
})
