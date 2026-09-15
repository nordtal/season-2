import type { ReactNode } from "react"
import { Link } from "@tanstack/react-router"
import { ChevronRight, CircleAlert, OctagonAlert, Terminal, ScrollText } from "lucide-react"

import type { Service } from "@/lib/api"
import { bytes, count, dateTime, load as formatLoad, percent, relative, since } from "@/lib/format"
import { UNKNOWN, shownLevel, summarise, type Level } from "@/lib/health"
import {
  useBackups,
  useHost,
  useJournal,
  useMetrics,
  useSeason,
  useServices,
  useSettings,
} from "@/lib/queries"
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
 * the box** - **where do I want to go**. Everything below the light is a tile, and every tile is a
 * link into the page that can actually do something about it.
 *
 * **Mobile first, as of steward/64** - and a standing rule for this page from here on, not a one-off
 * for this ticket. Till reads this page on a phone before he reads it anywhere else, so the narrow
 * column is the layout that gets designed, and the wide one is what falls out of it at `lg`, never
 * the other way around.
 *
 * The service rows are deliberately read-only. No restart button lives here: restarting the SMP
 * throws every player out, and the place where that happens is the Operations page, where the
 * confirmation already stands. A row is for reading and for jumping onwards.
 */
export function StatusPage() {
  const services = useServices()
  const host = useHost()
  const backups = useBackups()
  // The thresholds are configured, not compiled in. Without this the page would draw its traffic light
  // against 85/90 while steward-ui.yml said something else - and the Discord channel, which reads
  // the same numbers off the server, would disagree with the screen. Which is why this counts as
  // evidence like any other query: while it is missing, `summarise` leaves the checks that need a
  // threshold alone, and the light below says it could not read everything.
  const settings = useSettings()

  const { level, triggers } = summarise({
    table: services.data,
    host: host.data,
    backups: backups.data,
    thresholds: settings.data,
  })

  // Nothing has answered yet: the traffic light must not say "all is well" about a stack it has not
  // looked at. A green light on no evidence is worse than no light.
  const waiting = services.isPending || host.isPending || backups.isPending || settings.isPending
  const failed = services.error ?? host.error ?? backups.error ?? settings.error

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Status"
      />

      <TrafficLight level={level} triggers={triggers} waiting={waiting} failed={Boolean(failed)} />

      <MetricRow />

      <ServiceTable />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <SeasonPanel />
        <ActionsPanel />
      </div>
    </div>
  )
}

// --- the traffic light -------------------------------------------------------------------------

const LIGHTS: Record<Exclude<Level, "ok">, { icon: typeof CircleAlert; ring: string; text: string }> = {
  warn: { icon: CircleAlert, ring: "border-warning/30 bg-warning/8", text: "text-warning" },
  down: { icon: OctagonAlert, ring: "border-destructive/40 bg-destructive/8", text: "text-destructive" },
}

function TrafficLight({
  level,
  triggers,
  waiting,
  failed,
}: {
  level: Level
  triggers: ReturnType<typeof summarise>["triggers"]
  waiting: boolean
  failed: boolean
}) {
  if (waiting && triggers.length === 0) {
    return (
      <div className="flex items-center gap-3 rounded-md border border-border bg-card px-4 py-3">
        <span className="size-2 animate-pulse rounded-full bg-muted-foreground" aria-hidden />
        <p className="text-sm text-muted-foreground">Reading status…</p>
      </div>
    )
  }

  const shown = shownLevel(level, failed)

  // steward/64: Till's instruction was to remove the green banner entirely rather than to shorten
  // its text - show it only for the red and yellow case, and even there just a short reference. A
  // stack with nothing wrong gets no banner at all; the page starts with the numbers below. This branch is
  // only reachable once `waiting` is false AND `failed` is false, because `shownLevel` only ever
  // turns "ok" into "warn" on a failed query - so an `ok` here is a settled, evidenced "ok", never a
  // guess. That is the one distinction this whole file exists to keep visible: "checked and fine"
  // must never render the same as "nothing read yet", and the branch above is what still renders for
  // the second case.
  if (shown === "ok") {
    return null
  }

  const { icon: Icon, ring, text } = LIGHTS[shown]
  // A single link for a single line: the worst trigger (triggers are sorted red-first) is the one
  // whose destination the operator most wants, and every other trigger this render still names by
  // its subject even though only one of them gets a place to click through to.
  const worst = triggers[0]

  return (
    <div className={`flex flex-col gap-3 rounded-md border px-4 py-3 ${ring}`} role="status">
      <div className="flex items-start gap-3">
        <Icon className={`mt-0.5 size-5 shrink-0 ${text}`} aria-hidden />
        <div className="flex min-w-0 flex-col gap-1">
          {triggers.length === 0 ? (
            <p className="text-sm font-medium">{UNKNOWN}</p>
          ) : (
            <p className="text-sm">
              {/*
                Till's own words for what belongs here: "Fehler in smp, discord-bot" plus a link -
                a reference, not a list of sentences. The full sentence per trigger (`text`) still
                exists, on `Trigger` itself, for the page this link leads to; nothing here parses it
                back out of prose, `subject` is already the short form.
              */}
              <span className={worst.level === "down" ? "text-destructive" : "text-warning"}>
                Errors in {triggers.map((trigger) => trigger.subject).join(", ")}.
              </span>{" "}
              {worst.to ? (
                <Link
                  to={worst.to}
                  params={worst.params}
                  className="text-primary underline-offset-4 hover:underline"
                >
                  view
                </Link>
              ) : (
                <Link to="/operations" className="text-primary underline-offset-4 hover:underline">
                  view
                </Link>
              )}
            </p>
          )}
          {/*
            Both sentences, and `waiting` is the one that was missing. The loading state above only
            fires while there is NOTHING to say; with one trigger already found - an image behind,
            say - the page drew a definite yellow light while `/api/settings` was still on its way,
            and the answer can turn it red (a backup older than the threshold that had not arrived
            yet). A light that is definite about an incomplete reading is the failure this whole
            file argues against.
          */}
          {failed ? (
            <p className="text-sm text-muted-foreground">
              Some of the readings could not be fetched - so the light is judging on less than it
              should.
            </p>
          ) : waiting ? (
            <p className="text-sm text-muted-foreground">
              Still reading - so far the light is judging on less than it should.
            </p>
          ) : null}
        </div>
      </div>
    </div>
  )
}

// --- the metric row -------------------------------------------------------------------------------

/**
 * CPU, memory, disk, drift and the newest backup - the five numbers §10c and Till's own list of
 * 2026-09-15 agreed are the ones an operator wants without scrolling. This replaces the Host,
 * Updates and Backups cards: their remaining detail - the container limits, when the registry was
 * last compared, the unverifiable services, the last update run and the partial archives - is not
 * lost with them. The Operations page already showed every one of it before this change (Images
 * table with its Compared column and its `drift.unverifiable` line, the runs table, the backups
 * table with its partial state), so nothing had to be carried across; the front page simply stopped
 * printing a second copy of a page one tap away.
 *
 * Mobile first: two tiles to a row is the width steward/64 asked for on a phone, three from
 * `26rem`, and only the desktop breakpoint spends the whole thing on one row of five - the layout
 * this row *ends* on, not the one it starts from.
 */
function MetricRow() {
  const host = useHost()
  const cpu = useMetrics("host", "cpu", 6)
  const services = useServices()
  const backups = useBackups()

  const outdated = (services.data?.services ?? []).filter((service) => service.drift === "OUTDATED")
  const finishedBackups = (backups.data ?? []).filter((backup) => !backup.partial)
  const newest = finishedBackups[0]

  const unreadable = host.data?.unreadable
  const usedMemory = host.data?.memoryTotalBytes
    ? host.data.memoryTotalBytes - (host.data.memoryAvailableBytes ?? 0)
    : undefined

  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 min-[26rem]:grid-cols-3 lg:grid-cols-5">
      <MetricTile
        label="CPU"
        value={percent(host.data?.cpuPercent)}
        hint={unreadable ?? `${count(host.data?.cpus)} cores · Load ${formatLoad(host.data?.load1)}`}
      >
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

function MetricTile({
  label,
  value,
  hint,
  tone,
  children,
}: {
  label: string
  value: string
  hint?: string
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

function ActionsPanel() {
  const journal = useJournal("", "")
  const entries = (journal.data ?? []).slice(0, 8)

  return (
    <Panel title="Latest actions">
      {journal.isPending ? (
        <Loading rows={4} />
      ) : journal.error ? (
        <Failure error={journal.error} onRetry={journal.refetch} />
      ) : entries.length === 0 ? (
        <Empty title="Nothing recorded yet" />
      ) : (
        <ul className="flex flex-col">
          {entries.map((entry) => (
            <li
              key={entry.id}
              className="flex h-row items-center gap-3 border-b border-border/60 last:border-0"
            >
              <span className="w-28 shrink-0 text-xs text-muted-foreground tnum">
                {relative(entry.occurred)}
              </span>
              <span className="truncate text-sm">{entry.action}</span>
              <span className="ml-auto truncate text-xs text-muted-foreground">
                {entry.actor ?? "System"}
              </span>
            </li>
          ))}
        </ul>
      )}
      <Button asChild variant="ghost" size="sm" className="w-fit -ml-3">
        <Link to="/journal">The whole journal</Link>
      </Button>
    </Panel>
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
