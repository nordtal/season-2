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
  const thresholds = input.thresholds ?? DEFAULT_THRESHOLDS
  const now = input.now ?? Date.now()
  const triggers: Trigger[] = []

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
  if (host?.diskTotalBytes && host.diskUsedBytes != null) {
    const used = (host.diskUsedBytes / host.diskTotalBytes) * 100
    if (used >= thresholds.disk) {
      triggers.push({
        level: "warn",
        text: `Die Platte ist zu ${percent(used, 0)} belegt (${bytes(host.diskUsedBytes)} von ${bytes(host.diskTotalBytes)}), Schwelle ${thresholds.disk} %.`,
      })
    }
  }
  if (host?.memoryTotalBytes && host.memoryAvailableBytes != null) {
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
  thresholds: Thresholds,
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

/** The sentence at the top of the start page when nothing is wrong. */
export const ALL_CLEAR = "Alles in Ordnung."
