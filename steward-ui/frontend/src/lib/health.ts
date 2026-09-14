import type { Backup, Host, ServiceTable } from "@/lib/api"
import { bytes, percent, relative } from "@/lib/format"

/**
 * The Ampel of concept §10c.
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
 * **One sentence, not a field of symbols.** `summarise` returns the worst level and the reasons, in
 * order, and the page prints them as prose. A red trigger outranks a yellow one and never hides it:
 * both are listed, because "a service is down" and "the backup is missing" are two errands.
 */

export type Level = "ok" | "warn" | "down"

export type Trigger = {
  level: Exclude<Level, "ok">
  /** One sentence, already German, already complete. */
  text: string
  /** Where to go and do something about it. */
  to?: string
  params?: Record<string, string>
}

export type Thresholds = {
  /** Percent of the disk in use above which the Ampel turns yellow. */
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
      text: "Es ist kein Dienst da - Docker hat eine leere Liste geliefert.",
      to: "/betrieb",
    })
  }

  // 1 - a service stopped or unhealthy. Red: without this it would not be a traffic light.
  for (const service of input.table?.services ?? []) {
    if (!isUp(service.state)) {
      triggers.push({
        level: "down",
        text: `${service.service} läuft nicht (${service.status || service.state}).`,
        to: "/dienste/$name",
        params: { name: service.service },
      })
    } else if (service.health === "unhealthy") {
      triggers.push({
        level: "down",
        text: `${service.service} läuft, meldet sich aber als unhealthy.`,
        to: "/dienste/$name",
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
          ? `${outdated[0].service} läuft auf einem älteren Image als die Registry hat.`
          : `${outdated.length} Dienste laufen auf einem älteren Image als die Registry hat: ` +
            outdated.map((service) => service.service).join(", ") + ".",
      to: "/betrieb",
    })
  }

  // The comparison that could not be made is its own sentence, and a quiet one. It is not a fault
  // in the stack - but reporting "up to date" for an image nobody compared is the fault above.
  //
  // A SINGLE service whose own drift is UNKNOWN is deliberately NOT a trigger (Till, 2026-09-13).
  // status.tsx argues the opposite for the badge, and A24 is this light's whole reason to exist -
  // but several images here are built on this host and published nowhere, so UNKNOWN is their
  // ordinary state and a yellow that never clears is a light nobody reads. The Betrieb page
  // footnotes how many could not be compared. This trigger is the other case: the registry as a
  // whole did not answer, which is temporary and therefore worth a sentence.
  if (input.table && input.table.drift.reached === false) {
    triggers.push({
      level: "warn",
      text: `Die Images wurden nicht verglichen: ${input.table.drift.message ?? "die Registry antwortete nicht"}.`,
      to: "/betrieb",
    })
  }

  // 3 - the backup. Red, and this one is about the archive on the disk, not about a run that
  // reported success.
  triggers.push(...backupTriggers(input.backups, thresholds, now))

  // 4 - disk and memory over the configured thresholds. Yellow.
  const host = input.host
  if (thresholds && host?.diskTotalBytes && host.diskUsedBytes != null) {
    const used = (host.diskUsedBytes / host.diskTotalBytes) * 100
    if (used >= thresholds.disk) {
      triggers.push({
        level: "warn",
        text: `Die Platte ist zu ${percent(used, 0)} belegt (${bytes(host.diskUsedBytes)} von ${bytes(host.diskTotalBytes)}), Schwelle ${thresholds.disk} %.`,
      })
    }
  }
  if (thresholds && host?.memoryTotalBytes && host.memoryAvailableBytes != null) {
    const used = ((host.memoryTotalBytes - host.memoryAvailableBytes) / host.memoryTotalBytes) * 100
    if (used >= thresholds.memory) {
      triggers.push({
        level: "warn",
        text: `Der Speicher ist zu ${percent(used, 0)} belegt, Schwelle ${thresholds.memory} %. Kein Container hat ein Limit, das ist also der ganze Host.`,
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

function backupTriggers(
  backups: Backup[] | undefined,
  thresholds: Thresholds | undefined,
  now: number,
): Trigger[] {
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
            ? "Es liegt keine fertige Sicherung vor - nur angefangene (.partial)."
            : "Es liegt keine einzige Sicherung vor.",
        to: "/betrieb",
      },
    ]
  }

  // That there is no archive at all needs no threshold and is reported either way. How old one is
  // allowed to be is a configured number, so until it has arrived this says nothing about age.
  if (!thresholds) return []

  const newest = finished.reduce((latest, backup) =>
    backup.modified > latest.modified ? backup : latest,
  )
  const age = (now - new Date(newest.modified).getTime()) / 3_600_000
  if (!Number.isFinite(age) || age > thresholds.backupAgeHours) {
    return [
      {
        level: "down",
        text: `Die neueste Sicherung ist von ${relative(newest.modified, now)} - älter als die erlaubten ${thresholds.backupAgeHours} Stunden.`,
        to: "/betrieb",
      },
    ]
  }
  return []
}

/**
 * The level the light actually shows, which is not always the level that was measured.
 *
 * A green light on no evidence is the one thing this page must not do. With every query failed
 * there is nothing to summarise, so `summarise` returns no trigger and the level is "ok" - and the
 * page drew the green tick and {@link ALL_CLEAR} above a grey footnote saying the opposite. Not
 * knowing is yellow. It is this file's own argument: A24 went unnoticed for four releases because
 * nothing said it did not know.
 *
 * A measured warning or a measured failure outranks the doubt and is shown as it is - "a service
 * is down" is a more useful sentence than "something could not be read".
 */
export function shownLevel(level: Level, failed: boolean): Level {
  return failed && level === "ok" ? "warn" : level
}

/** What the Ampel says when it found nothing wrong and also could not look. */
export const UNKNOWN = "Ob alles in Ordnung ist, lässt sich gerade nicht sagen."

/** The sentence at the top of the start page when nothing is wrong. */
export const ALL_CLEAR = "Alles in Ordnung."
