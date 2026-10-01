import type { Backup, Host, ServiceTable } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, percent, relative } from "@/lib/format"

/**
 * The start page's traffic light: the worst level and every trigger behind it, worst first.
 *
 * A red trigger outranks a yellow one and never hides it; the page prints each trigger's `subject`.
 */

export type Level = "ok" | "warn" | "down"

export type Trigger = {
  level: Exclude<Level, "ok">
  /** One complete sentence, for the page the trigger links to. */
  text: string
  /** The word or comma-joined words this trigger is about, such as "smp" or "disk"; never a sentence. */
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
 * What the `web` group defaults to, for tests and never as a fallback.
 *
 * Without the server's thresholds the checks that need them do not run, so screen and Discord agree.
 */
export const DEFAULT_THRESHOLDS: Thresholds = { disk: 85, memory: 90, backupAgeHours: 36 }

/** Whether Docker calls this state fine; `restarting` is not, since a crash loop looks busy. */
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
  /** Undefined until `/api/settings` answers; every check needing a number is skipped until then. */
  const thresholds = input.thresholds
  const now = input.now ?? Date.now()
  const triggers: Trigger[] = []

  /** 0: an answered, empty service list is yellow, since no data must not read as fine. */
  if (input.table && input.table.services.length === 0) {
    triggers.push({
      level: "warn",
      text: "There is no service at all - Docker returned an empty list.",
      subject: "services",
      to: "/operations/updates",
    })
  }

  /**
   * 1: a service stopped or unhealthy is red.
   *
   * A stopped standby is exempt, marked by steward, since stopping is what it is for.
   */
  for (const service of input.table?.services ?? []) {
    if (service.standby === true && !isUp(service.state)) {
      continue
    }
    /**
     * A held service that is stopped is exempt too; `hold` is the only thing telling a deliberate stop from a crash.
     */
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

  /** 2: image drift is yellow, since nothing is broken yet it is easy to miss. */
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

  /**
   * A registry that did not answer at all is its own quiet trigger.
   *
   * One service's UNKNOWN drift is too common to be one, and LOCAL is a known answer.
   */
  if (input.table && !input.table.drift.reached) {
    triggers.push({
      level: "warn",
      text: `The images were not compared: ${input.table.drift.message ?? "the registry did not answer"}.`,
      subject: "registry",
      to: "/operations/updates",
    })
  }

  /** 3: the backup files on disk, both kinds, are red when missing or too old. */
  triggers.push(...backupTriggers(input.backups, thresholds, now))

  // 4: disk and memory over the configured thresholds. Yellow.
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

  // Red first.
  triggers.sort((left, right) => (left.level === right.level ? 0 : left.level === "down" ? -1 : 1))
  return { level, triggers }
}

/**
 * The newest of one series, held against the permitted age.
 *
 * A series is one volume or the dump, so one failing volume never hides behind the others.
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
  /** Undefined means not asked yet; only an answered, empty list means there is no backup. */
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

  /**
   * Both kinds are required, since archives can exist where pg_dump has never succeeded.
   *
   * `archived` is the same classifier the Operations page uses.
   */
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

  /** Presence needs no threshold, so neither of these waits for `/api/settings`. */
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
 * The level the light shows: yellow instead of green when every query failed.
 *
 * A measured warning or failure is shown as it is.
 */
export function shownLevel(level: Level, failed: boolean): Level {
  return failed && level === "ok" ? "warn" : level
}

/** What the light says when it found nothing wrong and could not look either. */
export const UNKNOWN = "Whether everything is in order cannot be said right now."
