import { DotsThreeIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { useEffect, useState } from "react"
import { cn } from "cn"
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

/** What `/services/$name` keeps in its URL. Console is the default and is never written. */
export type ServiceSearch = { tab?: "settings" | "plugins"; file?: string }

export function serviceSearch(search: Record<string, unknown>): ServiceSearch {
  const answer: ServiceSearch = {}
  if (search.tab === "settings" || search.tab === "plugins") answer.tab = search.tab
  if (typeof search.file === "string" && search.file !== "") answer.file = search.file
  return answer
}

type Tab = "console" | "settings" | "plugins"

/** Returns which tabs this service has, or `undefined` until they are known. */
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

/** One service: a head with its actions and who is on it, and its tabs. */
export function ServicePage() {
  const { name } = useParams({ from: "/services/$name" })
  const search = useSearch({ from: "/services/$name" })
  const navigate = useNavigate({ from: "/services/$name" })
  const service = useService(name)
  const tabs = useServiceTabs(name)
  const tab: Tab = search.tab ?? "console"
  const { run } = useRunLock()

  /**
   * A tab that the service lacks goes back to Console and replaces the entry, so Back skips it.
   */
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
          /** A tab change replaces the entry, so Back leaves the page. */
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
                <TERM_ICON.console aria-hidden />
                {t("steward.service-page.console")}
              </TabsTrigger>
              {tabs.includes("settings") ? (
                <TabsTrigger value="settings" className="sm:px-3">
                  <TERM_ICON.settings aria-hidden />
                  {t("steward.service-page.settings")}
                </TabsTrigger>
              ) : null}
              {tabs.includes("plugins") ? (
                <TabsTrigger value="plugins" className="sm:px-3">
                  <TERM_ICON.plugins aria-hidden />
                  {t("steward.service-page.plugins")}
                </TabsTrigger>
              ) : null}
            </TabsList>
          ) : (
            /** One tab is still drawn, so the page says what it shows. */
            <TabsList className="w-full sm:w-fit">
              <TabsTrigger value="console" className="sm:px-3">
                <TERM_ICON.console aria-hidden />
                {t("steward.service-page.console")}
              </TabsTrigger>
            </TabsList>
          )
        ) : (
          <Skeleton className="h-8 w-full rounded-lg sm:w-96" />
        )}

        <TabsContent value="console" className="flex flex-col gap-6">
          {/* An unknown service is a 404 from Steward. */}
          <QueryState query={service}>{(data) => <ServiceHead service={data} name={name} />}</QueryState>
          <ServiceConsole
            name={name}
            hasConsole={service.data?.hasConsole ?? false}
            capacity={service.data?.logCapacity}
            offline={offline(run, name, service.data?.state)}
          />
          {/* Their own area under the console, because the header's actions are about the container. */}
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
 * Shows Update, Take down or Start, and Recreate. Below `sm` the last two move into a menu that opens the buttons' own dialogs.
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
  /** Take down and Start are one switch that follows `hold`. */
  const hold = service === undefined ? undefined : service.hold ? "START" : "DOWN"
  const recreatable = name !== "steward-agent"
  const HoldIcon = hold === "START" ? TERM_ICON.start : TERM_ICON.down
  const holdLabel = hold ? t("steward.operations.ask", { kind: choice(hold) }) : ""

  /** A one-shot and a standby are started by runs alone. */
  if (service && resting(service)) return null

  /**
   * Until the row arrives, each button is a shape, hidden below `sm` like the button it stands for.
   */
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
      {/* A run for this service alone. With nothing to install it ends at "Nothing to do". */}
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
                <TERM_ICON.recreate aria-hidden />
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
      <Link {...runPath(run)} className="font-medium underline-offset-4 tap-target hover:text-primary hover:underline">
        {runKind(run.kind)} #{run.id}
      </Link>
      {elsewhere ? <span className="text-muted-foreground">{run.scope.join(", ")}</span> : null}
      {stage ? <StageBadge stage={stage} /> : <RunStatus status={run.status} />}
      {cancellable(run) ? <CancelButton run={run} /> : null}
    </div>
  )
}

/** Returns why this service's log may end on purpose. See `ServiceConsole`'s `offline`. */
export function offline(run: Run | null, name: string, state: string | undefined): "going" | "gone" | undefined {
  if (run && run.kind !== "START" && run.status === "RUNNING" && touches(run, name)) {
    return "going"
  }
  return state !== undefined && state !== "running" ? "gone" : undefined
}

/**
 * Returns whether a run starts this service on its own, a one-shot or a standby, and it is not running now.
 */
export function resting(service: NonNullable<ReturnType<typeof useService>["data"]>): boolean {
  return (service.oneShot === true || service.standby === true) && service.state !== "running"
}

/** The head of a service page, exported because some fields must be absent and not zero. */
export function ServiceHead({
  service,
  name,
}: {
  service?: NonNullable<ReturnType<typeof useService>["data"]>
  name: string
}) {
  const [minutes, setMinutes] = useState<Range>(360)
  const cpu = useMetrics(name, "cpu_percent", minutes)
  const memory = useMetrics(name, "memory_bytes", minutes)
  if (service && resting(service)) return <RestingHead service={service} />
  return (
    /*
     * One band of three fixed lines: names, values, then what draws them. On a phone the state is one compact row above it.
     */
    <section className="flex flex-col gap-y-3 sm:gap-y-4 lg:flex-row lg:gap-x-10">
      <div className="flex shrink-0 flex-col max-sm:flex-row max-sm:flex-wrap max-sm:items-center max-sm:gap-2">
        <span className={cn(NAME_LINE, "max-sm:sr-only")}>{t("steward.operations.state")}</span>
        <span className={cn(VALUE_LINE, "font-semibold max-sm:order-last max-sm:h-auto")}>
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
        <StateBadges service={service} />
      </div>
      <div className="flex min-w-0 flex-col">
        {/* The range belongs to the stats, so it sits on their name line. */}
        <div className={cn(NAME_LINE, "gap-3")}>
          {t("steward.service-page.stats")}
          <RangeSelect minutes={minutes} onChange={setMinutes} />
        </div>
        <div className="grid grid-cols-3 gap-x-4 lg:grid-cols-[repeat(3,11rem)] lg:gap-x-10">
          <MetricChart
            label={t("steward.service-page.cpu")}
            value={service ? percent(service.cpuPercent) : undefined}
            points={cpu.data?.points}
            format={percent}
            colour="var(--chart-1)"
            className="flex min-w-0 flex-col"
            statClassName={INLINE.className}
            valueClassName={INLINE.valueClassName}
          />
          <MetricChart
            label={t("steward.service-page.ram")}
            value={service ? bytes(service.memoryBytes) : undefined}
            points={memory.data?.points}
            format={bytes}
            colour="var(--chart-2)"
            className="flex min-w-0 flex-col"
            statClassName={INLINE.className}
            valueClassName={INLINE.valueClassName}
          />
          {/* Only services with a volume have a number, because 0 bytes would be a claim. */}
          {service?.diskBytes === undefined ? null : (
            <div className="flex min-w-0 flex-col">
              <Stat label={t("steward.service-page.disk")} value={bytes(service.diskBytes)} {...INLINE} />
              <span className="h-10 text-xs text-muted-foreground">{diskAge(service.diskMeasuredAt)}</span>
            </div>
          )}
        </div>
      </div>
    </section>
  )
}

/** The state badges, on the line where the curves start. The hold is part of the state badge, as in the sidebar. */
function StateBadges({
  service,
  className = "items-start lg:min-h-10",
}: {
  service?: NonNullable<ReturnType<typeof useService>["data"]>
  className?: string
}) {
  return (
    <div className={cn("flex flex-wrap gap-2", className)}>
      {service ? <ServiceState service={service} /> : <Skeleton className="h-5 w-20 rounded-full" />}
      {service ? <DriftBadge drift={service.drift} image={service.image} localBuild={service.localBuild} /> : null}
    </div>
  )
}

/** The head of a one-shot or a standby: its state and, for a one-shot, when its last run ended and how. */
function RestingHead({ service }: { service: NonNullable<ReturnType<typeof useService>["data"]> }) {
  const { lastRun } = service
  return (
    <section className="flex flex-col gap-y-3 sm:flex-row sm:gap-x-10">
      <div className="flex shrink-0 flex-col">
        <span className={cn(NAME_LINE, "max-sm:sr-only")}>{t("steward.operations.state")}</span>
        {/* Nothing is drawn under a resting service, so its badges take the value line. */}
        <StateBadges service={service} className="items-center sm:h-8" />
      </div>
      {lastRun ? (
        <div className="flex min-w-0 flex-col">
          <span className={NAME_LINE}>{t("steward.service-page.last-run")}</span>
          <div className="grid grid-cols-3 gap-x-4 sm:flex sm:gap-x-10">
            <div className="flex min-w-0 flex-col">
              <span className={cn(VALUE_LINE, "font-semibold")}>{relative(lastRun.finishedAt)}</span>
              <span className="text-xs text-muted-foreground">{dateTime(lastRun.finishedAt)}</span>
            </div>
            <Stat label={t("steward.service-page.exit-code")} value={String(lastRun.exitCode)} {...INLINE} />
          </div>
        </div>
      ) : null}
    </section>
  )
}

/** The name line of the head, as tall as the range select that shares it. */
const NAME_LINE = "flex h-6 items-center text-xs font-medium font-heading text-muted-foreground"

/** The value line of the head. Every value on it has one size, so all of them share a baseline. */
const VALUE_LINE = "flex h-8 items-baseline text-xl whitespace-nowrap tnum max-sm:text-sm"

/** A `Stat` on the value line, its name before its value. */
const INLINE = {
  className: "h-8 flex-row items-baseline gap-1.5 whitespace-nowrap",
  valueClassName: "max-sm:text-sm",
}

function diskAge(measuredAt: string | undefined, now = Date.now()): string | undefined {
  if (!measuredAt) return undefined
  const at = new Date(measuredAt).getTime()
  return Number.isFinite(at) && now - at > 2 * 60 * 1000 ? relative(measuredAt, now) : undefined
}
