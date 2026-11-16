import { PlusIcon, TrashIcon, XIcon } from "@/components/ui/icons"
import { useId, useState, type ReactNode } from "react"

import type { Npc, NpcMenu, NpcStep, NpcTextMeasurement } from "@/lib/api"
import {
  type Language,
  type Then,
  LANGUAGES,
  LONGEST_TEXT,
  MOST_ANSWERS,
  addAnswer,
  addStep,
  deleteStep,
  labelOf,
  removeAnswer,
  setAnswerText,
  setAnswerThen,
  setStart,
  setStepText,
  setThen,
  stepOf,
  thenOf,
} from "@/lib/npc"
import { measureKey } from "@/lib/queries-npcs"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import { menuName } from "@/components/steward/npc/graph"
import { Hint } from "@/components/steward/status"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"

/** The editor of the step picked on the canvas: its two texts with the panel's room, what follows it, and its answers. */

type Edit = (change: (npc: Npc) => Npc) => void

export type Measured = Map<string, NpcTextMeasurement> | undefined

/** A heading of the editor's side column, with the actions that belong to it. */
export function Heading({ children, actions }: { children: string; actions?: ReactNode }) {
  return (
    <div className="flex min-h-8 flex-wrap items-center justify-between gap-2">
      <h2 className="text-sm font-medium text-muted-foreground">{children}</h2>
      {actions ? <div className="flex flex-wrap items-center gap-1">{actions}</div> : null}
    </div>
  )
}

/** How much of the panel's room a text takes, and the sentence that says what the panel cuts. */
export function Room({ measurement, answers }: { measurement: NpcTextMeasurement | undefined; answers?: boolean }) {
  if (measurement === undefined) return <div className="h-1 rounded-full bg-muted" />
  const share = Math.min(1, measurement.room === 0 ? 0 : measurement.width / measurement.room)
  const lines = measurement.lines.length > measurement.lineLimit
  return (
    <>
      <div className="h-1 overflow-hidden rounded-full bg-muted">
        <div
          className={cn("h-full rounded-full", measurement.fits ? "bg-success" : "bg-warning")}
          style={{ width: `${(lines ? 1 : share) * 100}%` }}
        />
      </div>
      {measurement.fits ? null : (
        <p role="status" className="text-xs text-warning">
          {lines
            ? t("steward.npcs.cut-lines", { limit: measurement.lineLimit, answers: answers === true })
            : t("steward.npcs.cut-width")}
        </p>
      )}
    </>
  )
}

/**
 * Answers `value`, or the last one it had under the same `key` while it has none: a text being typed keeps the
 * measurement of the text before it until its own arrives.
 */
function useKept<T>(value: T | undefined, key: string): T | undefined {
  const [kept, setKept] = useState({ key, value })
  if (kept.key !== key || (value !== undefined && kept.value !== value)) setKept({ key, value })
  return kept.key === key ? (value ?? kept.value) : value
}

function StepText({
  step,
  language,
  measured,
  edit,
}: {
  step: NpcStep
  language: Language
  measured: Measured
  edit: Edit
}) {
  const id = useId()
  const text = step.text[language]
  const measurement = useKept(
    text === "" ? undefined : measured?.get(measureKey({ text, kind: "BODY", answers: step.answers.length })),
    text === "" ? "" : `${step.id}:${step.answers.length}`,
  )
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-2">
        <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
          {t("steward.npcs.language", { language })}
        </Label>
        {measurement ? (
          <span className="text-xs text-muted-foreground tnum">
            {t("steward.npcs.lines", { lines: measurement.lines.length, limit: measurement.lineLimit })}
          </span>
        ) : null}
      </div>
      <Textarea
        id={id}
        lang={language}
        value={text}
        maxLength={LONGEST_TEXT}
        className={cn("min-h-10 resize-none", measurement && !measurement.fits && "border-warning")}
        onChange={(event) => edit((npc) => setStepText(npc, step.id, language, event.target.value))}
      />
      <Room measurement={measurement} answers={step.answers.length > 0} />
    </div>
  )
}

/** A step's name in a picker: the start of its text. */
function nameOf(step: NpcStep): string {
  return labelOf(step) || t("steward.npcs.no-text")
}

const END = "end"
const NEW = "new"

function valueOf(then: Then): string {
  return then.to === "end" ? END : then.to === "menu" ? `menu:${then.menu}` : `step:${then.step}`
}

/**
 * What follows a text or an answer, as one picker: the end, a menu where `menus` is given, every other step, and a
 * new step. The closed picker shows the choice in a word or two; the open one says what each choice does.
 */
function ThenPicker({
  label,
  then,
  npc,
  menus,
  except,
  onThen,
  onNew,
  className,
}: {
  label: string
  then: Then
  npc: Npc
  /** The menus on offer; an answer leads to a step or the end and passes none. */
  menus?: NpcMenu[]
  /** The step that cannot be picked: a text does not go on to itself. */
  except?: string
  onThen: (then: Then) => void
  onNew: () => void
  className?: string
}) {
  const steps = npc.dialogue.steps.filter((step) => step.id !== except)
  const target = then.to === "step" ? stepOf(npc.dialogue, then.step) : undefined
  // A menu smp no longer registers stays in the list, so the picker shows what the step holds.
  const offered =
    then.to === "menu" && menus?.every((menu) => menu.key !== then.menu)
      ? [...menus, { key: then.menu, hint: { en: then.menu, de: then.menu } }]
      : (menus ?? [])
  return (
    <Select
      value={valueOf(then)}
      onValueChange={(value) => {
        if (value === NEW) onNew()
        else if (value === END) onThen({ to: "end" })
        else if (value.startsWith("menu:")) onThen({ to: "menu", menu: value.slice(5) })
        else onThen({ to: "step", step: value.slice(5) })
      }}
    >
      <SelectTrigger aria-label={label} className={cn("w-full min-w-0", className)}>
        <SelectValue>
          <span className="truncate">
            {then.to === "end"
              ? t("steward.npcs.end")
              : then.to === "menu"
                ? menuName(offered, then.menu)
                : target
                  ? nameOf(target)
                  : t("steward.npcs.end")}
          </span>
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={END}>{t("steward.npcs.then-end")}</SelectItem>
        {offered.map((menu) => (
          <SelectItem key={menu.key} value={`menu:${menu.key}`}>
            {t("steward.npcs.then-menu", { menu: menuName(offered, menu.key) })}
          </SelectItem>
        ))}
        {steps.map((step) => (
          <SelectItem key={step.id} value={`step:${step.id}`}>
            <span className="max-w-64 truncate">{t("steward.npcs.then-step", { step: nameOf(step) })}</span>
          </SelectItem>
        ))}
        <SelectItem value={NEW}>{t("steward.npcs.then-new")}</SelectItem>
      </SelectContent>
    </Select>
  )
}

function Answers({ npc, step, measured, edit }: { npc: Npc; step: NpcStep; measured: Measured; edit: Edit }) {
  const full = step.answers.length >= MOST_ANSWERS
  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.answers")}</Heading>
      {step.answers.map((answer, index) => {
        const wide = LANGUAGES.some((language) => {
          const text = answer.text[language]
          return text !== "" && measured?.get(measureKey({ text, kind: "ANSWER", answers: 0 }))?.fits === false
        })
        return (
          <div key={index} className="flex flex-col gap-1.5">
            <div className="grid grid-cols-1 gap-1.5 xl:grid-cols-2">
              {LANGUAGES.map((language) => (
                <Textarea
                  key={language}
                  lang={language}
                  rows={1}
                  aria-label={t("steward.npcs.answer-text", { index: index + 1, language })}
                  value={answer.text[language]}
                  maxLength={LONGEST_TEXT}
                  className={cn("min-h-8 resize-none", wide && "border-warning")}
                  onChange={(event) =>
                    edit((draft) => setAnswerText(draft, step.id, index, language, event.target.value))
                  }
                />
              ))}
              <div className="flex min-w-0 items-start gap-1 xl:col-span-2">
                <ThenPicker
                  label={t("steward.npcs.answer-then", { index: index + 1 })}
                  then={answer.next === undefined ? { to: "end" } : { to: "step", step: answer.next }}
                  npc={npc}
                  onThen={(then) => edit((draft) => setAnswerThen(draft, step.id, index, then))}
                  onNew={() =>
                    edit((draft) => {
                      const added = addStep(draft, step.id)
                      return setAnswerThen(added.npc, step.id, index, { to: "step", step: added.id })
                    })
                  }
                />
                <Hint tip={t("steward.npcs.remove-answer", { index: index + 1 })}>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="tap-target shrink-0"
                    aria-label={t("steward.npcs.remove-answer", { index: index + 1 })}
                    onClick={() => edit((draft) => removeAnswer(draft, step.id, index))}
                  >
                    <XIcon aria-hidden />
                  </Button>
                </Hint>
              </div>
            </div>
            {wide ? (
              <p role="status" className="text-xs text-warning">
                {t("steward.npcs.cut-width")}
              </p>
            ) : null}
          </div>
        )
      })}
      <div className="flex items-center gap-3">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={full}
          onClick={() => edit((draft) => addAnswer(draft, step.id))}
        >
          <PlusIcon aria-hidden />
          {t("steward.npcs.add-answer")}
        </Button>
        <span className="text-xs text-muted-foreground tnum">
          {t("steward.npcs.answer-count", { count: step.answers.length, most: MOST_ANSWERS })}
        </span>
      </div>
    </section>
  )
}

export function StepPanel({
  npc,
  step,
  menus,
  measured,
  edit,
  onDeleted,
}: {
  npc: Npc
  step: NpcStep
  menus: NpcMenu[]
  measured: Measured
  edit: Edit
  /** Called after the step is deleted, so the page picks another. */
  onDeleted: () => void
}) {
  const start = npc.dialogue.start === step.id
  const only = npc.dialogue.steps.length <= 1
  return (
    <>
      <section className="flex flex-col gap-3">
        <Heading
          actions={
            <>
              {start ? null : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => edit((draft) => setStart(draft, step.id))}
                >
                  {t("steward.npcs.start-here")}
                </Button>
              )}
              {only ? null : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    edit((draft) => deleteStep(draft, step.id))
                    onDeleted()
                  }}
                >
                  <TrashIcon aria-hidden />
                  {t("steward.npcs.delete-step")}
                </Button>
              )}
            </>
          }
        >
          {t("steward.npcs.step")}
        </Heading>
        {LANGUAGES.map((language) => (
          <StepText key={language} step={step} language={language} measured={measured} edit={edit} />
        ))}
        {step.answers.length > 0 ? null : (
          <div className="flex flex-col gap-1.5">
            <span className="text-xs text-muted-foreground">{t("steward.npcs.then")}</span>
            <ThenPicker
              label={t("steward.npcs.then")}
              then={thenOf(step)}
              npc={npc}
              menus={menus}
              except={step.id}
              onThen={(then) => edit((draft) => setThen(draft, step.id, then))}
              onNew={() =>
                edit((draft) => {
                  const added = addStep(draft, step.id, "below")
                  return setThen(added.npc, step.id, { to: "step", step: added.id })
                })
              }
            />
          </div>
        )}
      </section>
      <Answers npc={npc} step={step} measured={measured} edit={edit} />
    </>
  )
}
