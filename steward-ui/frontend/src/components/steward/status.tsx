import { cn } from "cn"

import type { Service } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * Status, in the three colours this theme allows.
 *
 * shadcn's `Badge` has `default`, `secondary`, `destructive`, `outline`, `ghost`, `link` - action
 * colours, not status colours. `--success`, `--warning` and `--destructive` are defined in
 * `index.css` precisely so that "good", "careful" and "broken" are three values and not thirty, so
 * this wraps the official component and supplies the tint. One place, not a class chain per table.
 *
 * **Colour is never the only carrier.** Every badge here says a word as well, because a red dot and
 * a green dot are the same dot to a red-green colour-blind reader - and because a screenshot in a
 * chat has no legend.
 */

export type Tone = "ok" | "warn" | "down" | "idle"

const TONES: Record<Tone, string> = {
  ok: "border-success/30 bg-success/12 text-success",
  warn: "border-warning/30 bg-warning/12 text-warning",
  down: "border-destructive/30 bg-destructive/12 text-destructive",
  idle: "border-border bg-secondary text-muted-foreground",
}

export function StatusBadge({
  tone,
  children,
  title,
  className,
}: {
  tone: Tone
  children: React.ReactNode
  title?: string
  className?: string
}) {
  const badge = (
    <Badge
      variant="outline"
      // WHY `max-w-full` AND `truncate` ARE NOT DECORATION (steward/103, measured 2026-09-17).
      //
      // shadcn's `Badge` is `w-fit shrink-0 whitespace-nowrap overflow-hidden`. Every one of those
      // four is deliberate for a badge sitting in a header; together, in a 390px table card, they
      // are a box as wide as its text, refusing to shrink, refusing to wrap, and clipping the
      // remainder WITHOUT an ellipsis. That is the whole of what `/access` looked like at 390px:
      // `active until 1 Dec 2026, 00:0`, the last digit simply gone and nothing saying so.
      //
      // `index.css`'s `.steward-table td > * { max-width: 100% }` does not reach it, because it is
      // one level too shallow - when this badge carries a `title` it sits inside the tooltip's own
      // span, and it is that span the rule caps. So the cap is repeated here, on the thing that
      // actually refuses to shrink, and `truncate` turns the silent cut into an ellipsis.
      //
      // A badge that ends in `…` is still a compromise. Where the text is a DATE the answer is a
      // shorter format instead - see `format.ts#date`; abbreviated is a reading, truncated is not.
      className={cn("max-w-full truncate font-medium", TONES[tone], className)}
    >
      {children}
    </Badge>
  )
  if (!title) return badge
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* `max-w-full` here too: this span is the grid item in a table card, and a badge capped
          * at 100% of a span that is itself wider than the cell is capped at nothing. */}
        <span tabIndex={0} className="inline-flex max-w-full rounded-full focus-visible:outline-none">
          {badge}
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">{title}</TooltipContent>
    </Tooltip>
  )
}

/** The two fields {@link serviceTone} and {@link ServiceState} actually need. */
export type ServiceHealth = Pick<Service, "state" | "health">

/**
 * One service, reduced to the tone the rest of this file already draws in three colours.
 *
 * Pulled out of {@link ServiceState} on 2026-09-16 (steward/83) so that a second drawing of the
 * same state - {@link HealthDot}, for the sidebar and for the network view steward/81 wants - reads
 * the container the same way `ServiceState`'s badge does. Two functions deciding "is this one
 * fine" is exactly how `panel.tsx` drifted from the rest of the app's Caps rule (steward/77): one
 * of them gets fixed and the other is forgotten.
 */
export function serviceTone(service: ServiceHealth): Exclude<Tone, "idle"> {
  if (service.state !== "running") return "down"
  if (service.health === "unhealthy") return "down"
  if (service.health === "starting") return "warn"
  return "ok"
}

/** Docker's container state, with health folded in where there is one. */
export function ServiceState({ state, health }: { state: string; health?: string }) {
  if (state !== "running") {
    return (
      <StatusBadge tone="down" title={`Docker reports the state "${state}".`}>
        {STATES[state] ?? state}
      </StatusBadge>
    )
  }
  const tone = serviceTone({ state, health })
  if (tone === "down") {
    return (
      <StatusBadge tone="down" title="The container is running, but its healthcheck is failing.">
        unhealthy
      </StatusBadge>
    )
  }
  if (tone === "warn") {
    return (
      <StatusBadge tone="warn" title="The healthcheck has not reached a verdict yet.">
        starting
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="ok" title={health ? `Healthcheck: ${health}.` : "Running. No healthcheck."}>
      running
    </StatusBadge>
  )
}

const STATES: Record<string, string> = {
  created: "created",
  restarting: "restarting",
  removing: "removing",
  paused: "paused",
  exited: "exited",
  dead: "dead",
}

const DOT_TONE: Record<Exclude<Tone, "idle">, string> = {
  ok: "bg-success",
  warn: "bg-warning",
  down: "bg-destructive",
}

const DOT_WORD: Record<Exclude<Tone, "idle">, string> = {
  ok: "healthy",
  warn: "starting",
  down: "unhealthy",
}

/**
 * The health of one service, as a dot rather than a badge - built once for two places (steward/83):
 * the sidebar's service list here, and the node steward/81 wants in the network view. Both draw
 * the same state from the same query, so this is the one place that decides what a dot means.
 *
 * **Silence is the fine state - in the sidebar.** A `tone === "ok"` service draws nothing at all,
 * the same call steward/64 already made for the traffic light on the start page: ten identical
 * green dots next to a service list said nothing, and the one row that actually needs a look is
 * what a quiet sidebar makes easy to spot. Decided at the narrow layout, where a row of ten dots
 * costs the most.
 *
 * **In the network view it draws green, and that is not a contradiction.** Till asked for a
 * small dot, green or red, "in any case" - his words are in steward/83 - and when this component
 * answered that wording with silence, the question went back to him rather than being decided
 * here. His answer, 2026-09-16: green in the network view, quiet in the sidebar. A network view is
 * a picture of state, and one whose healthy nodes carry nothing looks like a query that failed; a
 * sidebar is navigation, where nothing *is* the message. steward/81 built the second caller, so
 * `quiet` is that switch now.
 *
 * **`quiet` defaults to `true`, which is today's behaviour for every caller that had one.** The
 * default is the sidebar's, not the network view's, for two reasons: no existing call site
 * changes, and a caller that says nothing gets the cheaper of the two - a dot that appears only
 * when it means something. Going loud is the decision, so going loud is what has to be typed.
 *
 * **`service` undefined is not "fine".** `health.ts` already carries the rule this reuses one
 * level down: *"A green light on no evidence is the one thing this page must not do."* Before
 * `useServices()` has answered - or when a service is missing from an answer that did - this draws
 * a neutral, static dot instead of nothing, so "not read yet" is never mistaken for "checked and
 * fine". It is never coloured like the fine state and never pulses like ten rows all loading at
 * once would.
 */
export function HealthDot({
  service,
  className,
  quiet = true,
}: {
  service?: ServiceHealth
  className?: string
  /** Whether a healthy service draws nothing at all. The sidebar wants `true`, a graph `false`. */
  quiet?: boolean
}) {
  if (!service) {
    return (
      <span
        role="img"
        aria-label="Not read yet."
        title="Not read yet."
        className={cn("size-2 shrink-0 rounded-full bg-muted-foreground/40", className)}
      />
    )
  }

  const tone = serviceTone(service)
  if (tone === "ok" && quiet) return null

  return (
    <span
      role="img"
      aria-label={DOT_WORD[tone]}
      title={DOT_WORD[tone]}
      className={cn("size-2 shrink-0 rounded-full", DOT_TONE[tone], className)}
    />
  )
}

/**
 * Image drift.
 *
 * `UNKNOWN` is deliberately not silent and deliberately not green: an image nobody compared is not
 * a current image, and treating it as one is how four releases shipped unnoticed (A24).
 *
 * `LOCAL` is the opposite direction from `OUTDATED`, not a milder version of it (steward/75): the
 * registry has nothing newer, this host has something the registry has never seen. Drawn neutral
 * rather than red, because a red lamp that means "you just deployed something" is a lamp people
 * stop reading - but it carries the one warning that is true of it, which stood nowhere before this
 * state existed.
 */
/**
 * One artefact's resolve status (season-2-ops/128).
 *
 * **Four answers, not two**, and keeping them apart is the whole reason this exists. "outdated" and
 * "not installed" are work; "up to date" is none; "no build" is a publisher who has not shipped for
 * this Minecraft version yet, which is nobody's fault and no reason to hold a run; and "could not
 * ask" is the one that must never be drawn like any of the others - a source that did not answer
 * looks exactly like a source that said nothing had changed, and the difference is the entire value
 * of the reading.
 */
export function AvailableBadge({ status }: { status: string }) {
  switch (status) {
    case "UP_TO_DATE":
      return (
        <StatusBadge tone="ok" title="What is installed is what the source says is newest.">
          up to date
        </StatusBadge>
      )
    case "OUTDATED":
      return (
        <StatusBadge tone="warn" title="A newer file exists. A run would install it.">
          outdated
        </StatusBadge>
      )
    case "MISSING":
      return (
        <StatusBadge
          tone="warn"
          title={
            "Nothing with this file name is installed. On a fresh volume that is normal; on a" +
            " running server it is either new, or a publisher who renamed the jar."
          }
        >
          not installed
        </StatusBadge>
      )
    case "UNSUPPORTED":
      return (
        <StatusBadge
          tone="idle"
          title={
            "The source answered and has no build of this for the Minecraft version the network" +
            " runs. Nothing is installed and nothing failed. It stays on this list, so the day a" +
            " build appears the next run picks it up."
          }
        >
          no build
        </StatusBadge>
      )
    case "MOUNT_MISSING":
      return (
        <StatusBadge
          tone="down"
          title="The volume is not mounted in steward-worker, so nothing can be said about it."
        >
          no volume
        </StatusBadge>
      )
    default:
      return (
        <StatusBadge
          tone="down"
          title="The source could not be asked. This is not the same as nothing having changed."
        >
          could not ask
        </StatusBadge>
      )
  }
}

export function DriftBadge({ drift }: { drift: string }) {
  switch (drift) {
    case "UP_TO_DATE":
      return (
        <StatusBadge tone="ok" title="The running image carries the digest the registry names.">
          up to date
        </StatusBadge>
      )
    case "OUTDATED":
      return (
        <StatusBadge tone="warn" title="The registry has a newer image than this container.">
          outdated
        </StatusBadge>
      )
    case "LOCAL":
      return (
        <StatusBadge
          tone="idle"
          title={
            "Built on this host and never published - ahead of the registry, not behind it. The" +
            " next real update run replaces it silently, because the updater only ever installs" +
            " from a release."
          }
        >
          local build
        </StatusBadge>
      )
    default:
      return (
        <StatusBadge
          tone="idle"
          title={
            'Not compared - either the registry did not answer, or this container\'s exact image' +
            ' is no longer on file locally (its tag was rebuilt without recreating it). That is' +
            ' not "up to date".'
          }
        >
          unchecked
        </StatusBadge>
      )
  }
}

/** The status of a run, as the `update_request` row carries it. */
export function RunStatus({ status }: { status: string }) {
  const tone: Tone =
    status === "DONE"
      ? "ok"
      : status === "FAILED"
        ? "down"
        : status === "RUNNING"
          ? "warn"
          : "idle"
  return <StatusBadge tone={tone}>{RUN_STATUS[status] ?? status}</StatusBadge>
}

/** The outcome of a run, in the same words {@link RunStatus} draws - exported for anything that
 * needs the word rather than the badge, such as the command palette's search text. */
export const RUN_STATUS: Record<string, string> = {
  PENDING: "waiting",
  RUNNING: "running",
  DONE: "done",
  FAILED: "failed",
  CANCELLED: "cancelled",
}

/** What kind of run it was. Not a status - a noun. */
export const RUN_KIND: Record<string, string> = {
  UPDATE: "Update",
  BACKUP: "Backup",
  RESTART: "Restart",
  DOWN: "Put down",
  START: "Start",
  // Two kinds nothing in this interface asks for, but old rows carry them and a table that printed
  // the enum name for them would look broken rather than historical.
  REPORT: "Report",
  APPLY: "Apply",
}
