import { describe, expect, it } from "vitest"

import type { Backup, Host, Service, ServiceTable } from "@/lib/api"
import { DEFAULT_THRESHOLDS, UNKNOWN, shownLevel, summarise, type Level } from "@/lib/health"

/**
 * The traffic light against its four triggers.
 *
 * Green only comes from having looked, and a red trigger never hides a yellow one.
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

/** A service table whose image comparison succeeded and accuses nobody. */
function table(services: Service[], drift: Partial<ServiceTable["drift"]> = {}): ServiceTable {
  return {
    services,
    drift: { checkedAt: new Date(NOW - 30_000).toISOString(), reached: true, unverifiable: [], ...drift },
  }
}

function backup(hoursAgo: number, over: Partial<Backup> = {}): Backup {
  return {
    name: "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
    bytes: 1_500_000_000,
    human: "1.5 GB",
    modified: new Date(NOW - hoursAgo * HOUR).toISOString(),
    partial: false,
    ...over,
  }
}

/** The same archive for a named volume, so one volume can go stale alone. */
function archiveOf(volume: string, hoursAgo: number): Backup {
  return backup(hoursAgo, { name: `${volume}-20260912T044500Z.tar.zst` })
}

/** A database dump, `nordtal-<stamp>.dump`, told apart from the volume archives by its suffix. */
function dump(hoursAgo: number, over: Partial<Backup> = {}): Backup {
  return {
    name: "nordtal-20260912T024500Z.dump",
    bytes: 40_000_000,
    human: "40 MB",
    modified: new Date(NOW - hoursAgo * HOUR).toISOString(),
    partial: false,
    ...over,
  }
}

/** An archive list with a healthy dump in it, so a test about archives trips one trigger only. */
function withDump(...archives: Backup[]): Backup[] {
  return [dump(1), ...archives]
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

/** A whole stack with nothing wrong with it, thresholds included. */
function healthy() {
  return {
    table: table([service(), service({ service: "postgres" })]),
    host: host(),
    backups: [backup(2), dump(2)],
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
    /** No services at all is yellow, since every declared service is required. */
    const { level, triggers } = summarise({ table: table([]), now: NOW })

    expect(level).toBe("warn")
    expect(triggers[0].text).toBe("There is no service at all - Docker returned an empty list.")
    expect(triggers[0].to).toBe("/operations/updates")
    expect(triggers[0].subject).toBe("services")
  })

  it("says nothing about an empty list that was never asked for", () => {
    /** Undefined is "not answered yet", which must not accuse the host. */
    expect(summarise({ host: host(), backups: withDump(backup(1)), now: NOW }).level).toBe("ok")
  })
})

describe("summarise - a standby that is off", () => {
  it("a stopped standby is not a fault, because being off is what it is for", () => {
    /** Standbys are stopped nearly all season, so a stopped standby is no fault. */
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([
        service({ service: "proxy-standby", state: "exited", status: "Exited (0) 3 days ago", standby: true }),
        service({ service: "limbo-standby", state: "created", status: "Created", standby: true }),
      ]),
    })

    expect(level).toBe("ok")
    expect(triggers).toHaveLength(0)
  })

  it("a standby that is RUNNING and unhealthy is still red, because that is the minute it matters", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ service: "proxy-standby", state: "running", health: "unhealthy", standby: true })]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("proxy-standby is running, but reports itself unhealthy.")
  })

  it("the marker has to come from the worker - a stopped service without it is still a fault", () => {
    /** The exemption is keyed on `standby === true`, never on the name. */
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service({ service: "proxy-standby", state: "exited", status: "Exited (1)" })]),
    })

    expect(level).toBe("down")
    expect(triggers).toHaveLength(1)
  })
})

describe("summarise - a service somebody put down on purpose", () => {
  const SINCE = new Date(NOW - HOUR).toISOString()

  it("two held services are not two faults, because somebody decided both of them", () => {
    /** A hold is a stop an operator asked for, known only from `service_hold`, so it is no fault. */
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([
        service({
          service: "smp",
          state: "exited",
          status: "Exited (0) 5 minutes ago",
          hold: { since: SINCE },
        }),
        service({
          service: "hunger-games",
          state: "exited",
          status: "Exited (0) 5 minutes ago",
          hold: { since: SINCE },
        }),
      ]),
    })

    expect(level).toBe("ok")
    expect(triggers).toHaveLength(0)
  })

  it("a held service that is RUNNING and unhealthy is still red", () => {
    /** A held container that is up and unhealthy is a fault again. */
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([
        service({
          service: "smp",
          state: "running",
          health: "unhealthy",
          hold: { since: SINCE },
        }),
      ]),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("smp is running, but reports itself unhealthy.")
  })

  it("a stopped service beside a held one is still a fault of its own", () => {
    // A hold on one service must not quiet an outage on another.
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([
        service({
          service: "smp",
          state: "exited",
          status: "Exited (0) 5 minutes ago",
          hold: { since: SINCE },
        }),
        service({ service: "proxy", state: "exited", status: "Exited (1) 1 minute ago" }),
      ]),
    })

    expect(level).toBe("down")
    expect(triggers).toHaveLength(1)
    expect(triggers[0].subject).toBe("proxy")
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
    // The short word the start page prints instead of the sentence above.
    expect(triggers[0].subject).toBe("smp")
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
    // Restarting is not a good state.
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
    /** Absent or "starting" health is not unhealthy, so a deploy does not paint the page red. */
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
    expect(triggers[0].to).toBe("/operations/updates")
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
    // One trigger naming both services.
    expect(triggers[0].subject).toBe("smp, bot")
  })

  it("says out loud that the comparison did not happen, and repeats the registry's excuse", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([service()], { reached: false, message: "504 vom Proxy" }),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("were not compared")
    expect(triggers[0].text).toContain("504 vom Proxy")
    expect(triggers[0].subject).toBe("registry")
  })

  it("still says something when the registry failed without saying why", () => {
    const { triggers } = summarise({
      ...healthy(),
      table: table([service()], { reached: false, message: undefined }),
    })

    expect(triggers[0].text).toContain("the registry did not answer")
  })

  it("does not turn yellow for a single image that carries no registry digest", () => {
    /** A single unknown image does not raise the light, since a yellow that never clears stops being read. */
    const { level } = summarise({
      ...healthy(),
      table: table([service({ drift: "UNKNOWN" })], { unverifiable: ["smp"] }),
    })

    expect(level).toBe("ok")
  })

  it("does not turn yellow for a locally built image - LOCAL is not a milder OUTDATED", () => {
    /** Images built locally are ahead of the registry, not behind it, so this trigger stays silent. */
    const { level, triggers } = summarise({
      ...healthy(),
      table: table([
        service({ service: "steward-ui", drift: "LOCAL" }),
        service({ service: "steward-worker", drift: "LOCAL" }),
      ]),
    })

    expect(level).toBe("ok")
    expect(triggers).toHaveLength(0)
  })
})

describe("summarise - the backup", () => {
  it("says nothing when the backup list was never asked for", () => {
    /** Undefined backups mean "not asked yet", not "no backup". */
    expect(summarise({ table: table([service()]), host: host(), now: NOW }).level).toBe("ok")
  })

  it("is red when the list was answered and is empty", () => {
    const { level, triggers } = summarise({ ...healthy(), backups: [] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("There is not a single backup.")
    expect(triggers[0].to).toBe("/operations/backups")
    expect(triggers[0].subject).toBe("backups")
  })

  it("distinguishes no backup at all from one that was only ever started", () => {
    /** A .partial file must not read as an archive. */
    const { level, triggers } = summarise({ ...healthy(), backups: [backup(1, { partial: true })] })

    expect(level).toBe("down")
    expect(triggers[0].text).toBe("There is no finished backup - only started ones (.partial).")
  })

  it("is quiet for a backup exactly at the age the thresholds still allow", () => {
    /** At exactly the threshold a backup is still young enough. */
    expect(summarise({ ...healthy(), backups: withDump(backup(DEFAULT_THRESHOLDS.backupAgeHours)) }).level).toBe("ok")
  })

  it("is red a minute past that age, and says what the limit was", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      backups: withDump(backup(DEFAULT_THRESHOLDS.backupAgeHours + 1 / 60)),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("36 hours")
    /** Pins that the sentence says how old, in `relative`'s calendar words. */
    expect(triggers[0].text).toContain("2 days ago")
    // The subject is the volume name.
    expect(triggers[0].subject).toBe("nordtal-s2_mc-smp")
  })

  it("judges the newest finished archive, not the first row in the list", () => {
    const { level } = summarise({
      ...healthy(),
      backups: withDump(backup(200, { name: "alt.tar.zst" }), backup(3), backup(80)),
    })

    expect(level).toBe("ok")
  })

  it("does not let a fresh .partial rescue an old finished backup", () => {
    /** A half-written archive is no reason to believe there is a backup. */
    const { level, triggers } = summarise({
      ...healthy(),
      backups: withDump(backup(40), backup(0.5, { partial: true, name: "fresh.tar.zst.partial" })),
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("older than the permitted")
  })

  it("is red rather than quietly fine when the newest archive has no readable timestamp", () => {
    /** A NULL date arrives as the word "null", and a NaN age is not young enough. */
    const { level } = summarise({ ...healthy(), backups: withDump(backup(1, { modified: "null" })) })

    expect(level).toBe("down")
  })

  it("honours a threshold the deployment changed", () => {
    const thresholds = { ...DEFAULT_THRESHOLDS, backupAgeHours: 6 }

    expect(summarise({ ...healthy(), backups: withDump(backup(5)), thresholds }).level).toBe("ok")
    expect(summarise({ ...healthy(), backups: withDump(backup(7)), thresholds }).level).toBe("down")
  })
})

describe("summarise - the database dump, which is not a volume archive", () => {
  /**
   * The database dump, checked on its own.
   *
   * Volume archives can succeed while no dump is written, and accesses, payments and links cannot be rebuilt.
   */

  it("is red when every archive is fresh and there is no dump at all", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      backups: [backup(0.5), backup(0.5, { name: "nordtal-s2_mc-smp-20260915T024543Z.tar.zst" })],
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("database")
    expect(triggers[0].to).toBe("/operations/backups")
    expect(triggers[0].subject).toBe("database dump")
  })

  it("is quiet once a dump is there beside the archives", () => {
    expect(summarise({ ...healthy(), backups: [backup(2), dump(2)] }).level).toBe("ok")
  })

  it("does not accept a .partial dump as a dump", () => {
    /** `partial` is trusted rather than the suffix. */
    const { level } = summarise({
      ...healthy(),
      backups: [backup(1), dump(0.1, { partial: true, name: "nordtal-20260915T024501Z.dump.partial" })],
    })

    expect(level).toBe("down")
  })

  it("judges the dump's own age, not the age of the newest file in the directory", () => {
    /** Fresh archives beside a week-old dump are a half-failing run. */
    const { level, triggers } = summarise({ ...healthy(), backups: [backup(0.5), dump(200)] })

    expect(level).toBe("down")
    expect(triggers.some((trigger) => trigger.text.includes("dump"))).toBe(true)
  })

  it("says so about the archives too when only a dump is there", () => {
    /** A dump with no archives is half a backup too. */
    const { level, triggers } = summarise({ ...healthy(), backups: [dump(1)] })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("archive")
  })

  it("still says there is no backup at all rather than naming one of the two kinds", () => {
    /** An empty directory keeps its own sentence ahead of both dump triggers. */
    const { triggers } = summarise({ ...healthy(), backups: [] })

    expect(triggers[0].text).toBe("There is not a single backup.")
  })

  it("asks whether a dump is there without waiting for /api/settings", () => {
    // Presence needs no threshold; the age does, see the pair below.
    const { level, triggers } = summarise({ ...blind(), backups: [backup(1)] })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("database")
  })

  it("says nothing about the dump's age while the thresholds have not arrived", () => {
    const { level, triggers } = summarise({ ...blind(), backups: [backup(1), dump(500)] })

    expect(level).toBe("ok")
    expect(triggers).toEqual([])
  })
})

describe("summarise - one volume out of eight", () => {
  /** Every volume is judged on its own, so fresh small volumes cannot hide a stale world volume. */

  it("is red when one volume has gone stale behind seven fresh ones", () => {
    const { level, triggers } = summarise({
      ...healthy(),
      backups: [
        dump(1),
        archiveOf("nordtal-s2_mc-smp", 21 * 24),
        archiveOf("nordtal-s2_bot-config", 1),
        archiveOf("nordtal-s2_steward-ui-config", 1),
      ],
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("nordtal-s2_mc-smp")
    expect(triggers[0].text).toContain("older than the permitted")
    // The subject is the volume name alone.
    expect(triggers[0].subject).toBe("nordtal-s2_mc-smp")
  })

  it("names every volume that has gone stale, not just the first one it met", () => {
    const { triggers } = summarise({
      ...healthy(),
      backups: [
        dump(1),
        archiveOf("nordtal-s2_mc-smp", 21 * 24),
        archiveOf("nordtal-s2_mc-limbo", 21 * 24),
        archiveOf("nordtal-s2_bot-config", 1),
      ],
    })

    const said = triggers.map((trigger) => trigger.text).join(" ")
    expect(said).toContain("nordtal-s2_mc-smp")
    expect(said).toContain("nordtal-s2_mc-limbo")
  })

  it("is quiet when every volume has something from tonight", () => {
    expect(
      summarise({
        ...healthy(),
        backups: [dump(1), archiveOf("nordtal-s2_mc-smp", 1), archiveOf("nordtal-s2_bot-config", 2)],
      }).level,
    ).toBe("ok")
  })

  it("judges each volume by its own newest, not by the directory's", () => {
    // An ancient archive beside a fresh one of the same volume is no accusation.
    const { level } = summarise({
      ...healthy(),
      backups: [dump(1), archiveOf("nordtal-s2_mc-smp", 500), archiveOf("nordtal-s2_mc-smp", 1)],
    })

    expect(level).toBe("ok")
  })

  it("does not count a file it cannot classify as a volume archive", () => {
    /** Neither an `.unverified` mark nor a hand-written note counts as an archive. */
    const { level, triggers } = summarise({
      ...healthy(),
      backups: [
        dump(1),
        backup(1, { name: "nordtal-s2_mc-smp-20260912T044500Z.tar.zst.unverified" }),
        backup(1, { name: "README.txt" }),
      ],
    })

    expect(level).toBe("down")
    expect(triggers[0].text).toContain("archive")
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
    expect(triggers[0].subject).toBe("disk")
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
    /** The percentage is printed as a whole number. */
    const { triggers } = summarise({
      ...healthy(),
      host: host({ diskTotalBytes: 100_000_000_000, diskUsedBytes: 87_456_700_000 }),
    })

    expect(triggers[0].text).toContain("87 % full")
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
    /** Only available memory is alarmed on, since cache counts as in use. */
    const { level, triggers } = summarise({
      ...healthy(),
      host: host({ memoryTotalBytes: 8_000_000_000, memoryAvailableBytes: 400_000_000 }),
    })

    expect(level).toBe("warn")
    expect(triggers[0].text).toContain("95 % used")
    expect(triggers[0].text).toContain("threshold 90 %")
    expect(triggers[0].subject).toBe("memory")
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
    /** Missing disk fields stay silent rather than print "NaN % full". */
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
    expect(summarise({ table: table([service()]), backups: withDump(backup(2)), now: NOW }).level).toBe("ok")
  })
})

describe("summarise - several things at once", () => {
  it("lists a yellow trigger next to a red one rather than hiding it behind the worse news", () => {
    /** Two faults are both shown. */
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
    /** With nothing to judge there is no fault; the next describe handles doubt. */
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
    // Doubt can only ever make the light worse.
    for (const level of ["ok", "warn", "down"] as Level[]) {
      const shown = shownLevel(level, true)
      const rank = { ok: 0, warn: 1, down: 2 }

      expect(rank[shown]).toBeGreaterThanOrEqual(rank[level])
    }
  })

  it("keeps the light and the sentence saying the same thing with everything failed", () => {
    /** A page whose every query failed is never silently "ok". */
    const { level, triggers } = summarise({})

    expect(triggers).toEqual([])
    expect(shownLevel(level, true)).toBe("warn")
    expect(UNKNOWN).toContain("cannot be said")
  })
})

/** The thresholds are an input, not a default: a check that needs a number is skipped without one. */

/** The healthy stack before `/api/settings` has answered. */
function blind() {
  const { thresholds: _thresholds, ...rest } = healthy()
  return rest
}

describe("summarise - with no thresholds, the checks that need a number", () => {
  it("does not judge the disk at all, not even one that is nearly full", () => {
    /** A full disk stays silent while the threshold it would be compared with is missing. */
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
    /** A stale backup is green without thresholds; overview.tsx shows the unanswered settings instead. */
    const { level, triggers } = summarise({ ...blind(), backups: withDump(backup(500)) })

    expect(level).toBe("ok")
    expect(triggers).toEqual([])
  })

  it("is red about the very same backup the moment the thresholds arrive", () => {
    /** The pair to the test above: only the thresholds differ. */
    const { level } = summarise({ ...blind(), thresholds: DEFAULT_THRESHOLDS, backups: withDump(backup(500)) })

    expect(level).toBe("down")
  })

  it("does not quietly reach for DEFAULT_THRESHOLDS at any of its three numbers", () => {
    /** Each value is past the default and must stay silent, so a `?? DEFAULT_THRESHOLDS` fallback turns this red. */
    const over = [
      { ...blind(), host: host({ diskTotalBytes: 100e9, diskUsedBytes: 90e9 }) },
      { ...blind(), host: host({ memoryTotalBytes: 8e9, memoryAvailableBytes: 400e6 }) },
      { ...blind(), backups: withDump(backup(DEFAULT_THRESHOLDS.backupAgeHours + 12)) },
    ]

    for (const input of over) {
      expect(summarise(input).level).toBe("ok")
      expect(summarise({ ...input, thresholds: DEFAULT_THRESHOLDS }).level).not.toBe("ok")
    }
  })

  it("is silent about a backup whose timestamp cannot be read, which is not obviously right", () => {
    /** An unreadable date is skipped without thresholds, since it is judged as an age. */
    expect(summarise({ ...blind(), backups: withDump(backup(1, { modified: "null" })) }).level).toBe("ok")
    expect(
      summarise({ ...blind(), thresholds: DEFAULT_THRESHOLDS, backups: withDump(backup(1, { modified: "null" })) })
        .level,
    ).toBe("down")
  })
})

describe("summarise - with no thresholds, the checks that need no number", () => {
  it("still says there is no backup at all", () => {
    /** Whether there is an archive needs no number, so it never waits for /api/settings. */
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
    // Two faults, worst first, whichever branches ran.
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
 * `DEFAULT_THRESHOLDS` against `UiSpec.AlertSpec`, read as text.
 *
 * A pattern that stops matching throws with the line it was looking for.
 */

import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

/** Three levels up from this file is steward-ui/, never a path from the cwd. */
const STEWARD_UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..")

function read(relative: string): string {
  const file = path.join(STEWARD_UI, relative)
  if (!fs.existsSync(file)) {
    throw new Error(`${file} is not there - has the module been moved out from under this test?`)
  }
  return fs.readFileSync(file, "utf8")
}

/** `diskPercent` to 85, from the interface's method bodies. */
function specDefault(java: string, method: string): number {
  const found = java.match(new RegExp(`default\\s+int\\s+${method}\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+(\\d+)\\s*;`))
  if (!found) {
    throw new Error(
      `UiSpec.java has no "default int ${method}() { return <n>; }" any more - the defaults have ` +
        `changed shape, and this mirror has to be rewritten rather than deleted`,
    )
  }
  return Number(found[1])
}

/** `disk` to `diskPercent`, from the three `config.alerts()` calls behind /api/settings. */
function wiring(java: string): Record<string, string> {
  const found = [...java.matchAll(/"(\w+)",\s*config\.alerts\(\)\.(\w+)\(\)/g)]
  if (found.length === 0) {
    throw new Error(
      "Settings.java no longer builds /api/settings out of config.alerts() - the mapping this " +
        "test reads is gone and the mirror has to be rewritten",
    )
  }
  return Object.fromEntries(found.map(([, key, method]) => [key, method]))
}

describe("DEFAULT_THRESHOLDS - the mirror of UiSpec.AlertSpec", () => {
  const spec = read("src/main/java/eu/nordtal/s2/steward/ui/config/UiSpec.java")
  const routes = read("src/main/java/eu/nordtal/s2/steward/ui/Settings.java")

  it("carries the same three numbers steward-ui.yml would be written with", () => {
    /** The shipped defaults must match the constant. */
    const mirrored = Object.fromEntries(
      Object.entries(wiring(routes)).map(([key, method]) => [key, specDefault(spec, method)]),
    )

    expect(mirrored).toEqual({ ...DEFAULT_THRESHOLDS })
  })

  it("is about the same three keys the route actually sends", () => {
    /** The key names must match too, or every comparison is quietly false. */
    expect(Object.keys(wiring(routes)).toSorted()).toEqual(Object.keys(DEFAULT_THRESHOLDS).toSorted())
  })
})
