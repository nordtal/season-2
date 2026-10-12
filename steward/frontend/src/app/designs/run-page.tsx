import { type ReactNode, useState } from "react"
import {
  ArrowRightIcon,
  CaretRightIcon,
  CheckIcon,
  CircleIcon,
  CircleNotchIcon,
  DotOutlineIcon,
  MinusIcon,
} from "@phosphor-icons/react"
import { cn } from "cn"

import type { Run, RunNote, RunOutcome } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { message, span, t } from "@/lib/texts"
import { useRun, useRuns } from "@/lib/queries"
import { Actor } from "@/components/steward/entity"
import { QueryState } from "@/components/steward/query-state"
import { RunStatus, runKind } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { ASKS, StageBadge, Was, runSeconds, summaryOf } from "@/pages/operations"
import { type Stage, type StageItem, type Verdict, stageName, stagesOf } from "@/app/designs/run-stages"

/**
 * Four layouts of the run page on real runs: a successful update, the newest failed run and a backup.
 *
 * `?run=` shows any other run and `?layout=` one layout alone. The run page draws the one picked; this page goes then.
 */
export function RunPageDesigns() {
  const runs = useRuns(80)
  const asked = new URLSearchParams(window.location.search)
  const [picked, setPicked] = useState(asked.get("run") ?? "")
  const [layout, setLayout] = useState(asked.get("layout") ?? "")

  const list = runs.data ?? []
  const examples = [
    list.find((run) => run.kind === "UPDATE" && run.report?.stage === "DONE"),
    list.find((run) => run.status === "FAILED"),
    list.find((run) => run.kind === "BACKUP" && run.status === "DONE"),
  ].filter((run): run is Run => run !== undefined)
  const chosen = picked || String(examples[0]?.id ?? "")

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Run page</h1>
      <div className="flex flex-col gap-2">
        <Pills>
          {examples.map((run) => (
            <Pill key={run.id} on={String(run.id) === chosen} onClick={() => setPicked(String(run.id))}>
              {`${runKind(run.kind)} #${run.id}`}
            </Pill>
          ))}
        </Pills>
        <Pills>
          {LAYOUTS.map((one) => (
            <Pill
              key={one.mark}
              on={layout === one.mark}
              onClick={() => setLayout(layout === one.mark ? "" : one.mark)}
            >
              {`${one.mark} ${one.name}`}
            </Pill>
          ))}
        </Pills>
      </div>
      <QueryState query={runs} rows={6}>
        {() => (chosen ? <Proposals id={chosen} layout={layout} /> : null)}
      </QueryState>
    </div>
  )
}

const LAYOUTS: { mark: string; name: string; draw: (run: Run) => ReactNode }[] = [
  { mark: "a", name: "Rail", draw: (run) => <Rail run={run} /> },
  { mark: "b", name: "Ledger", draw: (run) => <Ledger run={run} /> },
  { mark: "c", name: "Headline", draw: (run) => <Headline run={run} /> },
  { mark: "d", name: "Matrix", draw: (run) => <Matrix run={run} /> },
]

function Proposals({ id, layout }: { id: string; layout: string }) {
  const run = useRun(id)
  return (
    <QueryState query={run} rows={8}>
      {(data) => (
        <div className="flex flex-col gap-12">
          {LAYOUTS.filter((one) => !layout || one.mark === layout).map((one) => (
            <section
              key={one.mark}
              aria-label={one.name}
              data-layout={one.mark}
              className="flex flex-col gap-5 border-t border-border pt-4"
            >
              <h2 className="flex items-baseline gap-2 text-sm">
                <span className="font-semibold">{one.mark}</span>
                <span className="text-muted-foreground">{one.name}</span>
              </h2>
              {one.draw(data)}
            </section>
          ))}
        </div>
      )}
    </QueryState>
  )
}

/* The run as a whole, shared by every layout. */

/**
 * Kind and outcome on one line, who, when and how long under it: the four facts that are about the run.
 *
 * The earliest start shows only when it was not the moment of asking, and records about the run follow.
 */
function Header({ run }: { run: Run }) {
  const Icon = KIND_ICON[run.kind] ?? TERM_ICON.update
  const deferred = run.scheduledFor !== run.requested
  const own = run.report?.notes.filter((note) => note.step === "RUN") ?? []
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Icon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
        <span className="font-heading text-lg font-semibold">{runKind(run.kind)}</span>
        {run.report ? <StageBadge stage={run.report.stage} /> : <RunStatus status={run.status} />}
        {run.scope.map((service) => (
          <Badge key={service} variant="outline">
            {service}
          </Badge>
        ))}
      </div>
      <dl className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
        <Fact label={t("steward.operations.requested-by")}>
          <Actor kind={run.actorKind} id={run.actorId} />
        </Fact>
        {deferred ? <Fact label={t("steward.operations.no-earlier-than")}>{dateTime(run.scheduledFor)}</Fact> : null}
        <Fact label={t("steward.operations.started")}>{dateTime(run.started ?? run.requested)}</Fact>
        <Fact label={t("steward.operations.duration")}>
          <span className="tnum">{span(runSeconds(run))}</span>
        </Fact>
      </dl>
      {own.length > 0 ? <Records records={own} /> : null}
    </div>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 truncate">{children}</dd>
    </div>
  )
}

/** A run with no stage to draw: nothing to do, an unreadable report, or none yet. */
function NoStages({ run }: { run: Run }) {
  return (
    <p className="flex items-center gap-2 text-sm text-muted-foreground">
      <TERM_ICON.nothingToDo aria-hidden className="size-4 shrink-0" />
      {summaryOf(run)[0]}
    </p>
  )
}

/* How a stage went, as a mark, a colour and a word, so colour never carries it alone. */

const VERDICT_COLOUR: Record<Verdict, string> = {
  done: "text-success",
  failed: "text-destructive",
  warning: "text-warning",
  now: "text-warning",
  skipped: "text-muted-foreground",
  quiet: "text-muted-foreground",
  "not-reached": "text-muted-foreground",
}

/** Each kind's icon, the one its ask button carries. */
const KIND_ICON: Partial<Record<string, typeof TERM_ICON.update>> = ASKS

const OUTCOME_VERDICT: Record<RunOutcome, Verdict> = {
  DONE: "done",
  SKIPPED: "skipped",
  WARNING: "warning",
  FAILED: "failed",
}

function Mark({ verdict, className }: { verdict: Verdict; className?: string }) {
  const classes = cn("size-4 shrink-0", VERDICT_COLOUR[verdict], className)
  if (verdict === "done") return <CheckIcon aria-hidden weight="bold" className={classes} />
  if (verdict === "failed") return <TERM_ICON.failed aria-hidden weight="fill" className={classes} />
  if (verdict === "warning") return <TERM_ICON.warning aria-hidden weight="fill" className={classes} />
  if (verdict === "now") return <CircleNotchIcon aria-hidden className={cn(classes, "motion-safe:animate-spin")} />
  if (verdict === "skipped") return <MinusIcon aria-hidden className={classes} />
  if (verdict === "not-reached") return <CircleIcon aria-hidden className={classes} />
  return <DotOutlineIcon aria-hidden weight="fill" className={classes} />
}

function verdictWord(verdict: Verdict): string {
  if (verdict === "quiet") return t("steward.updates.nothing")
  if (verdict === "not-reached") return t("steward.operations.not-reached")
  if (verdict === "now") return t("run.status", { status: "running" })
  return t("run.outcome", { outcome: verdict })
}

/** Whether a stage has anything to show beyond its word. */
function loud(stage: Stage): boolean {
  return stage.records.length > 0 || stage.items.length > 0
}

/* What a stage holds: its records, then its services. */

/**
 * A stage's records, one line per outcome and wording, with every service it was about as a pill.
 *
 * Two standbys made ready are one line with two names, not two lines saying the same.
 */
function Records({ records }: { records: RunNote[] }) {
  const lines = new Map<string, { note: RunNote; services: string[] }>()
  for (const note of records) {
    const words = `${note.outcome} ${message(note.what)}`
    const line = lines.get(words) ?? { note, services: [] }
    if (note.service) line.services.push(note.service)
    lines.set(words, line)
  }
  return (
    <ul className="flex flex-col gap-1.5">
      {[...lines.values()].map(({ note, services }) => (
        <li key={`${note.outcome}-${message(note.what)}`} className="grid grid-cols-[1rem_1fr] gap-x-2">
          <Mark verdict={OUTCOME_VERDICT[note.outcome]} className="mt-0.5" />
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span
              className={cn(
                "text-sm",
                note.outcome === "FAILED" ? "text-destructive" : note.outcome === "DONE" ? "" : "text-muted-foreground",
              )}
            >
              {message(note.what)}
            </span>
            {services.map((service) => (
              <Badge key={service} variant="secondary">
                {service}
              </Badge>
            ))}
          </div>
        </li>
      ))}
    </ul>
  )
}

/** A stage's services: changes per service under Install, the reason beside a failed start, otherwise names. */
function Items({ stage }: { stage: Stage }) {
  if (stage.items.length === 0) return null
  if (stage.key === "INSTALL") return <Installs items={stage.items} />
  const failed = stage.items.filter((item) => item.state === "FAILED")
  const fine = stage.items.filter((item) => item.state !== "FAILED")
  return (
    <div className="flex flex-col gap-2">
      {failed.map((item) => (
        <div key={item.service} className="flex flex-col gap-0.5">
          <span className="flex items-center gap-2 text-sm font-medium">
            <Mark verdict="failed" />
            {item.service}
          </span>
          {item.detail ? <span className="pl-6 text-sm text-destructive">{message(item.detail)}</span> : null}
        </div>
      ))}
      {fine.length > 0 ? <Names items={fine} /> : null}
    </div>
  )
}

/**
 * What a stage installed: one row per service with its version jumps, then each told change once with its services.
 *
 * Nine services whose image was out of date are one line with nine names. A change never made is muted.
 */
function Installs({ items }: { items: StageItem[] }) {
  const told = new Map<string, { words: string; services: string[]; undone: boolean }>()
  for (const item of items) {
    for (const change of item.changes) {
      if (!change.told) continue
      const words = `${change.artefact} ${message(change.told)}`
      const line = told.get(words) ?? { words, services: [], undone: item.undone === true }
      line.services.push(item.service)
      told.set(words, line)
    }
  }
  const jumping = items.filter((item) => item.changes.some((change) => !change.told))
  return (
    <ul className="flex flex-col gap-1.5">
      {jumping.map((item) => (
        <li
          key={item.service}
          className={cn(
            "flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-sm",
            item.undone && "text-muted-foreground",
          )}
        >
          <span className="font-medium">{item.service}</span>
          <Jumps item={item} />
        </li>
      ))}
      {[...told.values()].map((line) => (
        <li key={line.words} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <span className={line.undone ? "text-muted-foreground" : undefined}>{line.words}</span>
          {line.services.map((service) => (
            <Badge key={service} variant="secondary">
              {service}
            </Badge>
          ))}
        </li>
      ))}
    </ul>
  )
}

/** A service's version jumps; the artefact is named only when it is not the service itself. */
function Jumps({ item }: { item: StageItem }) {
  return (
    <>
      {item.changes
        .filter((change) => !change.told)
        .map((change) => (
          <span key={change.artefact} className="inline-flex items-center gap-1.5">
            {change.artefact === item.service ? null : <code className="text-xs">{change.artefact}</code>}
            {change.from ? (
              <Was value={change.from} />
            ) : (
              <span className="text-muted-foreground">{t("steward.operations.added")}</span>
            )}
            {change.from ? <ArrowRightIcon aria-hidden className="size-3 shrink-0 text-muted-foreground" /> : null}
            <span className="tnum">{change.to}</span>
          </span>
        ))}
    </>
  )
}

function Names({ items }: { items: StageItem[] }) {
  return (
    <div className="flex flex-wrap gap-1">
      {items.map((item) => (
        <Badge key={item.service} variant="secondary">
          {item.service}
        </Badge>
      ))}
    </div>
  )
}

function Content({ stage }: { stage: Stage }) {
  return (
    <div className="flex flex-col gap-2">
      {stage.records.length > 0 ? <Records records={stage.records} /> : null}
      <Items stage={stage} />
    </div>
  )
}

/* A: Rail. One vertical line down the run; a quiet stage is a dot and its name, a loud one opens below its name. */

function Rail({ run }: { run: Run }) {
  const stages = stagesOf(run)
  return (
    <div className="flex flex-col gap-5">
      <Header run={run} />
      {stages.length === 0 ? (
        <NoStages run={run} />
      ) : (
        <ol className="flex flex-col">
          {stages.map((stage, index) => (
            <li key={stage.key} className="grid grid-cols-[1.25rem_1fr] gap-x-3">
              <div className="flex flex-col items-center">
                <Mark verdict={stage.verdict} className={loud(stage) ? "mt-0.5" : "mt-0.5 size-3.5"} />
                {index < stages.length - 1 ? <span aria-hidden className="w-px flex-1 bg-border" /> : null}
              </div>
              <div className={cn("flex min-w-0 flex-col gap-2", loud(stage) ? "pb-5" : "pb-2")}>
                <div className="flex items-baseline gap-2">
                  <span className={cn(loud(stage) ? "text-sm font-medium" : "text-xs text-muted-foreground")}>
                    {stageName(stage.key)}
                  </span>
                  {loud(stage) && stage.verdict !== "done" ? (
                    <span className={cn("text-xs", VERDICT_COLOUR[stage.verdict])}>{verdictWord(stage.verdict)}</span>
                  ) : null}
                  {!loud(stage) ? (
                    <span className="text-xs text-muted-foreground">{verdictWord(stage.verdict)}</span>
                  ) : null}
                </div>
                {loud(stage) ? <Content stage={stage} /> : null}
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

/* B: Ledger. One row per stage with its count; a row opens in place, a failed or warned one is open already. */

function Ledger({ run }: { run: Run }) {
  const stages = stagesOf(run)
  const shown = stages.filter(loud)
  const quiet = stages.filter((stage) => !loud(stage))
  return (
    <div className="flex flex-col gap-5">
      <Header run={run} />
      {stages.length === 0 ? (
        <NoStages run={run} />
      ) : (
        <div className="flex flex-col">
          <ul className="flex flex-col divide-y divide-border border-y border-border">
            {shown.map((stage) => (
              <li key={stage.key}>
                <details open={stage.verdict === "failed" || stage.verdict === "warning"} className="group">
                  <summary className="flex min-h-11 cursor-pointer list-none items-center gap-3 py-2 [&::-webkit-details-marker]:hidden">
                    <Mark verdict={stage.verdict} />
                    <span className="text-sm font-medium">{stageName(stage.key)}</span>
                    <span className={cn("ml-auto text-xs tnum", VERDICT_COLOUR[stage.verdict])}>{countOf(stage)}</span>
                    <CaretRightIcon
                      aria-hidden
                      className="size-3.5 shrink-0 text-muted-foreground group-open:rotate-90 motion-safe:transition-transform"
                    />
                  </summary>
                  <div className="pb-3 pl-7">
                    <Content stage={stage} />
                  </div>
                </details>
              </li>
            ))}
          </ul>
          {quiet.length > 0 ? (
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-xs text-muted-foreground">
              {quiet.map((stage) => (
                <span key={stage.key} className="flex items-center gap-1">
                  <Mark verdict={stage.verdict} className="size-3.5" />
                  {stageName(stage.key)}
                  <span className="sr-only">{verdictWord(stage.verdict)}</span>
                </span>
              ))}
            </div>
          ) : null}
        </div>
      )}
    </div>
  )
}

/** A row's count: its services, or its records when it has none, or its word. */
function countOf(stage: Stage): string {
  if (stage.verdict !== "done") return verdictWord(stage.verdict)
  if (stage.items.length > 0) return String(stage.items.length)
  return String(stage.records.length)
}

/* C: Headline. What the run did in large type, every stage as a strip of marks, then only the stages that speak. */

function Headline({ run }: { run: Run }) {
  const stages = stagesOf(run)
  const failed = run.report?.stage === "FAILED"
  const lead = leadOf(run, stages)
  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        {lead.map((part) => (
          <p
            key={part}
            className={cn(
              "font-heading text-2xl font-semibold tracking-tight",
              failed && part === lead.at(-1) && "text-destructive",
            )}
          >
            {part}
          </p>
        ))}
      </div>
      <Header run={run} />
      {stages.length > 0 ? (
        <>
          <ol className="flex flex-wrap gap-x-4 gap-y-2" aria-label={t("steward.operations.stages")}>
            {stages.map((stage) => (
              <li key={stage.key} className="flex items-center gap-1.5 text-xs">
                <Mark verdict={stage.verdict} className="size-3.5" />
                <span className={loud(stage) ? "" : "text-muted-foreground"}>{stageName(stage.key)}</span>
                <span className="sr-only">{verdictWord(stage.verdict)}</span>
              </li>
            ))}
          </ol>
          <div className="grid grid-cols-1 gap-x-10 gap-y-6 md:grid-cols-2">
            {stages.filter(loud).map((stage) => (
              <section key={stage.key} className="flex min-w-0 flex-col gap-2">
                <h3 className="flex items-center gap-2 text-sm font-medium">
                  <Mark verdict={stage.verdict} />
                  {stageName(stage.key)}
                  {stage.verdict !== "done" ? (
                    <span className={cn("text-xs font-normal", VERDICT_COLOUR[stage.verdict])}>
                      {verdictWord(stage.verdict)}
                    </span>
                  ) : null}
                </h3>
                <Content stage={stage} />
              </section>
            ))}
          </div>
        </>
      ) : null}
    </div>
  )
}

/**
 * The headline: what the run moved, saved and failed, or, when it failed before installing anything, why.
 *
 * Counting a run that stopped at its release would report the changes it only planned.
 */
function leadOf(run: Run, stages: Stage[]): string[] {
  const failedAt = stages.find((stage) => stage.verdict === "failed")
  const installed = stages.some((stage) => stage.key === "INSTALL" && stage.verdict !== "not-reached")
  if (!failedAt || installed || failedAt.key === "INSTALL") return summaryOf(run)
  const why = failedAt.records.filter((note) => note.outcome === "FAILED").map((note) => message(note.what))
  return why.length > 0 ? why : summaryOf(run)
}

/* D: Matrix. Services down, the stages that touch them across; what is about no one service stands above. */

/** The stages a service row has a cell in. */
const SERVICE_STAGES = ["STOP", "BACKUP", "INSTALL", "START"] as const

function Matrix({ run }: { run: Run }) {
  const stages = stagesOf(run)
  const columns = stages.filter((stage) => (SERVICE_STAGES as readonly string[]).includes(stage.key))
  const services = [...new Set(columns.flatMap((stage) => stage.items.map((item) => item.service)))]
  const speaking = stages.filter((stage) => stage.records.length > 0)
  return (
    <div className="flex flex-col gap-5">
      <Header run={run} />
      {stages.length === 0 ? (
        <NoStages run={run} />
      ) : (
        <>
          {speaking.length > 0 ? (
            <dl className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-[8rem_1fr]">
              {speaking.map((stage) => (
                <div key={stage.key} className="contents">
                  <dt className="flex items-center gap-2 text-sm font-medium">
                    <Mark verdict={stage.verdict} />
                    {stageName(stage.key)}
                  </dt>
                  <dd className="min-w-0 pl-6 sm:pl-0">
                    <Records records={stage.records} />
                  </dd>
                </div>
              ))}
            </dl>
          ) : null}
          {services.length > 0 ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-muted-foreground">
                  <th className="py-1 pr-2 text-left font-normal">{t("steward.operations.service")}</th>
                  {columns.map((stage) => (
                    <th key={stage.key} className="w-12 px-1 py-1 text-center font-normal sm:w-24">
                      <span className="flex flex-col items-center gap-0.5">
                        <Mark verdict={stage.verdict} className="size-3.5" />
                        <span className="max-w-full truncate">{stageName(stage.key)}</span>
                      </span>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border border-y border-border">
                {services.map((service) => {
                  const cells = columns.map((stage) => ({
                    stage,
                    item: stage.items.find((one) => one.service === service),
                  }))
                  const install = cells.find((cell) => cell.stage.key === "INSTALL")?.item
                  const failure = cells.find((cell) => cell.item?.state === "FAILED")?.item
                  return (
                    <tr key={service} className="align-top">
                      <td className="py-2 pr-2">
                        <div className="flex min-w-0 flex-col gap-0.5">
                          <span className="font-medium">{service}</span>
                          {install ? (
                            <div
                              className={cn(
                                "flex flex-wrap gap-x-3 text-xs",
                                install.undone && "text-muted-foreground",
                              )}
                            >
                              <Jumps item={install} />
                              {install.changes.map((change) =>
                                change.told ? (
                                  <span key={change.artefact} className="text-muted-foreground">
                                    {`${change.artefact} ${message(change.told)}`}
                                  </span>
                                ) : null,
                              )}
                            </div>
                          ) : null}
                          {failure?.detail ? (
                            <span className="text-xs text-destructive">{message(failure.detail)}</span>
                          ) : null}
                        </div>
                      </td>
                      {cells.map(({ stage, item }) => (
                        <td key={stage.key} className="px-1 py-2 text-center">
                          {item ? (
                            <span className="inline-flex">
                              <Mark
                                verdict={item.state === "FAILED" ? "failed" : item.undone ? "not-reached" : "done"}
                              />
                              <span className="sr-only">{stageName(stage.key)}</span>
                            </span>
                          ) : null}
                        </td>
                      ))}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          ) : null}
        </>
      )}
    </div>
  )
}

function Pills({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap gap-1.5">{children}</div>
}

function Pill({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <Badge asChild variant={on ? "default" : "outline"} className="h-7 cursor-pointer px-3">
      <button type="button" aria-pressed={on} onClick={onClick}>
        {children}
      </button>
    </Badge>
  )
}
