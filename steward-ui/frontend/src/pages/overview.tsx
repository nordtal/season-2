import type { ReactNode } from "react"
import { Link } from "@tanstack/react-router"
import { ChevronRight, Terminal, ScrollText } from "lucide-react"

import type { Service } from "@/lib/api"
import { bytes, count, dateTime, percent, relative, since } from "@/lib/format"
import { summarise } from "@/lib/health"
import {
  useActions,
  useAvatarBaseUrl,
  useBackups,
  useHost,
  useMetrics,
  usePeople,
  useSeason,
  useServices,
  useSettings,
} from "@/lib/queries"
import { ActionRow } from "@/components/steward/actions"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Sparkline } from "@/components/steward/sparkline"
import { Stat, UsageBar } from "@/components/steward/stat"
import { DriftBadge, ServiceState } from "@/components/steward/status"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

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
 * appeared for `warn`/`down` and disappeared for `ok`. Its content is now the first tile in
 * {@link MetricRow}, in the same shape every other tile there already has: a number, and beneath it
 * the names it is about. `health.ts`'s own warning still applies inside that tile - a settled "0"
 * must never look like "nothing has been read yet".
 *
 * The service rows are deliberately read-only. No restart button lives here: restarting the SMP
 * throws every player out, and the place where that happens is the Operations page, where the
 * confirmation already stands. A row is for reading and for jumping onwards.
 */
export function OverviewPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Overview"
      />

      <MetricRow />

      <ServiceTable />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <SeasonPanel />
        <ActionsPanel />
      </div>
    </div>
  )
}

// --- the metric row -------------------------------------------------------------------------------

/**
 * Issues, CPU, memory, disk, drift and the newest backup - six numbers on a phone, three rows of
 * two, exactly the width steward/80 asked for. This replaces the Host, Updates and Backups cards:
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
 */
function MetricRow() {
  const host = useHost()
  const cpu = useMetrics("host", "cpu", 6)
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
      <IssuesTile triggers={triggers} waiting={waiting} failed={failed} />

      <MetricTile
        label="CPU"
        value={percent(host.data?.cpuPercent)}
        hint={unreadable ?? `${count(host.data?.cpus)} cores`}
      >
        <UsageBar used={host.data?.cpuPercent ?? 0} total={100} />
        <Sparkline points={cpu.data?.points ?? []} />
      </MetricTile>

      <MetricTile
        label="Memory"
        value={memoryShare(host.data)}
        hint={
          unreadable ??
          (host.data?.memoryTotalBytes
            ? `${bytes(usedMemory)} of ${bytes(host.data.memoryTotalBytes)}`
            : "–")
        }
      >
        {host.data?.memoryTotalBytes ? (
          <UsageBar used={usedMemory ?? 0} total={host.data.memoryTotalBytes} />
        ) : null}
      </MetricTile>

      <MetricTile
        label="Disk"
        value={
          host.data?.diskTotalBytes
            ? percent(((host.data.diskUsedBytes ?? 0) / host.data.diskTotalBytes) * 100, 0)
            : "–"
        }
        hint={
          unreadable ??
          (host.data?.diskTotalBytes
            ? `${bytes(host.data.diskUsedBytes)} of ${bytes(host.data.diskTotalBytes)}`
            : "–")
        }
      >
        {host.data?.diskTotalBytes ? (
          <UsageBar used={host.data.diskUsedBytes ?? 0} total={host.data.diskTotalBytes} />
        ) : null}
      </MetricTile>

      <MetricTile
        label="Behind"
        value={count(outdated.length)}
        tone={outdated.length > 0 ? "warn" : undefined}
        hint={outdated.length === 0 ? "up to date" : outdated.map((service) => service.service).join(", ")}
      />

      <MetricTile
        label="Newest backup"
        value={newest ? relative(newest.modified) : backups.data ? "none" : "–"}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? "no finished backup" : "–"}
      />
    </div>
  )
}

/**
 * What used to be the traffic light, now the first tile in {@link MetricRow} (steward/80). Till:
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
    if (waiting) return <MetricTile label="Issues" value="–" hint="reading" />
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
  children,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: "ok" | "warn" | "down"
  children?: ReactNode
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
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

// --- Season and the journal, flattened ------------------------------------------------------------

function SeasonPanel() {
  const season = useSeason()
  return (
    <Panel title="Season">
      {season.isPending ? (
        <Loading rows={2} />
      ) : season.error ? (
        <Failure error={season.error} onRetry={season.refetch} />
      ) : (
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-1 gap-4 min-[26rem]:grid-cols-3">
            <Stat label="Phase" value={PHASES[season.data!.phase] ?? season.data!.phase} />
            <Stat label="Season start" value={dateTime(season.data!.launch)} />
            <Stat label="SMP-Start" value={dateTime(season.data!.smpStart)} />
          </div>
          <Button asChild variant="ghost" size="sm" className="w-fit -ml-3">
            <Link to="/season">To the season</Link>
          </Button>
        </div>
      )}
    </Panel>
  )
}

const PHASES: Record<string, string> = {
  PRE_EVENT: "before the event",
  EVENT: "Event",
  SMP: "SMP",
  ENDED: "ended",
}

/**
 * The right half of the new bottom section (steward/82; the left is {@code SeasonPanel}, steward/81).
 *
 * Fed by steward-worker's own {@code /api/actions} rather than by sorting {@link useJournal}'s
 * `audit_log` rows together with a second call for `update_request` here - see that endpoint's own
 * javadoc for why a merge belongs in one query and not on this page. Every actor is drawn through
 * {@link ActionRow} and {@code PersonIdentity}, never as the raw text the old, journal-only version
 * of this panel used to print: `entry.actor` was an unadorned Discord snowflake, which is exactly
 * the leak steward/45's rule exists to close and which the old static check could not see, because
 * nothing here was named `discordId`.
 *
 * **A real heading, not `Panel`'s** (steward/77's rule, and Till's own words for this ticket): the
 * small grey capitalised line reads as a section label, and this is content, so it gets the same
 * weight the page's own `PageHeader` gives a title.
 */
function ActionsPanel() {
  const actions = useActions(5)
  const people = usePeople()
  const avatarBase = useAvatarBaseUrl()
  const now = Date.now()
  const entries = actions.data ?? []

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">Latest actions</h2>
      {actions.isPending ? (
        <Loading rows={4} />
      ) : actions.error ? (
        <Failure error={actions.error} onRetry={actions.refetch} />
      ) : entries.length === 0 ? (
        <Empty title="Nothing recorded yet" />
      ) : (
        <ul className="flex flex-col">
          {entries.map((action, index) => (
            <ActionRow
              // The feed carries no id of its own - a run and a journal line have different
              // primary keys, and stamping a synthetic one on here would be a fact this page
              // invented. Position is stable because the list is never reordered client-side.
              key={index}
              action={action}
              people={people.data}
              avatarBaseUrl={avatarBase.data}
              now={now}
            />
          ))}
        </ul>
      )}
      <Button asChild variant="ghost" size="sm" className="w-fit -ml-3">
        <Link to="/journal">The whole journal</Link>
      </Button>
    </section>
  )
}

// --- the service table --------------------------------------------------------------------------

/**
 * Three groups, and the membership is a decision rather than an alphabet.
 *
 * A service this list does not know still appears - under "Other". A new compose service that
 * silently vanished from the start page would be exactly the kind of thing nobody notices until it
 * is the one that is down.
 */
const GROUPS: Array<{ label: string; note: string; members: string[] }> = [
  {
    label: "Minecraft",
    note: "What players see. A restart here throws everybody out.",
    members: ["network-control", "limbo", "hunger-games", "smp"],
  },
  {
    label: "Steward",
    note: "This interface, the daemon access and the rollout.",
    members: ["steward-ui", "steward-worker", "steward-deployer"],
  },
  {
    label: "Infrastructure",
    note: "Database, delivery and the bot.",
    members: ["postgres", "caddy", "discord-bot"],
  },
]

/** A container Docker is happy with - the same rule `health.ts` judges the traffic light by. */
function isHealthy(service: Service): boolean {
  return service.state === "running" && service.health !== "unhealthy"
}

/**
 * A disclosure, not a table, by default.
 *
 * steward/64: ten rows of name, state, drift and uptime were the single biggest thing standing
 * between "open the page" and "see the numbers" on a phone, where each row is a seven-line card
 * (see `.steward-table`'s narrow layout in `index.css`). The default state is one line - how many
 * of how many are fine, and which ones are not if any are not - and the full table is one tap away
 * rather than the first thing scrolled past. `<details>` rather than a component of its own: it is
 * the platform's own disclosure widget, keyboard- and screen-reader-accessible for free, and it
 * needs no state this file would otherwise have to own.
 */
function ServiceTable() {
  const services = useServices()

  if (services.isPending) return <Loading rows={3} />
  if (services.error) return <Failure error={services.error} onRetry={services.refetch} />

  const all = services.data?.services ?? []

  if (all.length === 0) {
    return (
      <Empty
        title="No container in the project"
        note="steward-worker answered, but no container carries the compose project label. Is the stack running?"
      />
    )
  }

  const problems = all.filter((service) => !isHealthy(service))

  return (
    <details className="group rounded-md border border-border">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-4 py-3 text-sm select-none [&::-webkit-details-marker]:hidden">
        <ChevronRight
          className="size-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-90"
          aria-hidden
        />
        {problems.length === 0 ? (
          <span>
            Services: {all.length} of {all.length} healthy
          </span>
        ) : (
          <span>
            <span className="font-medium text-destructive">
              Services: {problems.length} of {all.length} need attention
            </span>{" "}
            <span className="text-muted-foreground">
              - {problems.map((service) => service.service).join(", ")}
            </span>
          </span>
        )}
      </summary>
      <div className="border-t border-border px-1 pb-1">
        <Table className="steward-table">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[18rem]">Service</TableHead>
              <TableHead className="w-[7rem]">State</TableHead>
              <TableHead className="w-[7rem]">Image</TableHead>
              <TableHead className="w-[8rem]">Uptime</TableHead>
              <TableHead className="w-[8rem] text-right">RAM</TableHead>
              <TableHead className="w-[6rem] text-right">CPU</TableHead>
              <TableHead className="w-[9rem]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {groupsOf(all)
              .filter((group) => group.rows.length > 0)
              .map((group) => (
                <GroupRows key={group.label} {...group} />
              ))}
          </TableBody>
        </Table>
      </div>
    </details>
  )
}

function groupsOf(all: Service[]) {
  const known = new Set(GROUPS.flatMap((group) => group.members))
  const groups = GROUPS.map((group) => ({
    ...group,
    rows: group.members
      .map((name) => all.find((service) => service.service === name))
      .filter((service): service is Service => service !== undefined),
  }))
  const others = all.filter((service) => !known.has(service.service))
  if (others.length > 0) {
    groups.push({
      label: "Other",
      note: "Services this interface does not know - new in compose.yml?",
      members: [],
      rows: others,
    })
  }
  return groups
}

function GroupRows({
  label,
  note,
  rows,
}: {
  label: string
  note: string
  rows: Service[]
}) {
  return (
    <>
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={7} className="bg-secondary/40">
          <span className="text-xs font-semibold">{label}</span>
          <span className="ml-2 text-xs text-muted-foreground">{note}</span>
        </TableCell>
      </TableRow>
      {rows.map((service) => (
        <TableRow key={service.service}>
          <TableCell data-label="Service" className="font-medium">
            <Link
              to="/services/$name"
              params={{ name: service.service }}
              className="underline-offset-4 hover:text-primary hover:underline"
            >
              {service.service}
            </Link>
            {service.unreadable ? (
              <span className="ml-2 text-xs text-warning">{service.unreadable}</span>
            ) : null}
          </TableCell>
          <TableCell data-label="State">
            <ServiceState state={service.state} health={service.health} />
          </TableCell>
          <TableCell data-label="Image">
            <DriftBadge drift={service.drift} />
          </TableCell>
          <TableCell data-label="Uptime" className="text-muted-foreground">
            {service.startedAt ? since(service.startedAt) : "–"}
          </TableCell>
          <TableCell data-label="RAM" className="text-right tnum">{bytes(service.memoryBytes)}</TableCell>
          <TableCell data-label="CPU" className="text-right tnum">{percent(service.cpuPercent)}</TableCell>
          <TableCell>
            <div className="flex items-center justify-end gap-1">
              <Button asChild variant="ghost" size="sm">
                <Link to="/services/$name" params={{ name: service.service }}>
                  <ScrollText aria-hidden />
                  Log
                </Link>
              </Button>
              {/* No greyed-out console: a service without one simply does not offer the link. */}
              {service.hasConsole ? (
                <Button asChild variant="ghost" size="sm">
                  <Link
                    to="/services/$name"
                    params={{ name: service.service }}
                    hash="console"
                  >
                    <Terminal aria-hidden />
                    Console
                  </Link>
                </Button>
              ) : null}
            </div>
          </TableCell>
        </TableRow>
      ))}
    </>
  )
}
