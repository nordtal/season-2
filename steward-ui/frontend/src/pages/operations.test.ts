import { describe, expect, it } from "vitest"

import { tonight } from "@/pages/operations"

/**
 * "Tonight", the one piece of arithmetic behind the buttons on the Operations page.
 *
 * What it hands back becomes `not_before` on an `update_request` row, so a moment that is already
 * past is not a harmless rounding error: the worker picks the row up at once, stops smp,
 * proxy and the bot, and the network goes down while somebody is in the world. Every
 * test here is ultimately about that one sentence.
 *
 * The forty-five minutes are `MINUTES_BEFORE_BACKUP`, which is not exported - so the gap is
 * asserted as behaviour rather than against the constant. Times built with `new Date(y, m, d, h)`
 * are local on purpose: that branch is specified in the browser's zone, and the tests must pass in
 * whatever zone they run in.
 */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

describe("tonight - with no nightly backup to stay out of the way of", () => {
  it("means four in the morning in the browser's own zone", () => {
    const now = new Date(2026, 8, 12, 22, 0, 0)
    const night = tonight(null, now)

    expect(night.getHours()).toBe(4)
    expect(night.getMinutes()).toBe(0)
    expect(night.getSeconds()).toBe(0)
    expect(night.getMilliseconds()).toBe(0)
    expect(night.getDate()).toBe(13)
  })

  it("means this morning when it is not yet four", () => {
    // Somebody at two in the morning asking for "tonight" means the four o'clock two hours
    // away, not the one twenty-six hours away.
    const now = new Date(2026, 8, 12, 2, 0, 0)

    expect(tonight(null, now).getDate()).toBe(12)
    expect(tonight(null, now).getHours()).toBe(4)
  })

  it("means tomorrow at exactly four, because a moment that has arrived is not a plan", () => {
    const now = new Date(2026, 8, 12, 4, 0, 0, 0)

    expect(tonight(null, now).getDate()).toBe(13)
  })

  it("takes the same branch for undefined, for an empty string and for the word null", () => {
    // The worker sends null when `backup.at` is empty; the API layer can produce the string "null"
    // for a NULL column. Both have to land on four o'clock rather than on Invalid Date.
    const now = new Date(2026, 8, 12, 22, 0, 0)

    for (const nothing of [null, undefined, "", "null", "not a time"]) {
      const night = tonight(nothing, now)

      expect(Number.isNaN(night.getTime())).toBe(false)
      expect(night.getHours()).toBe(4)
    }
  })
})

describe("tonight - forty-five minutes before the worker's own clock", () => {
  it("lands exactly three quarters of an hour before the backup", () => {
    const now = new Date(Date.UTC(2026, 8, 12, 20, 0, 0))
    const backup = new Date(Date.UTC(2026, 8, 13, 2, 45, 0))

    expect(tonight(backup.toISOString(), now).getTime()).toBe(backup.getTime() - 45 * MINUTE)
  })

  it("reads the worker's offset as an offset, which is the whole reason it asks the worker", () => {
    // The bug this replaced: 04:00 in the browser's zone, while the worker's backup clock runs in
    // the container's. An admin two zones west used to schedule the update after the backup.
    const now = new Date(Date.UTC(2026, 8, 12, 12, 0, 0))
    const night = tonight("2026-09-13T04:45:00+09:00", now)

    // 04:45 in +09:00 is 19:45 UTC, and the slot is 45 minutes before that instant - not 45
    // minutes before 04:45 wherever the browser happens to sit.
    expect(night.toISOString()).toBe("2026-09-12T19:00:00.000Z")
  })

  it("keeps the seconds of an odd backup time instead of rounding to the minute", () => {
    const now = new Date(Date.UTC(2026, 8, 12, 12, 0, 0))
    const backup = new Date(Date.UTC(2026, 8, 13, 4, 45, 30))

    expect(tonight(backup.toISOString(), now).getTime()).toBe(backup.getTime() - 45 * MINUTE)
  })
})

describe("tonight - when tonight's slot has already gone", () => {
  it("takes tomorrow's slot when the backup is less than forty-five minutes away", () => {
    // Asking for a moment in the past is asking the worker to run now, with the backup's own lock
    // about to be taken by somebody else.
    const now = new Date(Date.UTC(2026, 8, 12, 4, 15, 0))
    const backup = new Date(Date.UTC(2026, 8, 12, 4, 45, 0))
    const night = tonight(backup.toISOString(), now)

    expect(night.getTime()).toBe(backup.getTime() - 45 * MINUTE + DAY)
    expect(night.getTime()).toBeGreaterThan(now.getTime())
  })

  it("counts exactly forty-five minutes ahead as gone, not as just in time", () => {
    const now = new Date(Date.UTC(2026, 8, 12, 4, 0, 0))
    const backup = new Date(Date.UTC(2026, 8, 12, 4, 45, 0))

    expect(tonight(backup.toISOString(), now).getTime()).toBe(now.getTime() + DAY)
  })

  it("counts a second more than that as tonight", () => {
    const now = new Date(Date.UTC(2026, 8, 12, 3, 59, 59))
    const backup = new Date(Date.UTC(2026, 8, 12, 4, 45, 0))

    expect(tonight(backup.toISOString(), now).getTime()).toBe(backup.getTime() - 45 * MINUTE)
  })

  it("never hands back a moment that is already past, however stale the schedule is", () => {
    // Two days and not one, deliberately: `useSchedule` has an hour of staleTime, no refetch
    // interval, and main.tsx turns refetchOnWindowFocus off for every query, so a dashboard left
    // open on an unattended screen still holds a nextBackupAt from days ago. A single `+ 1 day`
    // answered with a moment in the PAST, which lands in not_before on the update_request row and
    // is a run that starts the instant the button is pressed.
    const now = new Date(Date.UTC(2026, 8, 12, 12, 0, 0))
    const stale = new Date(now.getTime() - 2 * DAY)

    expect(tonight(stale.toISOString(), now).getTime()).toBeGreaterThan(now.getTime())
  })
})
