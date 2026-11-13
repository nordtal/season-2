import { useState, type ReactNode } from "react"
import { CaretDownIcon, CheckIcon, CircleIcon, CircleNotchIcon, MinusIcon } from "@/components/ui/icons"
import { cn } from "@/lib/utils"

import {
  pillsOf,
  type Pill,
  type RailStep,
  stageName,
  stagesOf,
  type Stage,
  stepVerdict,
  type Verdict,
} from "@/lib/run-stages"
import { choice, message, t } from "@/lib/texts"
import { StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"

const VERDICT_COLOUR: Record<Verdict, string> = {
  done: "text-success",
  failed: "text-destructive",
  warning: "text-warning",
  running: "text-warning",
  skipped: "text-muted-foreground",
  planned: "text-muted-foreground",
}

/** The word a mark stands for, so its colour is never the only signal. */
function verdictWord(verdict: Verdict): string {
  if (verdict === "running") return t("run.status", { status: choice("RUNNING") })
  if (verdict === "planned") return t("run.status", { status: choice("PENDING") })
  return t("run.outcome", { outcome: verdict })
}

export function Mark({ verdict, className }: { verdict: Verdict; className?: string }) {
  const classes = cn("size-4 shrink-0", VERDICT_COLOUR[verdict], className)
  const word = verdictWord(verdict)
  if (verdict === "done") return <CheckIcon role="img" aria-label={word} className={classes} />
  if (verdict === "failed") return <TERM_ICON.failed role="img" aria-label={word} className={classes} />
  if (verdict === "warning") return <TERM_ICON.warning role="img" aria-label={word} className={classes} />
  if (verdict === "running") {
    return <CircleNotchIcon role="img" aria-label={word} className={cn(classes, "motion-safe:animate-spin")} />
  }
  if (verdict === "skipped") return <MinusIcon role="img" aria-label={word} className={classes} />
  return <CircleIcon role="img" aria-label={word} className={classes} />
}

/**
 * A run as its stages, top to bottom: each with its mark, its name and its coarse facts as pills. A stage opens to its steps, one fact per line. A failed or warned stage starts open.
 */
export function RunRail({ steps, ending }: { steps: RailStep[]; ending?: string }) {
  const stages = stagesOf(steps)
  return (
    <ol className="flex flex-col">
      {stages.map((stage, index) => (
        <StageRow key={stage.key} stage={stage} ending={ending} last={index === stages.length - 1} />
      ))}
    </ol>
  )
}

function StageRow({ stage, ending, last }: { stage: Stage; ending?: string; last: boolean }) {
  return (
    <RailStage
      id={`stage-${stage.key}`}
      name={stageName(stage.key)}
      verdict={stage.verdict}
      pills={pillsOf(stage, ending)}
      last={last}
    >
      <Steps steps={stage.steps} />
    </RailStage>
  )
}

/**
 * One stage of a rail: its mark, its name and its pills, and what it opens to. A failed or warned stage starts open.
 *
 * Without children the name is plain text, since there is nothing to open.
 */
export function RailStage({
  id,
  name,
  verdict,
  pills,
  last,
  children,
}: {
  id: string
  name: string
  verdict: Verdict
  pills: Pill[]
  last: boolean
  children?: ReactNode
}) {
  const [open, setOpen] = useState(verdict === "failed" || verdict === "warning")
  const planned = verdict === "planned"

  return (
    <li className="grid grid-cols-[1.25rem_1fr] gap-x-3">
      <div className="flex flex-col items-center">
        <Mark verdict={verdict} className="mt-2.5" />
        {last ? null : <span aria-hidden className="mt-1 w-px flex-1 bg-border" />}
      </div>
      <div className={cn("flex min-w-0 flex-col gap-1.5", last ? "" : "pb-4")}>
        {children ? (
          <button
            type="button"
            aria-expanded={open}
            aria-controls={id}
            onClick={() => setOpen(!open)}
            className={cn(
              "flex min-h-9 items-center gap-2 rounded-md text-left text-sm font-medium tap-target hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              planned && "font-normal text-muted-foreground",
            )}
          >
            {name}
            <CaretDownIcon
              aria-hidden
              className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", open && "rotate-180")}
            />
          </button>
        ) : (
          <span
            className={cn(
              "flex min-h-9 items-center text-sm font-medium",
              planned && "font-normal text-muted-foreground",
            )}
          >
            {name}
          </span>
        )}
        {pills.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {pills.map((pill, at) => (
              <PillBadge key={at} pill={pill} />
            ))}
          </div>
        ) : null}
        {open && children ? <div id={id}>{children}</div> : null}
      </div>
    </li>
  )
}

/**
 * A pill wraps onto further lines on a narrow screen and breaks a word only when it is longer than a line.
 * `text-clip` displaces StatusBadge's `truncate` in `cn`, so the wrap does not hang on the order of CSS rules.
 */
const WRAPS = "h-auto justify-start whitespace-normal text-clip text-left wrap-anywhere"

export function PillBadge({ pill }: { pill: Pill }) {
  if (pill.tone)
    return (
      <StatusBadge tone={pill.tone} className={WRAPS}>
        {pill.text}
      </StatusBadge>
    )
  return (
    <Badge variant="outline" className={cn("max-w-full font-normal", WRAPS)}>
      {pill.services ? <TERM_ICON.service aria-hidden className="size-3 text-muted-foreground" /> : null}
      {pill.text}
    </Badge>
  )
}

/** A stage's steps in the order the run began them, each fact on its own line below its step. */
export function Steps({ steps }: { steps: RailStep[] }) {
  return (
    <ul className="mt-1 flex flex-col gap-2.5">
      {steps.map((step, index) => (
        <li key={`${step.step}-${step.service ?? ""}-${index}`} className="grid grid-cols-[1rem_1fr] gap-x-2">
          <Mark verdict={stepVerdict(step)} className="mt-0.5 size-3.5" />
          <div className="flex min-w-0 flex-col gap-0.5">
            <span className="text-sm">
              {t("run.step-kind", { kind: choice(step.step) })}
              {step.service ? <span className="text-muted-foreground">{` ${step.service}`}</span> : null}
            </span>
            {step.lines.map((line, at) => (
              <span key={at} className="break-words pl-3 text-sm text-muted-foreground">
                {message(line)}
              </span>
            ))}
          </div>
        </li>
      ))}
    </ul>
  )
}
