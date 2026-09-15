import { Link } from "@tanstack/react-router"
import { CheckCircle2, CircleAlert, OctagonAlert, Terminal, ScrollText } from "lucide-react"

import type { Service } from "@/lib/api"
import { bytes, count, dateTime, load as formatLoad, percent, relative, since } from "@/lib/format"
import { ALL_CLEAR, UNKNOWN, shownLevel, summarise, type Level } from "@/lib/health"
import {
  useBackups,
  useHost,
  useJournal,
  useMetrics,
  useRuns,
  useSeason,
  useServices,
  useSettings,
} from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { SeriesChart } from "@/components/steward/series-chart"
import { Stat, UsageBar } from "@/components/steward/stat"
import { DriftBadge, ServiceState } from "@/components/steward/status"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
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
 * the box** - **where do I want to go**. Everything below the light is a tile, and every tile is a link
 * into the page that can actually do something about it.
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

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <HostCard />
        <UpdatesCard />
        <BackupsCard />
      </div>

      <ServiceTable />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SeasonCard />
        <ActionsCard />
      </div>
    </div>
  )
}

// --- the traffic light -------------------------------------------------------------------------

const LIGHTS: Record<Level, { icon: typeof CheckCircle2; ring: string; text: string }> = {
  ok: { icon: CheckCircle2, ring: "border-success/30 bg-success/8", text: "text-success" },
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

  const { icon: Icon, ring, text } = LIGHTS[shownLevel(level, failed)]
  return (
    <div className={`flex flex-col gap-3 rounded-md border px-4 py-3 ${ring}`} role="status">
      <div className="flex items-start gap-3">
        <Icon className={`mt-0.5 size-5 shrink-0 ${text}`} aria-hidden />
        <div className="flex min-w-0 flex-col gap-1">
          {triggers.length === 0 ? (
            <p className="text-sm font-medium">{failed ? UNKNOWN : ALL_CLEAR}</p>
          ) : (
            <ul className="flex flex-col gap-1">
              {triggers.map((trigger) => (
                <li key={trigger.text} className="text-sm">
                  <span
                    className={
                      trigger.level === "down" ? "text-destructive" : "text-warning"
                    }
                  >
                    {trigger.text}
                  </span>{" "}
                  {trigger.to ? (
                    <Link
                      to={trigger.to}
                      params={trigger.params}
                      className="text-primary underline-offset-4 hover:underline"
                    >
                      view
                    </Link>
                  ) : null}
                </li>
              ))}
            </ul>
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

// --- the tiles ----------------------------------------------------------------------------------

function HostCard() {
  const host = useHost()
  const cpu = useMetrics("host", "cpu", 6)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Host</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {host.isPending ? (
          <Loading rows={3} />
        ) : host.error ? (
          <Failure error={host.error} onRetry={host.refetch} />
        ) : host.data?.unreadable ? (
          <p className="text-sm text-muted-foreground">{host.data.unreadable}</p>
        ) : (
          <>
            <div className="grid grid-cols-1 gap-4 min-[26rem]:grid-cols-2">
              <Stat
                label="CPU"
                value={percent(host.data?.cpuPercent)}
                hint={`${count(host.data?.cpus)} cores · Load ${formatLoad(host.data?.load1)}`}
              />
              <Stat
                label="Memory"
                value={memoryShare(host.data)}
                hint={
                  host.data?.memoryTotalBytes
                    ? `${bytes(
                        host.data.memoryTotalBytes - (host.data.memoryAvailableBytes ?? 0),
                      )} of ${bytes(host.data.memoryTotalBytes)}`
                    : "–"
                }
              />
            </div>

            {host.data?.memoryTotalBytes ? (
              <UsageBar
                used={host.data.memoryTotalBytes - (host.data.memoryAvailableBytes ?? 0)}
                total={host.data.memoryTotalBytes}
              />
            ) : null}

            <Separator />

            <div className="flex flex-col gap-2">
              <Stat
                label="Disk"
                value={
                  host.data?.diskTotalBytes
                    ? percent(((host.data.diskUsedBytes ?? 0) / host.data.diskTotalBytes) * 100, 0)
                    : "–"
                }
                hint={`${bytes(host.data?.diskUsedBytes)} of ${bytes(host.data?.diskTotalBytes)} · Images ${bytes(host.data?.imagesBytes)} · Volumes ${bytes(host.data?.volumesBytes)}`}
              />
              {host.data?.diskTotalBytes ? (
                <UsageBar used={host.data.diskUsedBytes ?? 0} total={host.data.diskTotalBytes} />
              ) : null}
            </div>

            <SeriesChart
              points={cpu.data?.points ?? []}
              label="CPU"
              format={(value) => `${Math.round(value)} %`}
              height={96}
            />
            {/*
              The worker's own sentence, and nothing in front of it. This used to read
              "Percentages are shares of the whole host: none - percentages are a share of the
              host" - the same statement twice, because the value is already a full sentence about
              exactly that. Whatever steward-worker has to say about limits, it says here.
            */}
            <p className="text-xs text-muted-foreground">{host.data?.containerLimits}</p>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function memoryShare(host: { memoryTotalBytes?: number; memoryAvailableBytes?: number } | undefined) {
  if (!host?.memoryTotalBytes) return "–"
  const used = host.memoryTotalBytes - (host.memoryAvailableBytes ?? 0)
  return percent((used / host.memoryTotalBytes) * 100, 0)
}

function UpdatesCard() {
  const services = useServices()
  const runs = useRuns(8)
  const outdated = (services.data?.services ?? []).filter((s) => s.drift === "OUTDATED")
  const unverifiable = services.data?.drift.unverifiable ?? []
  const lastRun = (runs.data ?? []).find((run) => run.kind === "UPDATE")

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Updates</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {services.isPending ? (
          <Loading rows={2} />
        ) : services.error ? (
          <Failure error={services.error} onRetry={services.refetch} />
        ) : (
          <>
            <Stat
              label="Behind"
              value={count(outdated.length)}
              tone={outdated.length > 0 ? "warn" : undefined}
              hint={
                (outdated.length === 0
                  ? "No service is running an older image."
                  : outdated.map((s) => s.service).join(", ")) +
                (services.data?.drift.checkedAt
                  ? ` · compared ${relative(services.data.drift.checkedAt)}`
                  : " · not compared yet")
              }
            />
            {unverifiable.length > 0 ? (
              <p className="text-xs text-muted-foreground">
                Unchecked: {unverifiable.join(", ")} - an image with no registry digest cannot be
                compared, and therefore does not count as up to date.
              </p>
            ) : null}
            <Separator />
            <Stat
              label="Last run"
              value={lastRun ? relative(lastRun.finished ?? lastRun.requested) : "none"}
              hint={lastRun ? `#${lastRun.id} · ${lastRun.status}` : "There has been no update yet."}
            />
            <Button asChild variant="outline" size="sm" className="w-fit">
              <Link to="/operations">To Operations</Link>
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function BackupsCard() {
  const backups = useBackups()
  const finished = (backups.data ?? []).filter((backup) => !backup.partial)
  const newest = finished[0]
  const partial = (backups.data ?? []).filter((backup) => backup.partial)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Backups</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {backups.isPending ? (
          <Loading rows={2} />
        ) : backups.error ? (
          <Failure error={backups.error} onRetry={backups.refetch} />
        ) : newest === undefined ? (
          <Empty
            title="No finished backup"
            note="There is no finished archive in the backup directory."
          />
        ) : (
          <>
            <Stat
              label="Newest"
              value={relative(newest.modified)}
              hint={`${newest.name} · ${newest.human}`}
            />
            <Stat
              label="Stock"
              value={count(finished.length)}
              hint={`together ${bytes(finished.reduce((sum, backup) => sum + backup.bytes, 0))}`}
            />
            {partial.length > 0 ? (
              <p className="text-xs text-warning">
                {partial.length} started file(s) (.partial) - either a backup is running right now,
                or one was aborted.
              </p>
            ) : null}
            <Button asChild variant="outline" size="sm" className="w-fit">
              <Link to="/operations">All backups</Link>
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function SeasonCard() {
  const season = useSeason()
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Season</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {season.isPending ? (
          <Loading rows={2} />
        ) : season.error ? (
          <Failure error={season.error} onRetry={season.refetch} />
        ) : (
          <>
            <div className="grid grid-cols-1 gap-4 min-[26rem]:grid-cols-2">
              <Stat label="Phase" value={PHASES[season.data!.phase] ?? season.data!.phase} />
              <Stat label="Season start" value={dateTime(season.data!.launch)} />
            </div>
            <Stat label="SMP-Start" value={dateTime(season.data!.smpStart)} />
            <Button asChild variant="outline" size="sm" className="w-fit">
              <Link to="/season">To the season</Link>
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  )
}

const PHASES: Record<string, string> = {
  PRE_EVENT: "before the event",
  EVENT: "Event",
  SMP: "SMP",
  ENDED: "ended",
}

function ActionsCard() {
  const journal = useJournal("", "")
  const entries = (journal.data ?? []).slice(0, 8)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Latest actions</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
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
        <Button asChild variant="outline" size="sm" className="w-fit">
          <Link to="/journal">The whole journal</Link>
        </Button>
      </CardContent>
    </Card>
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

function ServiceTable() {
  const services = useServices()

  if (services.isPending) return <Loading rows={10} />
  if (services.error) return <Failure error={services.error} onRetry={services.refetch} />

  const all = services.data?.services ?? []
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

  if (all.length === 0) {
    return (
      <Empty
        title="No container in the project"
        note="steward-worker answered, but no container carries the compose project label. Is the stack running?"
      />
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Services</CardTitle>
      </CardHeader>
      <CardContent>
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
            {groups
              .filter((group) => group.rows.length > 0)
              .map((group) => (
                <GroupRows key={group.label} {...group} />
              ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
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
