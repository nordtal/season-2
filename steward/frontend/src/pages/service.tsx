import {
  ArrowsClockwiseIcon,
  DotsThreeIcon,
  PlugIcon,
  PlayIcon,
  PowerIcon,
  TerminalWindowIcon,
  WrenchIcon,
} from "@phosphor-icons/react"
import { useEffect, useState } from "react"
import { Link, useNavigate, useParams, useSearch } from "@tanstack/react-router"

import { bytes, dateTime, percent, relative } from "@/lib/format"
import { useConfigs, useMetrics, useService } from "@/lib/queries"
import { ServiceConsole } from "@/components/steward/console"
import { ServiceSettings } from "@/components/steward/settings"
import { ServicePlugins } from "@/components/steward/plugins"
import { HungerGamesActions } from "@/components/steward/game-actions"
import { SmpActions } from "@/components/steward/milestone-track"
import { AnnouncementForm } from "@/components/steward/announcement"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { RecreateButton, useRecreateGate } from "@/components/steward/recreate"
import { ServiceOnlineLine } from "@/components/steward/online"
import { AskButton, CancelButton, ENDINGS, StageBadge, cancellable } from "@/pages/operations"
import { runPath } from "@/lib/run-path"
import { DriftBadge, RunStatus, ServiceState, runKind } from "@/components/steward/status"
import type { Run } from "@/lib/api"
import { touches, useRunLock } from "@/lib/run-lock"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { MetricChart, RangeSelect, type Range } from "@/components/steward/metric-chart"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { choice, since, t } from "@/lib/texts"

/** What `/services/$name` keeps in its URL. Console is the default and never written. */
export type ServiceSearch = { tab?: "settings" | "plugins"; file?: string }

export function serviceSearch(search: Record<string, unknown>): ServiceSearch {
  const answer: ServiceSearch = {}
  if (search.tab === "settings" || search.tab === "plugins") answer.tab = search.tab
  if (typeof search.file === "string" && search.file !== "") answer.file = search.file
  return answer
}

type Tab = "console" | "settings" | "plugins"

/** Which tabs this service has, or `undefined` until known; a tab with nothing behind it is not there. */
export function useServiceTabs(name: string): Tab[] | undefined {
  const service = useService(name)
  const configs = useConfigs()
  if (service.isPending || configs.isPending) return undefined
  const settings = configs.isError || (configs.data ?? []).some((file) => file.service === name)
  return [
    "console",
    ...(settings ? (["settings"] as const) : []),
    ...(service.data?.hasPlugins ? (["plugins"] as const) : []),
  ]
}

/** One service: a head with its actions and who is on it, and its tabs; every service gets this page. */
export function ServicePage() {
  const { name } = useParams({ from: "/services/$name" })
  const search = useSearch({ from: "/services/$name" })
  const navigate = useNavigate({ from: "/services/$name" })
  const service = useService(name)
  const tabs = useServiceTabs(name)
  const tab: Tab = search.tab ?? "console"
  const { run } = useRunLock()

  /** A tab the service lacks goes back to Console, replacing the entry so Back skips it. */
  useEffect(() => {
    if (tabs && !tabs.includes(tab)) {
      void navigate({ search: {}, replace: true })
    }
  }, [tabs, tab, navigate])

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <PageHeader title={name} actions={<ServiceActions name={name} service={service.data} />} />
        <ActiveRunLine run={run} name={name} />
        <ServiceOnlineLine name={name} />
      </div>

      <Tabs
        value={tab}
        onValueChange={(next) =>
          /** A tab change replaces the entry, so Back leaves the page rather than walking through tabs. */
          void navigate({
            search: (previous) =>
              next === "console"
                ? {}
                : next === "settings"
                  ? { tab: "settings", file: previous.file }
                  : { tab: "plugins" },
            replace: true,
          })
        }
        className="gap-6"
      >
        {tabs ? (
          tabs.length > 1 ? (
            <TabsList className="w-full sm:w-fit">
              <TabsTrigger value="console" className="sm:px-3">
                <TerminalWindowIcon aria-hidden />
                {t("steward.service-page.console")}
              </TabsTrigger>
              {tabs.includes("settings") ? (
                <TabsTrigger value="settings" className="sm:px-3">
                  <WrenchIcon aria-hidden />
                  {t("steward.service-page.settings")}
                </TabsTrigger>
              ) : null}
              {tabs.includes("plugins") ? (
                <TabsTrigger value="plugins" className="sm:px-3">
                  <PlugIcon aria-hidden />
                  {t("steward.service-page.plugins")}
                </TabsTrigger>
              ) : null}
            </TabsList>
          ) : (
            /** One tab is still drawn, so the page says what it shows. */
            <TabsList className="w-full sm:w-fit">
              <TabsTrigger value="console" className="sm:px-3">
                <TerminalWindowIcon aria-hidden />
                {t("steward.service-page.console")}
              </TabsTrigger>
            </TabsList>
          )
        ) : (
          <Skeleton className="h-8 w-full rounded-lg sm:w-96" />
        )}

        <TabsContent value="console" className="flex flex-col gap-6">
          {/* An unknown service is a 404 from steward, which names the service. */}
          <QueryState query={service}>{(data) => <ServiceHead service={data} name={name} />}</QueryState>
          <ServiceConsole
            name={name}
            hasConsole={service.data?.hasConsole ?? false}
            capacity={service.data?.logCapacity}
            offline={offline(run, name, service.data?.state)}
          />
          {/* Their own area under the console, not the header: the header's actions are about the container. */}
          {name === "smp" ? <SmpActions /> : null}
          {name === "hunger-games" ? <HungerGamesActions /> : null}
          {name === "discord-bot" ? <AnnouncementForm /> : null}
        </TabsContent>

        <TabsContent value="settings">
          <ServiceSettings
            service={name}
            file={search.file}
            onFile={(file, replace) =>
              void navigate({ search: file ? { tab: "settings", file } : { tab: "settings" }, replace })
            }
          />
        </TabsContent>

        <TabsContent value="plugins" className="flex flex-col gap-6">
          <ServicePlugins service={name} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

/**
 * Update, Take down or Start, and Recreate, each with its own symbol.
 *
 * Below `sm` the last two move into a menu that opens the buttons' own dialogs.
 */
function ServiceActions({
  name,
  service,
}: {
  name: string
  service?: NonNullable<ReturnType<typeof useService>["data"]>
}) {
  const [dialog, setDialog] = useState<"hold" | "recreate" | null>(null)
  const gate = useRecreateGate(name)
  const lock = useRunLock()
  /** Take down and Start are one switch following `hold`; neither is drawn while the row loads. */
  const hold = service === undefined ? undefined : service.hold ? "START" : "DOWN"
  const recreatable = name !== "steward-agent"
  const HoldIcon = hold === "START" ? PlayIcon : PowerIcon
  const holdLabel = hold ? t("steward.operations.ask", { kind: choice(hold) }) : ""

  /** A one-shot and a standby are started by runs alone, so neither has a button of its own. */
  if (service && resting(service)) return null

  /** Until the row arrives each button is a shape, hidden below `sm` like the button it stands for. */
  if (service === undefined) {
    return (
      <div className="flex items-center gap-2" aria-hidden>
        <Skeleton data-testid="action-skeleton" className="h-7 w-7 sm:w-[4.5rem]" />
        <Skeleton data-testid="action-skeleton" className="h-7 w-[6.25rem] max-sm:hidden" />
        {recreatable ? <Skeleton data-testid="action-skeleton" className="h-7 w-[5.5rem] max-sm:hidden" /> : null}
        <Skeleton data-testid="action-skeleton" className="size-7 sm:hidden" />
      </div>
    )
  }

  return (
    <div className="flex items-center gap-2">
      {/* A run for this service alone; with nothing to install it ends at "Nothing to do". */}
      <AskButton
        kind="UPDATE"
        services={[name]}
        label={t("steward.operations.ask", { kind: "update" })}
        size="sm"
        labelClassName="max-sm:hidden"
        className="max-sm:size-7 max-sm:px-0"
      />
      {hold ? (
        <AskButton
          kind={hold}
          services={[name]}
          label={holdLabel}
          variant={hold === "START" ? "default" : "outline"}
          size="sm"
          className="max-sm:hidden"
          open={dialog === "hold"}
          onOpenChange={(open) => setDialog(open ? "hold" : null)}
        />
      ) : null}
      <RecreateButton
        service={name}
        size="sm"
        className="max-sm:hidden"
        open={dialog === "recreate"}
        onOpenChange={(open) => setDialog(open ? "recreate" : null)}
      />
      {hold || recreatable ? (
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              className="sm:hidden"
              aria-label={t("steward.service-page.more-actions")}
            >
              <DotsThreeIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {hold ? (
              <DropdownMenuItem disabled={lock.locked} onSelect={() => setDialog("hold")}>
                <HoldIcon aria-hidden />
                {holdLabel}
              </DropdownMenuItem>
            ) : null}
            {recreatable ? (
              <DropdownMenuItem disabled={gate.unavailable} onSelect={() => setDialog("recreate")}>
                <ArrowsClockwiseIcon aria-hidden />
                {runKind("RECREATE")}
              </DropdownMenuItem>
            ) : null}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
    </div>
  )
}

/** The open run on a page in its scope: its stage, and Cancel while the countdown runs. */
export function ActiveRunLine({ run, name }: { run: Run | null; name: string }) {
  if (!run || !touches(run, name)) return null
  const stage = run.report && !ENDINGS.has(run.report.stage) ? run.report.stage : null
  const elsewhere = run.scope.length > 0 && !(run.scope.length === 1 && run.scope[0] === name)
  return (
    <div role="status" className="flex flex-wrap items-center gap-2 text-sm">
      <Link {...runPath(run)} className="font-medium underline-offset-4 hover:text-primary hover:underline">
        {runKind(run.kind)} #{run.id}
      </Link>
      {elsewhere ? <span className="text-muted-foreground">{run.scope.join(", ")}</span> : null}
      {stage ? <StageBadge stage={stage} /> : <RunStatus status={run.status} />}
      {cancellable(run) ? <CancelButton run={run} /> : null}
    </div>
  )
}

/** Why this service's log may end on purpose; see `ServiceConsole`'s `offline`. */
export function offline(run: Run | null, name: string, state: string | undefined): "going" | "gone" | undefined {
  if (run && run.kind !== "START" && run.status === "RUNNING" && touches(run, name)) {
    return "going"
  }
  return state !== undefined && state !== "running" ? "gone" : undefined
}

/** Whether this service is one a run starts on its own, a one-shot or a standby, and is not running now. */
export function resting(service: NonNullable<ReturnType<typeof useService>["data"]>): boolean {
  return (service.oneShot === true || service.standby === true) && service.state !== "running"
}

/** The head of a service page, exported for its test, since some fields must be absent rather than zero. */
export function ServiceHead({
  service,
  name,
}: {
  /** Absent while `/api/services/{name}` is out, each field then drawing its own shape. */
  service?: NonNullable<ReturnType<typeof useService>["data"]>
  name: string
}) {
  const [minutes, setMinutes] = useState<Range>(360)
  const cpu = useMetrics(name, "cpu_percent", minutes)
  const memory = useMetrics(name, "memory_bytes", minutes)
  if (service && resting(service)) return <RestingHead service={service} />
  return (
    /* On a phone the state is one compact row, the curves side by side under it; from `lg` one flat row. */
    <section className="grid grid-cols-2 gap-x-4 gap-y-3 sm:gap-y-5 lg:flex lg:items-start lg:gap-x-10">
      <div className="col-span-2 flex flex-col gap-2 max-sm:flex-row max-sm:flex-wrap max-sm:items-center">
        <span className="text-xs font-medium font-heading text-muted-foreground max-sm:sr-only">
          {t("steward.operations.state")}
        </span>
        <div className="flex flex-wrap items-center gap-2">
          {/* The hold is part of the state badge, the same reading the sidebar and network view draw. */}
          {service ? <ServiceState service={service} /> : <Skeleton className="h-5 w-20 rounded-full" />}
          {service ? <DriftBadge drift={service.drift} image={service.image} localBuild={service.localBuild} /> : null}
        </div>
        <span className="text-2xl font-semibold tabular-nums max-sm:text-sm">
          {service ? (
            service.startedAt ? (
              since(service.startedAt)
            ) : (
              "\u2013"
            )
          ) : (
            <SkeletonText width="short" className="h-[1lh]" />
          )}
        </span>
        <div className="max-sm:ml-auto">
          <RangeSelect minutes={minutes} onChange={setMinutes} />
        </div>
      </div>
      <MetricChart
        label={t("steward.service-page.cpu")}
        value={service ? percent(service.cpuPercent) : undefined}
        points={cpu.data?.points}
        format={percent}
        colour="var(--chart-1)"
        className="flex min-w-0 flex-col gap-1.5 lg:w-48"
        statClassName={PHONE_INLINE.className}
        valueClassName={PHONE_INLINE.valueClassName}
      />
      <MetricChart
        label={t("steward.service-page.ram")}
        value={service ? bytes(service.memoryBytes) : undefined}
        points={memory.data?.points}
        format={bytes}
        colour="var(--chart-2)"
        className="flex min-w-0 flex-col gap-1.5 lg:w-48"
        statClassName={PHONE_INLINE.className}
        valueClassName={PHONE_INLINE.valueClassName}
      />
      {/* Only services with a volume have a number, since 0 bytes would be a claim; an old one says its age. */}
      {service?.diskBytes === undefined ? null : (
        <Stat
          label={t("steward.service-page.disk")}
          value={bytes(service.diskBytes)}
          hint={diskAge(service.diskMeasuredAt)}
          {...PHONE_INLINE}
        />
      )}
    </section>
  )
}

/** A one-shot's or a standby's head: its state and, for a one-shot, how its last run ended, without curves. */
function RestingHead({ service }: { service: NonNullable<ReturnType<typeof useService>["data"]> }) {
  const { lastRun } = service
  return (
    <section className="flex flex-wrap items-start gap-x-10 gap-y-3">
      <div className="flex flex-col gap-2 max-sm:w-full max-sm:flex-row max-sm:items-center">
        <span className="text-xs font-medium font-heading text-muted-foreground max-sm:sr-only">
          {t("steward.operations.state")}
        </span>
        <div className="flex flex-wrap items-center gap-2">
          <ServiceState service={service} />
          <DriftBadge drift={service.drift} image={service.image} localBuild={service.localBuild} />
        </div>
      </div>
      {lastRun ? (
        <>
          <Stat
            label={t("steward.service-page.last-run")}
            value={relative(lastRun.finishedAt)}
            hint={dateTime(lastRun.finishedAt)}
            {...PHONE_INLINE}
          />
          <Stat label={t("steward.service-page.exit-code")} value={String(lastRun.exitCode)} {...PHONE_INLINE} />
        </>
      ) : null}
    </section>
  )
}

/** A `Stat` in the phone row: label, value and hint on one line, the value no larger than the text. */
const PHONE_INLINE = {
  className: "max-sm:flex-row max-sm:items-baseline max-sm:gap-1.5",
  valueClassName: "max-sm:text-sm",
}

function diskAge(measuredAt: string | undefined, now = Date.now()): string | undefined {
  if (!measuredAt) return undefined
  const at = new Date(measuredAt).getTime()
  return Number.isFinite(at) && now - at > 2 * 60 * 1000 ? relative(measuredAt, now) : undefined
}
