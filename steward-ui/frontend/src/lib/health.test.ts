import { describe, expect, it } from "vitest"

import type { Backup, Host, Service, ServiceTable } from "@/lib/api"
import {
  ALL_CLEAR,
  DEFAULT_THRESHOLDS,
  UNKNOWN,
  shownLevel,
  summarise,
  type Level,
} from "@/lib/health"

/**
 * The traffic light, held against the four triggers §10c says it has.
 *
 * Two rules are worth more than the rest and both are here twice, once at the boundary and once a
 * step past it: a green light is only ever the result of having looked, and a red trigger never
 * hides a yellow one. Everything else in this file is a sentence the operator reads at six in the
 * morning, so the texts are asserted by their content, not by their exact wording - a rephrasing
 * should not break a test, a missing service name should.
 */

const NOW = Date.UTC(2026, 8, 12, 12, 0, 0)
const HOUR = 3_600_000

/** A container Docker is happy with, which is the row every test starts from. */
function service(over: Partial<Service> = {}): Service {
  return {
    service: "smp",
    containerId: "abc123",
    image: "ghcr.io/nordtal/smp:1.2.3",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "UP_TO_DATE",
    health: "healthy",
    ...over,
  }
}

/** A service table whose image comparison succeeded, i.e. one that accuses nobody by itself. */
function table(services: Service[], drift: Partial<ServiceTable["drift"]> = {}): ServiceTable {
  return {
    services,
    drift: { checkedAt: new Date(NOW - 30_000).toISOString(), reached: true, unverifiable: [], ...drift },
  }
}

function backup(hoursAgo: number, over: Partial<Backup> = {}): Backup {
  return {
    name: "nordtal-2026-09-12.tar.zst",
    bytes: 1_500_000_000,
    human: "1.5 GB",
    modified: new Date(NOW - hoursAgo * HOUR).toISOString(),
    partial: false,
    ...over,
  }
}

/** A host with room to spare, so that only the value under test can trip a threshold. */
function host(over: Partial<Host> = {}): Host {
  return {
    memoryTotalBytes: 8_000_000_000,
    memoryAvailableBytes: 4_000_000_000,
    diskTotalBytes: 100_000_000_000,
    diskUsedBytes: 40_000_000_000,
    containerLimits: "none",
    ...over,
  }
}

/**
 * A whole stack with nothing wrong with it.
 *
 * The thresholds are in here because `summarise` has none of its own: they arrive from
 * `/api/settings`, and a call that leaves them out is testing the case where that has not answered.
 */
function healthy() {
  return {
    table: table([service(), service({ service: "postgres" })]),
    host: host(),
    backups: [backup(2)],
    thresholds: DEFAULT_THRESHOLDS,
    now: NOW,
  }
}

describe("summarise - a stack with nothing wrong", () => {
  it("says nothing at all when every trigger is quiet", () => {
    const { level, triggers } = summarise(healthy())

    expect(level).toBe("ok")
    expect(triggers).toEqual([])
  })

  it("is yellow for a service table that was answered and is empty", () => {
    // Till's call, 2026-09-13. This used to be green on the grounds that no service is no failing
    // service. But compose.yml declares eight services on this host and none of them is optional
    // enough to explain an empty table, so an answered empty list means the daemon has nothing
    // left to show - the same family as the green light on failed queries that was just removed.
    const { level, triggers } = summarise({ table: table([]), now: NOW })

    expect(level).toBe("warn")
    expect(triggers[0].text).toBe("There is no service at all - Docker returned an empty list.")
    expect(triggers[0].to).toBe("/operations")
  })

  it("says nothing about an empty list that was never asked for", () => {
    // Undefined is "not answered yet", and a page that has not finished loading must not accuse
    // the host of having lost every container.
    expect(summarise({ host: host(), backups: [backup(1)], now: NOW }).level).toBe("ok")
  })
})

describe("summarise - a service that is not running", () => {
  it("turns the light red and names the service and Docker's own words", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ service: "smp", state: "exited", status: "Exited (1) 2 minutes ago" })]),
    })

    expect(level).toBe("down")
    expect(triggers).toHaveLength(1)
    expect(triggers[0].text).toBe("smp is not running (Exited (1) 2 minutes ago).")
  })

  it("points at the page where something can be done about it", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service({ service: "postgres", state: "exited", status: "" })]),
    })

    // The route is /services/$name in router.tsx; a trigger that points nowhere is a dead end.
    expect(triggers[0].to).toBe("/services/$name")
    expect(triggers[0].params).toEqual({ name: "postgres" })
  })

  it("falls back to the container state when Docker's status line is empty", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service({ state: "dead", status: "" })]),
    })

    expect(triggers[0].text).toBe("smp is not running (dead).")
  })

  it("counts a container that keeps restarting as down, because a crash loop looks busy", () => {
    // The one state the file argues about by name: restarting is not on the good list.
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ state: "restarting", status: "Restarting (1) 5 seconds ago" })]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("is not running")
  })

  it("is red for a container that runs but reports itself unhealthy", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ state: "running", health: "unhealthy" })]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("smp is running, but reports itself unhealthy.")
  })

  it("is quiet for a container with no healthcheck and for one still starting", () => {
    // Absent is not unhealthy, and neither is "starting" - a container in its grace period would
    // otherwise paint the whole page red every deploy.
    expect(summarise({ ...healthy(), table: table([service({ health: undefined })]) }).level).toBe("ok")
    expect(summarise({ ...healthy(), table: table([service({ health: "starting" })]) }).level).toBe("ok")
  })
})

describe("summarise - image drift", () => {
  it("names the one service that runs on an older image", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ service: "smp", drift: "OUTDATED" }), service({ service: "postgres" })]),
    })

    expect(level).toBe("warn")
    expect(triggers).toHaveLength(1)
    expect(triggers[0].text).toBe("smp is running an older image than the registry has.")
    expect(triggers[0].to).toBe("/operations")
  })

  it("counts them and lists them when more than one is behind", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([
        service({ service: "smp", drift: "OUTDATED" }),
        service({ service: "bot", drift: "OUTDATED" }),
        service({ service: "postgres" }),
      ]),
    })

    expect(triggers).toHaveLength(1)
    expect(triggers[0].text).toContain("2 services")
    expect(triggers[0].text).toContain("smp, bot")
    expect(triggers[0].text.endsWith(".")).toBe(true)
  })

  it("says out loud that the comparison did not happen, and repeats the registry's excuse", () => {
    // A24: four releases shipped unnoticed because nothing said it did not know.
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service()], { reached: false, message: "504 vom Proxy" }),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("were not compared")
    expect(triggers[0].text).toContain("504 vom Proxy")
  })

  it("still says something when the registry failed without saying why", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service()], { reached: false, message: undefined }),
    })

    expect(triggers[0].text).toContain("the registry did not answer")
  })

  it("does not turn yellow for a single image that carries no registry digest", () => {
    // Till's call, 2026-09-13, asked rather than assumed. status.tsx argues that UNKNOWN is
    // "deliberately not silent and deliberately not green", and A24 is the story this whole light
    // came from - but several images in this stack are built here and published nowhere, so
    // UNKNOWN is their normal state. A yellow that never goes away is a light nobody reads any
    // more, which costs more than the case it would catch. The page footnotes the count instead.
    const { level } = summarise({
      ...healthy(),
      table: table([service({ drift: "UNKNOWN" })], { unverifiable: ["smp"] }),
    })

    expect(level).toBe("ok")
  })
})

describe("summarise - the backup", () => {
  it("says nothing when the backup list was never asked for", () => {
    // Undefined is "not asked yet". Reading it as "there is no backup" would put a red light on
    // every first render, which is how a real alarm stops being read.
    expect(summarise({ table: table([service()]), host: host(), now: NOW }).level).toBe("ok")
  })

  it("is red when the list was answered and is empty", () => {
    const { level, triggers } = summarise({ ...healthy(), backups: [] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("There is not a single backup.")
    expect(triggers[0].to).toBe("/operations")
  })

  it("distinguishes no backup at all from one that was only ever started", () => {
    // A23: run 23 reported success having saved zero volumes. A .partial file is the shape that
    // failure leaves behind, and it must not read as an archive.
    const { level, triggers } = summarise({ ...healthy(), backups: [backup(1, { partial: true })] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("There is no finished backup - only started ones (.partial).")
  })

  it("is quiet for a backup exactly at the age the thresholds still allow", () => {
    // "How old the newest backup may be": at 36 h it still may be. A run that finishes at 04:45
    // every night is 36 h old for nobody, but the boundary is where an off-by-one lives.
    expect(summarise({ ...healthy(), backups: [backup(DEFAULT_THRESHOLDS.backupAgeHours)] }).level).toBe(
      "ok",
    )
  })

  it("is red a minute past that age, and says what the limit was", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      backups: [backup(DEFAULT_THRESHOLDS.backupAgeHours + 1 / 60)],
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("36 hours")
    // The age is spelled by `relative`, which at day distance uses the calendar words. Pinned so
    // that a sentence saying only "older than allowed" without saying how old would break here.
    expect(triggers[0].text).toContain("2 days ago")
  })

  it("judges the newest finished archive, not the first row in the list", () => {
    const { level } = summarise({
      ...healthy(),
      backups: [backup(200, { name: "alt.tar.zst" }), backup(3), backup(80)],
    })

    expect(level).toBe("ok")
  })

  it("does not let a fresh .partial rescue an old finished backup", () => {
    // The half-written archive of a run that is going wrong right now is the worst possible reason
    // to believe there is a backup.
    const { level, triggers } = summarise({
      ...healthy(),
      backups: [backup(40), backup(0.5, { partial: true, name: "neu.tar.zst.partial" })],
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("older than the permitted")
  })

  it("is red rather than quietly fine when the newest archive has no readable timestamp", () => {
    // The backend prints String.valueOf(instant), so a NULL arrives as the word "null" and the age
    // is NaN. NaN is not "young enough".
    const { level } = summarise({ ...healthy(), backups: [backup(1, { modified: "null" })] })

    expect(level).toBe("down")
  })

  it("honours a threshold the deployment changed", () => {
    const thresholds = { ...DEFAULT_THRESHOLDS, backupAgeHours: 6 }

    expect(summarise({ ...healthy(), backups: [backup(5)], thresholds }).level).toBe("ok")
    expect(summarise({ ...healthy(), backups: [backup(7)], thresholds }).level).toBe("down")
  })
})

describe("summarise - disk and memory", () => {
  it("turns yellow at the threshold itself, not one percent above it", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 85_000_000_000 }),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("85 % full")
    expect(triggers[0].text).toContain("threshold 85 %")
  })

  it("says nothing a hair below the threshold", () => {
    expect(
      summarise({
        ...healthy(),
        host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 84_999_000_000 }),
      }).level,
    ).toBe("ok")
  })

  it("prints the disk figure as a whole percent, not to three decimals", () => {
    // This sentence is where the Intl default showed up: "87.457 % full" in a line an
    // operator is meant to read at a glance.
    const { triggers } = summarise({
      ...healthy(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 87_456_700_000 }),
    })

    expect(triggers[0].text).toContain("87 % full")
    // The byte counts in the same sentence do carry a decimal, so only the percentage is pinned:
    // what must not come back is "87.457 % full".
    expect(triggers[0].text).toMatch(/is \d+ % full/)
  })

  it("puts both byte counts in the sentence, so the percentage can be checked", () => {
    const { triggers } = summarise({
      ...healthy(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 90_000_000_000 }),
    })

    expect(triggers[0].text).toContain("90.0 GB of 100.0 GB")
  })

  it("measures memory as total minus available, which is not total minus used", () => {
    // 8 GB with 400 MB available is 95 % in use, cache included - the number the host itself
    // reports as available is the only one worth alarming on.
    const { level, triggers } = summarise({
      ...healthy(),
      host: host({ memoryTotalBytes: 8_000_000_000, memoryAvailableBytes: 400_000_000 }),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("95 % used")
    expect(triggers[0].text).toContain("threshold 90 %")
  })

  it("turns yellow at the memory threshold itself", () => {
    expect(
      summarise({
        ...healthy(),
        host: host({ memoryTotalBytes: 8_000_000_000, memoryAvailableBytes: 800_000_000 }),
      }).level,
    ).toBe("warn")
  })

  it("does not divide by a size it does not have", () => {
    // A host whose df could not be read arrives with the fields missing, and "NaN % full" is
    // worse than silence.
    for (const broken of [
      { diskTotalBytes: undefined, diskUsedBytes: 40_000_000_000 },
      { diskTotalBytes: 0, diskUsedBytes: 0 },
      { diskTotalBytes: 100_000_000_000, diskUsedBytes: undefined },
      { memoryTotalBytes: undefined, memoryAvailableBytes: 100 },
      { memoryTotalBytes: 8_000_000_000, memoryAvailableBytes: undefined },
    ]) {
      const { level, triggers } = summarise({ ...healthy(), host: host(broken) })

      expect(level).toBe("ok")
      expect(JSON.stringify(triggers)).not.toContain("NaN")
    }
  })

  it("says nothing at all when the host could not be read", () => {
    expect(summarise({ table: table([service()]), backups: [backup(2)], now: NOW }).level).toBe("ok")
  })
})

describe("summarise - several things at once", () => {
  it("lists a yellow trigger next to a red one rather than hiding it behind the worse news", () => {
    // Two errands: a service is down and an image is behind. Showing only the first means the
    // second is discovered a week later.
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([
        service({ service: "smp", state: "exited", status: "Exited (0)" }),
        service({ service: "bot", drift: "OUTDATED" }),
      ]),
      backups: [],
    })

    expect(level).toBe("down")
    expect(triggers).toHaveLength(3)
    expect(triggers.map((trigger) => trigger.level)).toEqual(["down", "down", "warn"])
  })

  it("keeps the order the services came in within one level", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([
        service({ service: "smp", state: "exited", status: "Exited (0)" }),
        service({ service: "postgres", state: "exited", status: "Exited (0)" }),
      ]),
    })

    expect(triggers.map((trigger) => trigger.text)).toEqual([
      "smp is not running (Exited (0)).",
      "postgres is not running (Exited (0)).",
    ])
  })

  it("is yellow when every trigger is yellow, however many there are", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ drift: "OUTDATED" })], { reached: false }),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 99_000_000_000 }),
    })

    expect(level).toBe("warn")
    expect(triggers).toHaveLength(3)
  })

  it("is green with no input at all, which is the whole reason shownLevel exists", () => {
    // summarise on nothing cannot find a fault, so it reports none. That is correct and it is also
    // exactly the state that drew a green tick over a grey footnote saying the page had read
    // nothing. The fix is not here; it is the next describe.
    expect(summarise({}).level).toBe("ok")
  })
})

describe("shownLevel", () => {
  it("shows yellow rather than green when a query failed, because not knowing is not ok", () => {
    expect(shownLevel("ok", true)).toBe("warn")
  })

  it("lets a measured level stand, because a failed query is the weaker sentence", () => {
    expect(shownLevel("warn", true)).toBe("warn")
    expect(shownLevel("down", true)).toBe("down")
  })

  it("changes nothing at all when everything was read", () => {
    for (const level of ["ok", "warn", "down"] as Level[]) {
      expect(shownLevel(level, false)).toBe(level)
    }
  })

  it("never turns a measured red into anything softer", () => {
    // The one direction this function must not have: doubt can only ever make the light worse.
    for (const level of ["ok", "warn", "down"] as Level[]) {
      const shown = shownLevel(level, true)
      const rank = { ok: 0, warn: 1, down: 2 }

      expect(rank[shown]).toBeGreaterThanOrEqual(rank[level])
    }
  })

  it("keeps the light and the sentence saying the same thing with everything failed", () => {
    // End to end, the defect as it was reported: a page with every query failed drew the green tick
    // and "Alles in Ordnung." while its own footnote said it had read nothing.
    const { level, triggers } = summarise({})

    expect(triggers).toEqual([])
    expect(shownLevel(level, true)).toBe("warn")
    expect(UNKNOWN).not.toBe(ALL_CLEAR)
    expect(UNKNOWN).toContain("cannot be said")
  })
})

/**
 * The thresholds are an input, not a default.
 *
 * `summarise` used to end on `input.thresholds ?? DEFAULT_THRESHOLDS`, and the cost of that one
 * `??` is the reason this whole block exists: with `/api/settings` unanswered the page judged the
 * disk against 85 % while the deployment said something else, and said nothing about having
 * guessed - so the screen and the Discord channel, which reads the same numbers off the server,
 * could disagree without either of them looking wrong.
 *
 * The line the fallback used to hide runs exactly here: a check that needs a NUMBER to compare
 * against is skipped, a check that needs none is not. Every test below is one side of that line,
 * and the pairs are deliberate - the same input twice, once with thresholds and once without, so
 * that a failure says which of the two moved.
 */

/** The healthy stack with the thresholds taken away: `/api/settings` has not answered. */
function blind() {
  const { thresholds: _thresholds, ...rest } = healthy()
  return rest
}

describe("summarise - with no thresholds, the checks that need a number", () => {
  it("does not judge the disk at all, not even one that is nearly full", () => {
    // 99 % of the disk, and silence. That is not a bug being asserted: the number this would be
    // compared against is the one that has not arrived, and inventing one is how the screen and
    // the Discord channel came to disagree.
    const { level, triggers } = summarise({
      ...blind(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 99_000_000_000 }),
    })

    expect(level).toBe("ok")
    expect(triggers).toEqual([])
  })

  it("does not judge memory either", () => {
    const { level, triggers } = summarise({
      ...blind(),
      host: host({ memoryTotalBytes: 8_000_000_000, memoryAvailableBytes: 100_000_000 }),
    })

    expect(level).toBe("ok")
    expect(triggers).toEqual([])
  })

  it("says nothing about the age of the newest backup, however old it is", () => {
    // Three weeks, and green. The page is what has to catch this: status.tsx puts `settings` in
    // `waiting` and in `failed` alongside the other three queries, so an unanswered /api/settings
    // draws either "Reading status…" or the yellow "could not be fetched". Take that
    // away and this green is what the operator sees over a backup from the 23rd.
    const { level, triggers } = summarise({ ...blind(), backups: [backup(500)] })

    expect(level).toBe("ok")
    expect(triggers).toEqual([])
  })

  it("is red about the very same backup the moment the thresholds arrive", () => {
    // The pair to the test above, and the point of both: the only difference between the two
    // calls is the three numbers.
    const { level } = summarise({ ...blind(), thresholds: DEFAULT_THRESHOLDS, backups: [backup(500)] })

    expect(level).toBe("down")
  })

  it("does not quietly reach for DEFAULT_THRESHOLDS at any of its three numbers", () => {
    // The regression in one test. Each of these is comfortably past the shipped default and must
    // still be silent, so that reinstating `?? DEFAULT_THRESHOLDS` turns this red rather than
    // going unnoticed for the four releases the last one did.
    const over = [
      { ...blind(), host: host({ diskTotalBytes: 100e9, diskUsedBytes: 90e9 }) },
      { ...blind(), host: host({ memoryTotalBytes: 8e9, memoryAvailableBytes: 400e6 }) },
      { ...blind(), backups: [backup(DEFAULT_THRESHOLDS.backupAgeHours + 12)] },
    ]

    for (const input of over) {
      expect(summarise(input).level).toBe("ok")
      expect(summarise({ ...input, thresholds: DEFAULT_THRESHOLDS }).level).not.toBe("ok")
    }
  })

  it("is silent about a backup whose timestamp cannot be read, which is not obviously right", () => {
    // `!thresholds` returns before the NaN check, so an archive the backend printed as the word
    // "null" is red with thresholds and invisible without them. Whether "this file has no
    // readable date" is an age comparison at all is a judgement; pinned here so that changing it
    // is a decision somebody makes rather than a side effect.
    expect(summarise({ ...blind(), backups: [backup(1, { modified: "null" })] }).level).toBe("ok")
    expect(
      summarise({ ...blind(), thresholds: DEFAULT_THRESHOLDS, backups: [backup(1, { modified: "null" })] })
        .level,
    ).toBe("down")
  })
})

describe("summarise - with no thresholds, the checks that need no number", () => {
  it("still says there is no backup at all", () => {
    // "Is there an archive" is a question about a list being empty. No number is involved, so
    // nothing about it may wait for /api/settings.
    const { level, triggers } = summarise({ ...blind(), backups: [] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("There is not a single backup.")
  })

  it("still distinguishes no backup from one that was only ever started", () => {
    const { level, triggers } = summarise({ ...blind(), backups: [backup(1, { partial: true })] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("There is no finished backup - only started ones (.partial).")
  })

  it("still turns red for a service that is not running", () => {
    const { level, triggers } = summarise({
      ...blind(),
      table: table([service({ service: "smp", state: "exited", status: "Exited (1)" })]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("smp is not running (Exited (1)).")
  })

  it("still turns red for a container that runs and calls itself unhealthy", () => {
    expect(summarise({ ...blind(), table: table([service({ health: "unhealthy" })]) }).level).toBe("down")
  })

  it("still reports image drift and still names the service", () => {
    const { level, triggers } = summarise({
      ...blind(),
      table: table([service({ service: "bot", drift: "OUTDATED" })]),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("bot")
  })

  it("still says the registry was not reached", () => {
    const { triggers } = summarise({
      ...blind(),
      table: table([service()], { reached: false, message: "504 vom Proxy" }),
    })

    expect(triggers[0].text).toContain("504 vom Proxy")
  })

  it("still calls an answered, empty service list yellow", () => {
    const { level, triggers } = summarise({ ...blind(), table: table([]) })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("no service at all")
  })

  it("keeps sorting red before yellow when the thresholds are missing", () => {
    // The ordering must not quietly depend on which branches ran. Two errands, worst first.
    const { level, triggers } = summarise({
      ...blind(),
      table: table([service({ service: "bot", drift: "OUTDATED" })]),
      backups: [],
    })

    expect(level).toBe("down")
    expect(triggers.map((trigger) => trigger.level)).toEqual(["down", "warn"])
  })
})

/**
 * `DEFAULT_THRESHOLDS` against the file it is a copy of.
 *
 * Since `summarise` stopped falling back to it, the constant has exactly one job left: being the
 * mirror of `UiSpec.AlertSpec`, so that "what steward-ui.yml ships with" written in this repository
 * twice says the same thing twice. Nothing checked that, which is how a mirror stops being one -
 * and this repository already answers that with mirror tests elsewhere (`PlatformTest`,
 * `ResourcePackTest`), so this is the TypeScript-side counterpart.
 *
 * Both files are read as **text**, and both anchors are exact rather than fuzzy:
 *
 * - `UiSpec.java` notes its defaults as method bodies - `default int diskPercent() { return 85; }`;
 *   there is no `@Default` annotation anywhere in that package, and each of the three names occurs
 *   exactly once in the file (checked 2026-09-14).
 * - `StewardUi.java` is what decides which JSON key carries which of them, in the only three
 *   `config.alerts()` calls in the file. Reading that too is the difference between a mirror and a
 *   guess: the mapping from `diskPercent()` to `disk` is not this file's to assume.
 *
 * A pattern that stops matching **throws with the line it was looking for** rather than quietly
 * asserting nothing, which is the one way a test like this can rot.
 */

/*
 * Node, in a directory that is otherwise a browser program.
 *
 * `tsconfig.app.json` deliberately loads no `@types/node`: `process` and `Buffer` must not be
 * reachable from anything that ends up in a bundle. The tests are a different matter - vitest runs
 * in node - so they are excluded there and checked by `tsconfig.test.json`, which is the same
 * project plus those types. A `/// <reference types="node" />` here would have been the short way
 * and was measured and rejected: it makes them legal in EVERY file under src/, application
 * included. `?raw` was the other candidate and Vite refuses it ("Denied ID") because the Java file
 * lies outside the frontend root.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

/** This file's own directory, three levels down from steward-ui/ - never a path from the cwd. */
const STEWARD_UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..")

function read(relative: string): string {
  const file = path.join(STEWARD_UI, relative)
  if (!fs.existsSync(file)) {
    throw new Error(`${file} is not there - has the module been moved out from under this test?`)
  }
  return fs.readFileSync(file, "utf8")
}

/** `diskPercent` -> 85, out of the interface's own method bodies. */
function specDefault(java: string, method: string): number {
  const found = java.match(
    new RegExp(`default\\s+int\\s+${method}\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+(\\d+)\\s*;`),
  )
  if (!found) {
    throw new Error(
      `UiSpec.java has no "default int ${method}() { return <n>; }" any more - the defaults have ` +
        `changed shape, and this mirror has to be rewritten rather than deleted`,
    )
  }
  return Number(found[1])
}

/** `disk` -> `diskPercent`, out of the three `config.alerts()` calls behind /api/settings. */
function wiring(java: string): Record<string, string> {
  const found = [...java.matchAll(/"(\w+)",\s*config\.alerts\(\)\.(\w+)\(\)/g)]
  if (found.length === 0) {
    throw new Error(
      "StewardUi.java no longer builds /api/settings out of config.alerts() - the mapping this " +
        "test reads is gone and the mirror has to be rewritten",
    )
  }
  return Object.fromEntries(found.map(([, key, method]) => [key, method]))
}

describe("DEFAULT_THRESHOLDS - the mirror of UiSpec.AlertSpec", () => {
  const spec = read("src/main/java/eu/nordtal/s2/steward/ui/config/UiSpec.java")
  const routes = read("src/main/java/eu/nordtal/s2/steward/ui/StewardUi.java")

  it("carries the same three numbers steward-ui.yml would be written with", () => {
    // The whole point of the constant. If the deployment's default disk threshold moves to 80 and
    // this still says 85, every test in this file that reads "the shipped default" is testing a
    // number nobody ships.
    const mirrored = Object.fromEntries(
      Object.entries(wiring(routes)).map(([key, method]) => [key, specDefault(spec, method)]),
    )

    expect(mirrored).toEqual({ ...DEFAULT_THRESHOLDS })
  })

  it("is about the same three keys the route actually sends", () => {
    // Not only the values: a `Thresholds` field renamed on one side and not the other means
    // `thresholds.disk` is `undefined` and every comparison against it is quietly false.
    expect(Object.keys(wiring(routes)).sort()).toEqual(Object.keys(DEFAULT_THRESHOLDS).sort())
  })
})
