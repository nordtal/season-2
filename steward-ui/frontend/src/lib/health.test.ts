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
 * The Ampel, held against the four triggers §10c says it has.
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
    human: "1,5 GB",
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

/** A whole stack with nothing wrong with it. */
function healthy() {
  return {
    table: table([service(), service({ service: "postgres" })]),
    host: host(),
    backups: [backup(2)],
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
    expect(triggers[0].text).toBe("Es ist kein Dienst da - Docker hat eine leere Liste geliefert.")
    expect(triggers[0].to).toBe("/betrieb")
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
    expect(triggers[0].text).toBe("smp läuft nicht (Exited (1) 2 minutes ago).")
  })

  it("points at the page where something can be done about it", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service({ service: "postgres", state: "exited", status: "" })]),
    })

    // The route is /dienste/$name in router.tsx; a trigger that points nowhere is a dead end.
    expect(triggers[0].to).toBe("/dienste/$name")
    expect(triggers[0].params).toEqual({ name: "postgres" })
  })

  it("falls back to the container state when Docker's status line is empty", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service({ state: "dead", status: "" })]),
    })

    expect(triggers[0].text).toBe("smp läuft nicht (dead).")
  })

  it("counts a container that keeps restarting as down, because a crash loop looks busy", () => {
    // The one state the file argues about by name: restarting is not on the good list.
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ state: "restarting", status: "Restarting (1) 5 seconds ago" })]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("läuft nicht")
  })

  it("is red for a container that runs but reports itself unhealthy", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ state: "running", health: "unhealthy" })]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("smp läuft, meldet sich aber als unhealthy.")
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
    expect(triggers[0].text).toBe("smp läuft auf einem älteren Image als die Registry hat.")
    expect(triggers[0].to).toBe("/betrieb")
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
    expect(triggers[0].text).toContain("2 Dienste")
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
    expect(triggers[0].text).toContain("nicht verglichen")
    expect(triggers[0].text).toContain("504 vom Proxy")
  })

  it("still says something when the registry failed without saying why", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service()], { reached: false, message: undefined }),
    })

    expect(triggers[0].text).toContain("die Registry antwortete nicht")
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
    expect(triggers[0].text).toBe("Es liegt keine einzige Sicherung vor.")
    expect(triggers[0].to).toBe("/betrieb")
  })

  it("distinguishes no backup at all from one that was only ever started", () => {
    // A23: run 23 reported success having saved zero volumes. A .partial file is the shape that
    // failure leaves behind, and it must not read as an archive.
    const { level, triggers } = summarise({ ...healthy(), backups: [backup(1, { partial: true })] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("Es liegt keine fertige Sicherung vor - nur angefangene (.partial).")
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
    expect(triggers[0].text).toContain("36 Stunden")
    // The age is spelled by `relative`, which at day distance uses the calendar words. Pinned so
    // that a sentence saying only "älter als erlaubt" without saying how old would break here.
    expect(triggers[0].text).toContain("vorgestern")
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
    expect(triggers[0].text).toContain("älter als die erlaubten")
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
    expect(triggers[0].text).toContain("zu 85 % belegt")
    expect(triggers[0].text).toContain("Schwelle 85 %")
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
    // This sentence is where the Intl default showed up: "zu 87,457 % belegt" in a line an
    // operator is meant to read at a glance.
    const { triggers } = summarise({
      ...healthy(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 87_456_700_000 }),
    })

    expect(triggers[0].text).toContain("zu 87 % belegt")
    // The byte counts in the same sentence do carry a decimal, so only the percentage is pinned:
    // what must not come back is "zu 87,457 % belegt".
    expect(triggers[0].text).toMatch(/ist zu \d+ % belegt/)
  })

  it("puts both byte counts in the sentence, so the percentage can be checked", () => {
    const { triggers } = summarise({
      ...healthy(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 90_000_000_000 }),
    })

    expect(triggers[0].text).toContain("90,0 GB von 100,0 GB")
  })

  it("measures memory as total minus available, which is not total minus used", () => {
    // 8 GB with 400 MB available is 95 % in use, cache included - the number the host itself
    // reports as available is the only one worth alarming on.
    const { level, triggers } = summarise({
      ...healthy(),
      host: host({ memoryTotalBytes: 8_000_000_000, memoryAvailableBytes: 400_000_000 }),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("zu 95 % belegt")
    expect(triggers[0].text).toContain("Schwelle 90 %")
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
    // A host whose df could not be read arrives with the fields missing, and "NaN % belegt" is
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
      "smp läuft nicht (Exited (0)).",
      "postgres läuft nicht (Exited (0)).",
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
    expect(UNKNOWN).toContain("nicht sagen")
  })
})
