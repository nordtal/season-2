import type { ReactNode } from "react"
import { Link } from "@tanstack/react-router"
import { cn } from "cn"

import { bytes, count, percent, relative } from "@/lib/format"
import { summarise } from "@/lib/health"
import {
  useActions,
  useBackups,
  useHost,
  useMetrics,
  useServices,
  useSettings,
} from "@/lib/queries"
import { ActionRow } from "@/components/steward/actions"
import { NetworkPanel } from "@/components/steward/network/view"
import { OnlineLine, useOnline } from "@/components/steward/online"
import { Sparkline } from "@/components/steward/sparkline"
import { Stat, UsageBar } from "@/components/steward/stat"
import { QueryState, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"

/**
 * The landing page (concept §10c).
 *
 * It answers three questions in this order and no other: **is something wrong** - **how full is
 * the box** - **where do I want to go**. Everything below the header is a tile, and every tile is a
 * link into the page that can actually do something about it.
 *
 * **Mobile first, as of steward/64** - and a standing rule for this page from here on, not a one-off
 * for this ticket. Till reads this page on a phone before he reads it anywhere else, so the narrow
 * column is the layout that gets designed, and the wide one is what falls out of it at `lg`, never
 * the other way around.
 *
 * **The traffic light is gone (steward/80).** It used to stand above the numbers as a banner that
 * appeared for `warn`/`down` and disappeared for `ok`. Its content is now the last tile in
 * {@link MetricRow}, in the same shape every other tile there already has: a number, and beneath it
 * the names it is about. `health.ts`'s own warning still applies inside that tile - a settled "0"
 * must never look like "nothing has been read yet".
 *
 * **There is no page title (steward/64).** "Overview" named the page to somebody who was already
 * standing on it, and the first line is now the one fact on this stack that changes minute to
 * minute and that no tile below carries: how many people are in the game. See
 * {@code components/steward/online.tsx} for the three shapes that were drawn for it and for why
 * this one is in the product. The word still labels the route in the sidebar and in the
 * breadcrumbs, where it is a destination rather than a heading.
 *
 * **The service table is gone (steward/81).** Ten rows of name, state, drift and uptime behind a
 * `<details>` were what steward/64 had already reduced the ten cards to; the network picture in the
 * bottom section now carries the same ten services with their health, their image state and their
 * player counts, and the whole table lives on the Operations page for whoever wants the columns.
 * Nothing on this page restarts anything, which was true of the table as well and stays true of the
 * picture: a restart throws every player out, and it belongs where the confirmation already stands.
 */
export function OverviewPage() {
  const online = useOnline()

  return (
    <div className="flex flex-col gap-6">
      <OnlineLine online={online} />

      <MetricRow />

      {/*
        The bottom section, split into two halves on desktop exactly as Till asked (steward/81):
        the network picture on the left, and on the right the season above the latest actions.

        **Why the right half is a stack of two and not one panel.** Till named the two halves - a
        network view and the action list - and said nothing about Season, which was standing in the
        left half at the time. Deleting it was never asked for and it has no other home on this
        page, so it keeps its place in the section and moves over: the picture is one tall column,
        and two short panels beside it is what fills the same height. The alternative, a full-width
        Season strip above the section, spends a whole row of the page on three dates that change
        twice a season.

        **On a phone the picture goes last**, which is the one place this layout is not simply the
        desktop one stacked. Since steward/121 a phone gets a table of ten rows rather than the
        940px drawing that used to be here - shorter, but still most of a screen before the first
        thing that changed today. It replaced a one-line disclosure, and a one-line disclosure is
        what was cheap to scroll past - so the order changes rather than the picture.
      */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="order-last lg:order-first lg:col-span-2">
          <NetworkPanel />
        </div>
        <div className="flex flex-col lg:col-start-3">
          <ActionsPanel />
        </div>
      </div>
    </div>
  )
}

// --- the metric row -------------------------------------------------------------------------------

/**
 * CPU, memory, disk, the latest backup, drift and the issues - six numbers on a phone, three rows
 * of two, exactly the width steward/80 asked for. This replaces the Host, Updates and Backups cards:
 * their remaining detail - the container limits, when the registry was last compared, the
 * unverifiable services, the last update run and the partial archives - is not lost with them. The
 * Operations page already showed every one of it before this change (Images table with its
 * Compared column and its `drift.unverifiable` line, the runs table, the backups table with its
 * partial state), so nothing had to be carried across; the front page simply stopped printing a
 * second copy of a page one tap away.
 *
 * Mobile first: two tiles to a row is the width steward/64 asked for on a phone, three from
 * `26rem`, and only the desktop breakpoint spends the whole thing on one row of six - the layout
 * this row *ends* on, not the one it starts from.
 *
 * **The order is Till's, 2026-09-17:** CPU, Memory, Disk, Latest backup, Behind, Issues. The three
 * resources come first because "how full is the box" is what the page is opened on a phone to
 * answer, and the two counts that are zero on a healthy day come last - including Issues, which
 * steward/80 had put first while it was still a banner pretending to be a tile. Nothing was added
 * for this: "Issues" is that same tile moved, and "Latest backup" is the tile that used to be
 * called "Newest backup" and is nothing else.
 */
/** The quiet line under a figure, while the figure is still out. */
const WAITING_HINT = <SkeletonText className="w-20 text-xs" />

function MetricRow() {
  const host = useHost()
  const cpu = useMetrics("host", "cpu_percent", 6)
  const services = useServices()
  const backups = useBackups()
  const settings = useSettings()

  const { triggers } = summarise({
    table: services.data,
    host: host.data,
    backups: backups.data,
    thresholds: settings.data,
  })
  // Nothing has answered yet: the tile must not say "all is well" about a stack it has not looked
  // at. A settled zero on no evidence is worse than no number - the argument health.ts has always
  // made for the light, unchanged now that it is a tile.
  const waiting = services.isPending || host.isPending || backups.isPending || settings.isPending
  const failed = Boolean(services.error ?? host.error ?? backups.error ?? settings.error)

  const outdated = (services.data?.services ?? []).filter((service) => service.drift === "OUTDATED")
  const finishedBackups = (backups.data ?? []).filter((backup) => !backup.partial)
  const newest = finishedBackups[0]

  const unreadable = host.data?.unreadable
  const usedMemory = host.data?.memoryTotalBytes
    ? host.data.memoryTotalBytes - (host.data.memoryAvailableBytes ?? 0)
    : undefined

  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 min-[26rem]:grid-cols-3 lg:grid-cols-6">
      {/*
        CPU is the only one of the six tiles that carries both a bar and a sparkline beneath its
        number - Memory and Disk stop at the bar, and Latest backup/Behind/Issues have no meter at
        all. That makes it the tallest tile by a fixed ~34px (a gap plus the sparkline's own 28px),
        in every state, whether the sparkline is drawing a real curve or the flat placeholder it
        shows while `cpu.data.points` is still empty - `Sparkline` reserves the same height either
        way, on purpose (see its own comment), so this is not a loading-state artifact.

        A CSS grid row's height is the tallest item in it, and every other item in that row
        stretches to match by default - so whichever tile happens to land next to CPU in a given
        column count inherits blank space nothing of its own explains. `col-span-2` /
        `min-[26rem]:col-span-3` give CPU the whole row to itself below `lg`, where six columns
        already hold it without a row-mate at all (steward/92: measured with `getBoundingClientRect`
        - the tile paired with CPU was 110px tall against its neighbours' 64-76px, and no amount of
        `items-*` changes that, since grid track sizing is content-based regardless of alignment).
        CPU leads the row since steward/64 reordered it, so the cell it vacates is the second one
        of the first row and stays empty rather than stretched - an empty grid cell costs nothing
        to look at, the way the trailing cell in an odd-numbered row already does not.
      */}
      <MetricTile
        label="CPU"
        value={host.data ? percent(host.data.cpuPercent) : undefined}
        hint={host.data ? (unreadable ?? `${count(host.data.cpus)} cores`) : WAITING_HINT}
        className="col-span-2 min-[26rem]:col-span-3 lg:col-span-1"
      >
        <UsageBar used={host.data?.cpuPercent ?? (host.data ? 0 : undefined)} total={host.data ? 100 : undefined} />
        <Sparkline points={cpu.data?.points} />
      </MetricTile>

      <MetricTile
        label="Memory"
        value={host.data ? memoryShare(host.data) : undefined}
        hint={
          host.data
            ? (unreadable ??
              (host.data.memoryTotalBytes
                ? `${bytes(usedMemory)} of ${bytes(host.data.memoryTotalBytes)}`
                : "–"))
            : WAITING_HINT
        }
      >
        {!host.data ? (
          <UsageBar />
        ) : host.data.memoryTotalBytes ? (
          <UsageBar used={usedMemory ?? 0} total={host.data.memoryTotalBytes} />
        ) : null}
      </MetricTile>

      <MetricTile
        label="Disk"
        value={
          !host.data
            ? undefined
            : host.data.diskTotalBytes
              ? percent(((host.data.diskUsedBytes ?? 0) / host.data.diskTotalBytes) * 100, 0)
              : "–"
        }
        hint={
          host.data
            ? (unreadable ??
              (host.data.diskTotalBytes
                ? `${bytes(host.data.diskUsedBytes)} of ${bytes(host.data.diskTotalBytes)}`
                : "–"))
            : WAITING_HINT
        }
      >
        {!host.data ? (
          <UsageBar />
        ) : host.data.diskTotalBytes ? (
          <UsageBar used={host.data.diskUsedBytes ?? 0} total={host.data.diskTotalBytes} />
        ) : null}
      </MetricTile>

      {/* The one tile of the six that links anywhere (steward/112): `/operations/backups`
          (steward/95) now holds everything this number is a summary of - volumes, retention,
          the remote target. The plain `<a>` this becomes still lays out as the grid item
          `MetricTile` would have wrapped it in. */}
      <Link to="/operations/backups" className="flex min-w-0 flex-col gap-1.5">
        <Stat
          label="Latest backup"
          value={newest ? relative(newest.modified) : backups.data ? "none" : undefined}
          tone={backups.data && !newest ? "down" : undefined}
          hint={newest ? newest.human : backups.data ? "no finished backup" : WAITING_HINT}
        />
      </Link>

      <MetricTile
        label="Behind"
        value={services.data ? count(outdated.length) : undefined}
        tone={outdated.length > 0 ? "warn" : undefined}
        hint={
          !services.data
            ? WAITING_HINT
            : outdated.length === 0
              ? "up to date"
              : outdated.map((service) => service.service).join(", ")
        }
      />

      <IssuesTile triggers={triggers} waiting={waiting} failed={failed} />
    </div>
  )
}

/**
 * What used to be the traffic light, now the last tile in {@link MetricRow} (steward/80 put it in
 * the row, steward/64 moved it to the end of it). Till:
 * "the alert-style banner disappears entirely and its information is folded into the area that
 * already says 'Behind 2 steward-worker, steward-ui'" - so this tile is deliberately built like
 * `Behind`: a count, and beneath it the names the count is about.
 *
 * **The steward/64 trap is sharper here than it was for the banner.** With no banner left at all,
 * this tile is the only place "nothing has been read yet" or "a read failed" can still be told
 * apart from "read, and nothing is wrong" - so a settled `0` only ever appears once every query has
 * actually answered and found nothing. While reading, or once a read has failed outright, the value
 * is the same dash this row already uses for "no data" everywhere else, never the zero that means
 * evidenced and fine.
 */
function IssuesTile({
  triggers,
  waiting,
  failed,
}: {
  triggers: ReturnType<typeof summarise>["triggers"]
  waiting: boolean
  failed: boolean
}) {
  if (triggers.length === 0) {
    // Not a dash and not a zero: the tile is drawn as a tile with nothing in it yet (steward/120).
    // The old dash and the word "reading" were this same statement in the only vocabulary the row
    // had before there was a skeleton to say it with.
    if (waiting) return <MetricTile label="Issues" value={undefined} hint={WAITING_HINT} />
    if (failed) return <MetricTile label="Issues" value="–" tone="warn" hint="could not be read" />
    return <MetricTile label="Issues" value={count(0)} hint="all clear" />
  }

  const worst = triggers[0]
  const names = triggers.map((trigger) => trigger.subject).join(", ")
  const note = failed ? "could not read everything" : waiting ? "still reading" : null

  return (
    <MetricTile
      label="Issues"
      value={count(triggers.length)}
      tone={worst.level === "down" ? "down" : "warn"}
      hint={
        note ? (
          <span className="flex flex-col gap-0.5">
            <span>{names}</span>
            <span>{note}</span>
          </span>
        ) : (
          names
        )
      }
    />
  )
}

function MetricTile({
  label,
  value,
  hint,
  tone,
  className,
  children,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: "ok" | "warn" | "down"
  className?: string
  children?: ReactNode
}) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-1.5", className)}>
      <Stat label={label} value={value} hint={hint} tone={tone} />
      {children}
    </div>
  )
}

function memoryShare(host: { memoryTotalBytes?: number; memoryAvailableBytes?: number } | undefined) {
  if (!host?.memoryTotalBytes) return "–"
  const used = host.memoryTotalBytes - (host.memoryAvailableBytes ?? 0)
  return percent((used / host.memoryTotalBytes) * 100, 0)
}

/**
 * The right half of the new bottom section (steward/82).
 *
 * Fed by steward-worker's own {@code /api/actions} rather than by sorting {@link useJournal}'s
 * `audit_log` rows together with a second call for `update_request` here - see that endpoint's own
 * javadoc for why a merge belongs in one query and not on this page. Every actor is drawn through
 * {@link ActionRow} and {@code Entity}, never as the raw text the old, journal-only version
 * of this panel used to print: `entry.actor` was an unadorned Discord snowflake, which is exactly
 * the leak steward/45's rule exists to close and which the old static check could not see, because
 * nothing here was named `discordId`.
 *
 * **A real heading, not `Panel`'s** (steward/77's rule, and Till's own words for this ticket): the
 * small grey capitalised line reads as a section label, and this is content, so it gets the same
 * weight the page's own `PageHeader` gives a title.
 */
/** Four absent rows, the length `useActions(5)` settles at once the season is running. */
const WAITING_ACTIONS = [undefined, undefined, undefined, undefined]

function ActionsPanel() {
  const actions = useActions(5)
  const now = Date.now()

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">Latest actions</h2>
      <QueryState
        query={actions}
        isEmpty={(list) => list.length === 0}
        empty={{
          title: "Nothing recorded yet",
          note: "Every update, backup and access change shows up here as it happens.",
        }}
      >
        {(list) => (
          <ul className="flex flex-col">
            {/* Four while waiting, because that is what `useActions(5)` all but always answers. */}
            {(list ?? WAITING_ACTIONS).map((action, index) => (
              <ActionRow
                // The feed carries no id of its own - a run and a journal line have different
                // primary keys, and stamping a synthetic one on here would be a fact this page
                // invented. Position is stable because the list is never reordered client-side.
                key={index}
                action={action}
                now={now}
              />
            ))}
          </ul>
        )}
      </QueryState>
      <Button asChild variant="ghost" size="sm" className="w-fit -ml-3">
        <Link to="/journal">The whole journal</Link>
      </Button>
    </section>
  )
}
