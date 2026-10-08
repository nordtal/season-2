import { type ReactNode, useId, useState } from "react"

import type { RunNote, RunOutcome, RunStep } from "@/lib/api"
import { choice, message, t } from "@/lib/texts"
import { Panel } from "@/components/steward/panel"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Badge } from "@/components/ui/badge"

/** The outcomes worst first, which is the order the filter offers them in. */
const OUTCOMES: readonly RunOutcome[] = ["FAILED", "WARNING", "DONE", "SKIPPED"]

const TONE: Record<RunOutcome, Tone> = { FAILED: "down", WARNING: "warn", DONE: "ok", SKIPPED: "idle" }

export type NoteFilter = { outcome?: RunOutcome; service?: string }

/**
 * The records a filter keeps, grouped by step in the order the run first reached each.
 *
 * A run writes its records in order, so within a step they stay in the order they happened.
 */
export function grouped(notes: RunNote[], filter: NoteFilter): { step: RunStep; notes: RunNote[] }[] {
  const groups = new Map<RunStep, RunNote[]>()
  for (const note of notes) {
    if (filter.outcome && note.outcome !== filter.outcome) continue
    if (filter.service && note.service !== filter.service) continue
    groups.set(note.step, [...(groups.get(note.step) ?? []), note])
  }
  return [...groups].map(([step, kept]) => ({ step, notes: kept }))
}

/** A run's records under their steps, filtered by outcome and by service; nothing for a run without any. */
export function RunNotes({ notes }: { notes: RunNote[] }) {
  const [filter, setFilter] = useState<NoteFilter>({})
  if (notes.length === 0) return null

  const services = [...new Set(notes.flatMap((note) => (note.service ? [note.service] : [])))]
  const count = (outcome: RunOutcome) => notes.filter((note) => note.outcome === outcome).length

  return (
    <Panel title={t("steward.operations.notes")}>
      <div className="flex flex-col gap-2">
        <Chips label={t("steward.operations.outcome")}>
          <Chip on={!filter.outcome} onClick={() => setFilter({ ...filter, outcome: undefined })}>
            {`${t("steward.operations.all-notes")} ${notes.length}`}
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
      <div className="flex flex-col gap-4">
        {grouped(notes, filter).map((group) => (
          <Step key={group.step} step={group.step} notes={group.notes} />
        ))}
      </div>
    </Panel>
  )
}

function Step({ step, notes }: { step: RunStep; notes: RunNote[] }) {
  const heading = useId()
  return (
    <section aria-labelledby={heading} className="flex flex-col gap-1">
      <h3 id={heading} className="text-sm font-medium">
        {t("run.step", { step: choice(step) })}
      </h3>
      <ul className="flex flex-col divide-y divide-border">
        {notes.map((note, index) => (
          /* Phone first: the badge and the service share a line and the words go under them; a grid from sm on. */
          <li
            key={`${note.what.key}-${note.service ?? ""}-${index}`}
            className="flex flex-col gap-1 py-2 sm:grid sm:grid-cols-[6rem_12rem_1fr] sm:items-baseline sm:gap-3"
          >
            <span className="flex items-center gap-2 sm:contents">
              <StatusBadge tone={TONE[note.outcome]} className="w-fit">
                {t("run.outcome", { outcome: choice(note.outcome) })}
              </StatusBadge>
              <span className="truncate text-sm font-medium">{note.service}</span>
            </span>
            <span className="text-sm whitespace-pre-line text-muted-foreground">{message(note.what)}</span>
          </li>
        ))}
      </ul>
    </section>
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
