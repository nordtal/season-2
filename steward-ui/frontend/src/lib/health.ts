import type { Backup, Host, ServiceTable } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, percent, relative } from "@/lib/format"

/**
 * The traffic light of concept §10c.
 *
 * Four triggers, and the point is which four. Two of them are the two failures measured on this
 * host on 2026-09-12:
 *
 * - **A24** — four releases shipped while the running containers kept an older image, and nothing
 *   said so, because the thing doing the deploying never asked a registry.
 * - **A23** — run 23 reported success having saved zero volumes. "The run did not complain" is not
 *   "there is a backup".
 *
 * Had this existed, neither would have gone unnoticed. That is the argument for these four and not
 * for a wall of tiles.
 *
 * **Reasons, not a field of symbols.** `summarise` returns the worst level and every trigger that
 * produced it, in order, worst first. A red trigger outranks a yellow one and never hides it: both
 * are counted, because "a service is down" and "the backup is missing" are two errands.
 *
 * **`text` is the whole sentence; `subject` is what the start page actually prints** (steward/64).
 * The page used to render `text` for every trigger, one line each - which is exactly the "wall of
 * tiles" this file argued against replacing itself with a wall of sentences instead. It now prints
 * "Errors in {subjects}" plus one link, and `text` stays here for the page that link leads to: the
 * full sentence never had to move, because nothing outside `overview.tsx` ever read it.
 */

export type Level = "ok" | "warn" | "down"

export type Trigger = {
  level: Exclude<Level, "ok">
  /** One sentence, already written out, already complete. */
  text: string
  /**
   * The word (or short, comma-joined list of words) this trigger is about - "smp", "disk", "database
   * dump". Never a sentence.
   *
   * steward/64: the start page stopped printing `text` for warn/down and prints "Errors in
   * {subjects}" instead, because Till's own words for what should replace a screenful of prose were
   * a reference, not a second sentence per trigger. `text` still exists and still says the whole
   * thing - it is what the page this trigger links to is for - so a page that wants a service name
   * or a volume name does not have to parse it back out of the prose.
   */
  subject: string
  /** Where to go and do something about it. */
  to?: string
  params?: Record<string, string>
}

export type Thresholds = {
  /** Percent of the disk in use above which the traffic light turns yellow. */
  disk: number
  /** Percent of host memory in use above which it turns yellow. */
  memory: number
  /** How old the newest backup may be before it counts as missing. */
  backupAgeHours: number
}

/**
 * What steward-ui.yml ships with - a copy for tests, and deliberately not a fallback.
 *
 * {@link summarise} used to reach for this whenever `/api/settings` had not answered, which meant
 * the light could judge the disk against 85 % while the deployment said 70, and say nothing about
 * having guessed. The thresholds are configured on the server precisely so that the screen and the
 * Discord channel agree; a browser-side default is the one way they can disagree silently. Without
 * them, the checks that need them do not run.
 */
export const DEFAULT_THRESHOLDS: Thresholds = { disk: 85, memory: 90, backupAgeHours: 36 }

/**
 * A container state Docker calls fine.
 *
 * `running` is the only good state; `restarting` is deliberately not on this list, because a
 * container in a crash loop is `restarting` and looks busy rather than broken.
 */
function isUp(state: string): boolean {
  return state === "running"
}

export function summarise(input: {
  table?: ServiceTable
  host?: Host
  backups?: Backup[]
  thresholds?: Thresholds
  now?: number
}): { level: Level; triggers: Trigger[] } {
  // Undefined until `/api/settings` has answered. Everything that needs a number to compare
  // against is skipped while it is, and the page says so instead of quietly using its own.
  const thresholds = input.thresholds
  const now = input.now ?? Date.now()
  const triggers: Trigger[] = []

  // 0 - an answered, EMPTY service list. Yellow, and Till's call on 2026-09-13.
  //
  // This used to be green, because no service is no failing service. But the list being answered
  // and empty is not "nothing to report": compose.yml declares eight services here and none of
  // them is optional enough to explain an empty table, so the reading is that the daemon has
  // nothing left to show - which is the loudest thing this page could ever have to say. It is the
  // same family as the green light on failed queries: no data must not read as fine.
  if (input.table && input.table.services.length === 0) {
    triggers.push({
      level: "warn",
      text: "There is no service at all - Docker returned an empty list.",
      subject: "services",
      to: "/operations/updates",
    })
  }

  // 1 - a service stopped or unhealthy. Red: without this it would not be a traffic light.
  //
  // WITH ONE EXCEPTION, AND IT IS NOT A SOFTENING (steward/125): a standby that is not running is
  // not a fault, it is the standby doing what it is for. `proxy-standby` and `limbo-standby` live
  // in the `standby` compose profile and are stopped for all but a minute of the season - so
  // without this the front page said "2 issues" on a perfectly healthy stack, every day, which is
  // precisely how a fault counter stops being read and how the third fault goes unnoticed.
  //
  // A standby that IS running and reports itself unhealthy stays red, because that is the one
  // minute it matters. The marker comes from the worker (Topology.standbyNames()) and not from a
  // name match here: a stopped standby and a crashed backend are the same container state, so
  // nothing on this side could tell them apart.
  for (const service of input.table?.services ?? []) {
    if (service.standby === true && !isUp(service.state)) {
      continue
    }
    // The same exemption for the same reason, one ticket later (steward/134): a service somebody
    // put down on purpose is not a fault either. `hold` is the `service_hold` row the worker passes
    // through, and it is the only thing that can tell a deliberate stop from a crash - the
    // container is `exited` in both cases. A held service that is RUNNING and unhealthy falls
    // through to the check below and stays red, exactly as a standby does.
    if (service.hold !== undefined && !isUp(service.state)) {
      continue
    }
    if (!isUp(service.state)) {
      triggers.push({
        level: "down",
        text: `${service.service} is not running (${service.status || service.state}).`,
        subject: service.service,
        to: "/services/$name",
        params: { name: service.service },
      })
    } else if (service.health === "unhealthy") {
      triggers.push({
        level: "down",
        text: `${service.service} is running, but reports itself unhealthy.`,
        subject: service.service,
        to: "/services/$name",
        params: { name: service.service },
      })
    }
  }

  // 2 - image drift. Yellow: nothing is broken, and this is exactly the state that went unnoticed
  // for four releases.
  const outdated = (input.table?.services ?? []).filter((service) => service.drift === "OUTDATED")
  if (outdated.length > 0) {
    triggers.push({
      level: "warn",
      text:
        outdated.length === 1
          ? `${outdated[0].service} is running an older image than the registry has.`
          : `${outdated.length} services are running an older image than the registry has: ` +
            outdated.map((service) => service.service).join(", ") +
            ".",
      subject: outdated.map((service) => service.service).join(", "),
      to: "/operations/updates",
    })
  }

  // The comparison that could not be made is its own sentence, and a quiet one. It is not a fault
  // in the stack - but reporting "up to date" for an image nobody compared is the fault above.
  //
  // A SINGLE service whose own drift is UNKNOWN is deliberately NOT a trigger (Till, 2026-09-13).
  // overview.tsx argues the opposite for the badge, and A24 is this light's whole reason to exist -
  // but a registry that could not be asked about one image, or a container whose exact image is no
  // longer on file locally, is not rare enough here for a yellow that never clears to be worth
  // reading. The Operations page footnotes how many could not be compared. This trigger is the
  // other case: the registry as a whole did not answer, which is temporary and therefore worth a
  // sentence.
  //
  // LOCAL is not checked here at all, on purpose (steward/75): it is the opposite direction from
  // OUTDATED, not a milder version of it, and a service built here and never published is a known
  // answer - never "nobody looked", never a reason to turn the light yellow.
  if (input.table && input.table.drift.reached === false) {
    triggers.push({
      level: "warn",
      text: `The images were not compared: ${input.table.drift.message ?? "the registry did not answer"}.`,
      subject: "registry",
      to: "/operations/updates",
    })
  }

  // 3 - the backup. Red, and this one is about the files on the disk, not about a run that
  // reported success. Since steward/40 it asks for both kinds of file: a run that reports success
  // having written only half of them is the shape this stack actually had.
  triggers.push(...backupTriggers(input.backups, thresholds, now))

  // 4 - disk and memory over the configured thresholds. Yellow.
  const host = input.host
  if (thresholds && host?.diskTotalBytes && host.diskUsedBytes != null) {
    const used = (host.diskUsedBytes / host.diskTotalBytes) * 100
    if (used >= thresholds.disk) {
      triggers.push({
        level: "warn",
        text: `The disk is ${percent(used, 0)} full (${bytes(host.diskUsedBytes)} of ${bytes(host.diskTotalBytes)}), threshold ${thresholds.disk} %.`,
        subject: "disk",
      })
    }
  }
  if (thresholds && host?.memoryTotalBytes && host.memoryAvailableBytes != null) {
    const used = ((host.memoryTotalBytes - host.memoryAvailableBytes) / host.memoryTotalBytes) * 100
    if (used >= thresholds.memory) {
      triggers.push({
        level: "warn",
        text: `Memory is ${percent(used, 0)} used, threshold ${thresholds.memory} %. No container has a limit, so this is the whole host.`,
        subject: "memory",
      })
    }
  }

  const level: Level = triggers.some((trigger) => trigger.level === "down")
    ? "down"
    : triggers.length > 0
      ? "warn"
      : "ok"

  // Red first. Two errands sorted by which one is on fire.
  triggers.sort((left, right) => (left.level === right.level ? 0 : left.level === "down" ? -1 : 1))
  return { level, triggers }
}

/**
 * The newest of one series, held against the one permitted age.
 *
 * A series is one volume, or the database dump. Never the whole directory: sixteen files from
 * tonight and a world from three weeks ago make "the newest backup" minutes old, and a per-volume
 * tar failure - which is what a missing mount produces, one FAILED line for that volume alone -
 * hides behind the seven small ones that succeeded. `TarSnapshots.prune` counts per volume for the
 * same reason and would otherwise keep fourteen of whichever was written last.
 */
function tooOld(rows: Backup[], what: string, subject: string, thresholds: Thresholds, now: number): Trigger[] {
  if (rows.length === 0) return []

  const newest = rows.reduce((latest, row) => (row.modified > latest.modified ? row : latest))
  const age = (now - new Date(newest.modified).getTime()) / 3_600_000
  if (!Number.isFinite(age) || age > thresholds.backupAgeHours) {
    return [
      {
        level: "down",
        text: `The newest ${what} is from ${relative(newest.modified, now)} - older than the permitted ${thresholds.backupAgeHours} hours.`,
        subject,
        to: "/operations/backups",
      },
    ]
  }
  return []
}

function backupTriggers(backups: Backup[] | undefined, thresholds: Thresholds | undefined, now: number): Trigger[] {
  // Undefined means "not asked yet" and must not read as "there is no backup". Only an answered,
  // empty list is an accusation.
  if (backups === undefined) return []

  const finished = backups.filter((backup) => !backup.partial)
  if (finished.length === 0) {
    return [
      {
        level: "down",
        text:
          backups.length > 0
            ? "There is no finished backup - only started ones (.partial)."
            : "There is not a single backup.",
        subject: "backups",
        to: "/operations/backups",
      },
    ]
  }

  // BOTH KINDS ARE REQUIRED, and this is steward/40. Counting files in `/backups` finds plenty on
  // a stack where pg_dump has never once succeeded: the volume archives are written by a root
  // container and the dump runs as `postgres` against a root-owned directory (steward/39). The
  // nightly run came back FAILED on 2026-09-15 and this page drew the green tick over it, because
  // sixteen fresh .tar.zst answered the only question it asked.
  //
  // The asymmetry is why it matters rather than being pedantry: a world and a set of configs can be
  // rebuilt from the repository and a paintbrush. The accesses, payments and Discord links cannot.
  // `archived` is the same classifier the Operations page lists the directory with, and asking it
  // rather than the suffix is the difference between "not a dump" and "is an archive": an
  // `.unverified` mark and a README an operator dropped in are neither.
  const dumps: Backup[] = []
  const volumes = new Map<string, Backup[]>()
  for (const backup of finished) {
    const what = archived(backup.name)
    if (what.kind === "database") {
      dumps.push(backup)
    } else if (what.kind === "volume" && what.subject) {
      const series = volumes.get(what.subject)
      if (series) series.push(backup)
      else volumes.set(what.subject, [backup])
    }
  }
  const triggers: Trigger[] = []

  // Presence is not a number, so neither of these waits for /api/settings - same argument as "there
  // is not a single backup" above. The age below is a number and does wait.
  if (dumps.length === 0) {
    triggers.push({
      level: "down",
      text:
        "There is no database dump - only volume archives. The worlds and configurations are" +
        " saved, the accesses and payments are not.",
      subject: "database dump",
      to: "/operations/backups",
    })
  }
  if (volumes.size === 0) {
    triggers.push({
      level: "down",
      text: "There is no volume archive - only a database dump.",
      subject: "backups",
      to: "/operations/backups",
    })
  }

  if (!thresholds) return triggers

  for (const [volume, series] of volumes) {
    triggers.push(...tooOld(series, `archive of ${volume}`, volume, thresholds, now))
  }
  triggers.push(...tooOld(dumps, "database dump", "database dump", thresholds, now))
  return triggers
}

/**
 * The level the light actually shows, which is not always the level that was measured.
 *
 * A green light on no evidence is the one thing this page must not do. With every query failed
 * there is nothing to summarise, so `summarise` returns no trigger and the level is "ok" - and the
 * page used to draw the green tick and its all-clear sentence above a grey footnote saying the
 * opposite. Not knowing is yellow. It is this file's own argument: A24 went unnoticed for four
 * releases because nothing said it did not know.
 *
 * A measured warning or a measured failure outranks the doubt and is shown as it is - "a service
 * is down" is a more useful sentence than "something could not be read".
 */
export function shownLevel(level: Level, failed: boolean): Level {
  return failed && level === "ok" ? "warn" : level
}

/** What the traffic light says when it found nothing wrong and also could not look. */
export const UNKNOWN = "Whether everything is in order cannot be said right now."

// There used to be an ALL_CLEAR string here - "Everything is in order.", printed above the light
// whenever `ok` had nothing to report. steward/64 removed the banner for that case entirely rather
// than replacing its text: Till's own instruction was that a stack with nothing wrong should not
// say so, it should simply start with the numbers. The constant is gone with the sentence, not kept
// unused - the only thing that read it was the page itself, and `shownLevel` above already argues
// why a green line printed on no evidence at all is worse than none.
