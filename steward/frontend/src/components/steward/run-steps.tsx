import { type ReactNode, useState } from "react"

import { choice, type MessageRef, message, t } from "@/lib/texts"
import { Panel } from "@/components/steward/panel"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Badge } from "@/components/ui/badge"

/** What a run's step and a plan's step share: the plan's are computed and never stored. */
export type StepLike = { step: string; service?: string; outcome?: string; lines: MessageRef[] }

const OUTCOMES = ["FAILED", "WARNING", "DONE", "SKIPPED"] as const

const TONE: Record<string, Tone> = { FAILED: "down", WARNING: "warn", DONE: "ok", SKIPPED: "idle" }

export type StepFilter = { outcome?: string; service?: string }

/** Returns the steps a filter keeps, in the order the run began them. */
export function kept<S extends StepLike>(steps: S[], filter: StepFilter): S[] {
  return steps.filter(
    (step) =>
      (!filter.outcome || step.outcome === filter.outcome) && (!filter.service || step.service === filter.service),
  )
}

export function RunSteps({ steps, title }: { steps: StepLike[]; title: string }) {
  const [filter, setFilter] = useState<StepFilter>({})
  if (steps.length === 0) return null

  const services = [...new Set(steps.flatMap((step) => (step.service ? [step.service] : [])))]
  const count = (outcome: string) => steps.filter((step) => step.outcome === outcome).length

  return (
    <Panel title={title}>
      <div className="flex flex-col gap-2">
        <Chips label={t("steward.operations.outcome")}>
          <Chip on={!filter.outcome} onClick={() => setFilter({ ...filter, outcome: undefined })}>
            {`${t("steward.operations.all-notes")} ${steps.length}`}
          </Chip>
          {OUTCOMES.filter((outcome) => count(outcome) > 0).map((outcome) => (
            <Chip
              key={outcome}
              on={filter.outcome === outcome}
              onClick={() => setFilter({ ...filter, outcome: filter.outcome === outcome ? undefined : outcome })}
            >
              {`${t("run.outcome", { outcome: choice(outcome) })} ${count(outcome)}`}
            </Chip>
          ))}
        </Chips>
        {services.length > 1 ? (
          <Chips label={t("steward.operations.service")}>
            {services.map((service) => (
              <Chip
                key={service}
                on={filter.service === service}
                onClick={() => setFilter({ ...filter, service: filter.service === service ? undefined : service })}
              >
                {service}
              </Chip>
            ))}
          </Chips>
        ) : null}
      </div>
      <ul className="flex flex-col divide-y divide-border">
        {kept(steps, filter).map((step, index) => (
          /*
           * Phone first: the badge and the step share a line and the facts go under them. From `sm` up it is a grid.
           */
          <li
            key={`${step.step}-${step.service ?? ""}-${index}`}
            className="flex flex-col gap-1 py-2 sm:grid sm:grid-cols-[6rem_14rem_1fr] sm:items-baseline sm:gap-3"
          >
            <span className="flex items-center gap-2 sm:contents">
              <StatusBadge tone={step.outcome ? (TONE[step.outcome] ?? "idle") : "warn"} className="w-fit">
                {step.outcome
                  ? t("run.outcome", { outcome: choice(step.outcome) })
                  : t("run.status", { status: choice("RUNNING") })}
              </StatusBadge>
              <span className="truncate text-sm font-medium">
                {t("run.step-kind", { kind: choice(step.step) })}
                {step.service ? <span className="text-muted-foreground">{` ${step.service}`}</span> : null}
              </span>
            </span>
            <span className="flex flex-col text-sm text-muted-foreground">
              {step.lines.map((line, at) => (
                <span key={at}>{message(line)}</span>
              ))}
            </span>
          </li>
        ))}
      </ul>
    </Panel>
  )
}

function Chips({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1.5">
      {children}
    </div>
  )
}

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <Badge asChild variant={on ? "default" : "outline"} className="cursor-pointer">
      <button type="button" aria-pressed={on} onClick={onClick}>
        {children}
      </button>
    </Badge>
  )
}
