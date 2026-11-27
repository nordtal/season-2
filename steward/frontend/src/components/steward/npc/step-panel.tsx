import {
  CaretDownIcon,
  CaretLeftIcon,
  CaretRightIcon,
  CaretUpIcon,
  PlusIcon,
  TrashIcon,
  XIcon,
} from "@/components/ui/icons"
import { useId, type KeyboardEvent, type ReactNode } from "react"

import type { Npc, NpcAnswer, NpcMenu, NpcStep, NpcTextMeasurement } from "@/lib/api"
import {
  type Language,
  type Then,
  LANGUAGES,
  LONGEST_TEXT,
  MOST_ANSWERS,
  MOST_PAGES,
  addAnswer,
  addPage,
  addStep,
  labelOf,
  missingCount,
  ordered,
  removeAnswer,
  removePage,
  setAnswerText,
  setAnswerThen,
  setStart,
  setStepPageText,
  setThen,
  stepNumbers,
  stepOf,
  thenOf,
} from "@/lib/npc"
import { measureKey } from "@/lib/queries-npcs"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import { DialoguePreview, useKept } from "@/components/steward/npc/preview"
export { useKept }
import { Hint } from "@/components/steward/status"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"

/** The inspector of the selected step: its bilingual pages, what follows, its numbered answers, and its collapsible preview. */

export type Edit = (change: (npc: Npc) => Npc, options?: { coalesce?: string }) => void

export type Measured = Map<string, NpcTextMeasurement> | undefined

/** The name of a menu as the picker lists it: its hint in English, or its key while smp has not registered it. */
export function menuName(menus: NpcMenu[], key: string): string {
  return menus.find((menu) => menu.key === key)?.hint.en.trim() || key
}

/** A heading of a section, with the actions that belong to it. */
export function Heading({ children, actions }: { children: string; actions?: ReactNode }) {
  return (
    <div className="flex min-h-6 flex-wrap items-center justify-between gap-2">
      <h2 className="text-sm font-medium font-heading text-muted-foreground">{children}</h2>
      {actions ? <div className="flex flex-wrap items-center gap-1">{actions}</div> : null}
    </div>
  )
}

/** How much of the panel's room a text takes, and the sentence that says what the panel cuts or refuses. */
export function Room({ measurement }: { measurement: NpcTextMeasurement | undefined }) {
  if (measurement === undefined) return <div className="h-1 rounded-full bg-muted" />
  const tall = measurement.lines.length > measurement.lineLimit
  const share = tall ? 1 : Math.min(1, measurement.room === 0 ? 0 : measurement.width / measurement.room)
  return (
    <>
      <div className="h-1 overflow-hidden rounded-full bg-muted">
        <div
          className={cn("h-full rounded-full", measurement.fits ? "bg-success" : "bg-warning")}
          style={{ width: `${share * 100}%` }}
        />
      </div>
      {tall ? (
        <p role="status" className="text-xs text-warning">
          {t("steward.npcs.too-tall", { limit: measurement.lineLimit })}
        </p>
      ) : measurement.fits ? null : (
        <p role="status" className="text-xs text-warning">
          {t("steward.npcs.cut-width")}
        </p>
      )}
    </>
  )
}

function StepPageText({
  step,
  pageIndex,
  language,
  measured,
  edit,
  onFocus,
  onQuickNext,
}: {
  step: NpcStep
  pageIndex: number
  language: Language
  measured: Measured
  edit: Edit
  onFocus: () => void
  onQuickNext?: () => void
}) {
  const id = useId()
  const text = step.pages[pageIndex]?.[language] ?? ""
  const answers = pageIndex === step.pages.length - 1 ? step.answers.length : 0
  const measurement = useKept(
    text === "" ? undefined : measured?.get(measureKey({ text, kind: "BODY", answers })),
    text === "" ? "" : `${step.id}:${pageIndex}:${language}:${answers}`,
  )
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter" && onQuickNext) {
      event.preventDefault()
      onQuickNext()
    }
  }
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-2">
        <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
          {t("steward.npcs.language", { language })}
        </Label>
        {measurement ? (
          <span
            className={cn(
              "text-xs tnum",
              measurement.lines.length > measurement.lineLimit ? "text-warning" : "text-muted-foreground",
            )}
          >
            {t("steward.npcs.lines", {
              lines: measurement.lines.length,
              limit: measurement.lineLimit,
            })}
          </span>
        ) : null}
      </div>
      <Textarea
        id={id}
        lang={language}
        rows={2}
        data-focus-field={`step-text:${step.id}:${pageIndex}:${language}`}
        value={text}
        maxLength={LONGEST_TEXT}
        className={cn(
          "min-h-10 flex-1 resize-none py-1 text-sm max-md:text-base",
          measurement && !measurement.fits && "border-warning",
        )}
        onFocus={onFocus}
        onKeyDown={onKeyDown}
        onChange={(event) =>
          edit((npc) => setStepPageText(npc, step.id, pageIndex, language, event.target.value), {
            coalesce: `step-text:${step.id}:${pageIndex}:${language}`,
          })
        }
      />
      <Room measurement={measurement} />
    </div>
  )
}

/** A step's name in a picker: its reading-order number plus the start of its text, or "Step N" when empty. */
export function stepOptionLabel(step: NpcStep, number: number): string {
  const words = labelOf(step)
  return words === ""
    ? t("steward.npcs.step-number", { number })
    : t("steward.npcs.step-named", { number, text: words })
}

const END = "end"
const NEW = "new"

function valueOf(then: Then): string {
  return then.to === "end" ? END : then.to === "menu" ? `menu:${then.menu}` : `step:${then.step}`
}

/**
 * What follows a text or an answer, as one picker: the end, a menu where `menus` is given, every other step in reading
 * order, and a new step.
 */
export function ThenPicker({
  label,
  then,
  npc,
  menus,
  except,
  focusField,
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
  focusField?: string
  onThen: (then: Then) => void
  onNew: () => void
  className?: string
}) {
  const numbers = stepNumbers(npc.dialogue)
  const steps = ordered(npc.dialogue)
    .map((entry) => entry.step)
    .filter((step) => step.id !== except)
  const target = then.to === "step" ? stepOf(npc.dialogue, then.step) : undefined
  const targetNumber = target ? (numbers.get(target.id) ?? 1) : 1
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
      <SelectTrigger aria-label={label} data-focus-field={focusField} className={cn("w-full min-w-0", className)}>
        <SelectValue>
          <span className="truncate">
            {then.to === "end"
              ? t("steward.npcs.end")
              : then.to === "menu"
                ? menuName(offered, then.menu)
                : target
                  ? stepOptionLabel(target, targetNumber)
                  : t("steward.npcs.end")}
          </span>
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={END}>{t("steward.npcs.then-end")}</SelectItem>
        {offered.map((menu) => (
          <SelectItem key={menu.key} value={`menu:${menu.key}`}>
            {menuName(offered, menu.key)}
          </SelectItem>
        ))}
        {steps.map((step) => {
          const number = numbers.get(step.id) ?? 1
          return (
            <SelectItem key={step.id} value={`step:${step.id}`}>
              <span className="max-w-64 truncate">
                {t("steward.npcs.then-step", { step: stepOptionLabel(step, number) })}
              </span>
            </SelectItem>
          )
        })}
        <SelectItem value={NEW}>{t("steward.npcs.then-new")}</SelectItem>
      </SelectContent>
    </Select>
  )
}

function AnswerInput({
  step,
  answer,
  index,
  language,
  wide,
  edit,
  onQuickNext,
}: {
  step: NpcStep
  answer: NpcAnswer
  index: number
  language: Language
  wide: boolean
  edit: Edit
  onQuickNext?: () => void
}) {
  const id = useId()
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter" && onQuickNext) {
      event.preventDefault()
      onQuickNext()
    }
  }
  return (
    <div className="flex flex-col gap-0.5">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        {t("steward.npcs.language", { language })}
      </Label>
      <Input
        id={id}
        lang={language}
        data-focus-field={`answer-text:${step.id}:${index}:${language}`}
        aria-label={t("steward.npcs.answer-text", { index: index + 1, language })}
        value={answer.text[language]}
        maxLength={LONGEST_TEXT}
        className={cn("h-8", wide && "border-warning")}
        onKeyDown={onKeyDown}
        onChange={(event) =>
          edit((draft) => setAnswerText(draft, step.id, index, language, event.target.value), {
            coalesce: `answer-text:${step.id}:${index}:${language}`,
          })
        }
      />
    </div>
  )
}

/** One numbered answer card: English input, German input, labelled Leads to picker, and Remove. */
function AnswerCard({
  npc,
  step,
  answer,
  index,
  measured,
  edit,
  onQuickNext,
  onCreatedStep,
}: {
  npc: Npc
  step: NpcStep
  answer: NpcAnswer
  index: number
  measured: Measured
  edit: Edit
  onQuickNext?: () => void
  onCreatedStep?: (id: string) => void
}) {
  const fitsEn =
    answer.text.en === "" ? true : measured?.get(measureKey({ text: answer.text.en, kind: "ANSWER", answers: 0 }))?.fits
  const fitsDe =
    answer.text.de === "" ? true : measured?.get(measureKey({ text: answer.text.de, kind: "ANSWER", answers: 0 }))?.fits
  const wideEn = useKept(fitsEn, `${step.id}:${index}:en`) === false
  const wideDe = useKept(fitsDe, `${step.id}:${index}:de`) === false
  return (
    <div className="flex flex-col gap-1 rounded-md bg-secondary/50 px-2 py-1.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium font-heading text-muted-foreground tnum">
          {t("steward.npcs.answer-number", { index: index + 1 })}
        </span>
        <Hint tip={t("steward.npcs.remove-answer", { index: index + 1 })}>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            className="tap-target shrink-0"
            aria-label={t("steward.npcs.remove-answer", { index: index + 1 })}
            onClick={() => edit((draft) => removeAnswer(draft, step.id, index))}
          >
            <XIcon aria-hidden />
          </Button>
        </Hint>
      </div>
      <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
        <AnswerInput
          step={step}
          answer={answer}
          index={index}
          language="en"
          wide={wideEn}
          edit={edit}
          onQuickNext={onQuickNext}
        />
        <AnswerInput
          step={step}
          answer={answer}
          index={index}
          language="de"
          wide={wideDe}
          edit={edit}
          onQuickNext={onQuickNext}
        />
      </div>
      <div className="flex items-center gap-2">
        <span className="shrink-0 text-xs text-muted-foreground">{t("steward.npcs.leads-to")}</span>
        <ThenPicker
          label={t("steward.npcs.answer-then", { index: index + 1 })}
          then={answer.next === undefined ? { to: "end" } : { to: "step", step: answer.next }}
          npc={npc}
          className="h-8"
          onThen={(then) => edit((draft) => setAnswerThen(draft, step.id, index, then))}
          onNew={() => {
            let createdId = ""
            edit((draft) => {
              const added = addStep(draft, step.id)
              createdId = added.id
              return setAnswerThen(added.npc, step.id, index, { to: "step", step: added.id })
            })
            if (createdId && onCreatedStep) onCreatedStep(createdId)
          }}
        />
      </div>
      {wideEn || wideDe ? (
        <p role="status" className="text-xs text-warning">
          {t("steward.npcs.cut-width")}
        </p>
      ) : null}
    </div>
  )
}

function Answers({
  npc,
  step,
  measured,
  edit,
  onQuickNext,
  onCreatedStep,
  onFocusField,
}: {
  npc: Npc
  step: NpcStep
  measured: Measured
  edit: Edit
  onQuickNext?: () => void
  onCreatedStep?: (id: string) => void
  onFocusField?: (selectorKey: string) => void
}) {
  const full = step.answers.length >= MOST_ANSWERS
  return (
    <section className="flex flex-col gap-1.5">
      <Heading>{t("steward.npcs.answers")}</Heading>
      {step.answers.map((answer, index) => (
        <AnswerCard
          key={index}
          npc={npc}
          step={step}
          answer={answer}
          index={index}
          measured={measured}
          edit={edit}
          onQuickNext={onQuickNext}
          onCreatedStep={onCreatedStep}
        />
      ))}
      <div className="flex items-center gap-3">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={full}
          onClick={() => {
            const newIndex = step.answers.length
            edit((draft) => addAnswer(draft, step.id))
            onFocusField?.(`answer-text:${step.id}:${newIndex}:en`)
          }}
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
  onSelectPage,
  onDeleteStep,
  onQuickNext,
  onCreatedStep,
  onFocusField,
}: {
  npc: Npc
  step: NpcStep
  menus: NpcMenu[]
  measured: Measured
  edit: Edit
  onSelectPage: (pageIndex: number) => void
  onDeleteStep: (id: string) => void
  onQuickNext?: () => void
  onCreatedStep?: (id: string) => void
  onFocusField?: (selectorKey: string) => void
}) {
  const numbers = stepNumbers(npc.dialogue)
  const number = numbers.get(step.id) ?? 1
  const start = npc.dialogue.start === step.id
  const only = npc.dialogue.steps.length <= 1
  const multiPage = step.pages.length > 1
  return (
    <div className="flex flex-col gap-1.5">
      <section className="flex flex-col gap-1.5">
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
                <Button type="button" variant="ghost" size="sm" onClick={() => onDeleteStep(step.id)}>
                  <TrashIcon aria-hidden />
                  {t("steward.npcs.delete-step")}
                </Button>
              )}
            </>
          }
        >
          {t("steward.npcs.step-number", { number })}
        </Heading>
        {step.pages.map((_, pageIndex) => (
          <div key={pageIndex} className={cn("flex flex-col gap-2.5", multiPage && "rounded-md bg-secondary/40 p-3")}>
            {multiPage ? (
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium text-muted-foreground">
                  {t("steward.npcs.page", { number: pageIndex + 1 })}
                </span>
                <Hint tip={t("steward.npcs.remove-page", { index: pageIndex + 1 })}>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-xs"
                    className="tap-target shrink-0"
                    aria-label={t("steward.npcs.remove-page", { index: pageIndex + 1 })}
                    onClick={() => edit((draft) => removePage(draft, step.id, pageIndex))}
                  >
                    <XIcon aria-hidden />
                  </Button>
                </Hint>
              </div>
            ) : null}
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {LANGUAGES.map((language) => (
                <StepPageText
                  key={language}
                  step={step}
                  pageIndex={pageIndex}
                  language={language}
                  measured={measured}
                  edit={edit}
                  onFocus={() => onSelectPage(pageIndex)}
                  onQuickNext={onQuickNext}
                />
              ))}
            </div>
          </div>
        ))}
        <div className="flex items-center gap-3">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={step.pages.length >= MOST_PAGES}
            onClick={() => {
              edit((draft) => addPage(draft, step.id))
              onSelectPage(step.pages.length)
            }}
          >
            <PlusIcon aria-hidden />
            {t("steward.npcs.add-page")}
          </Button>
          {multiPage ? (
            <span className="text-xs text-muted-foreground tnum">
              {t("steward.npcs.page-count", { count: step.pages.length })}
            </span>
          ) : null}
        </div>
        {step.answers.length > 0 ? null : (
          <div className="flex flex-col gap-1.5">
            <span className="text-xs text-muted-foreground">{t("steward.npcs.then")}</span>
            <ThenPicker
              label={t("steward.npcs.then")}
              then={thenOf(step)}
              npc={npc}
              menus={menus}
              except={step.id}
              focusField={`step-then:${step.id}`}
              onThen={(then) => edit((draft) => setThen(draft, step.id, then))}
              onNew={() => {
                let createdId = ""
                edit((draft) => {
                  const added = addStep(draft, step.id)
                  createdId = added.id
                  return setThen(added.npc, step.id, { to: "step", step: added.id })
                })
                if (createdId && onCreatedStep) onCreatedStep(createdId)
              }}
            />
          </div>
        )}
      </section>
      <Answers
        npc={npc}
        step={step}
        measured={measured}
        edit={edit}
        onQuickNext={onQuickNext}
        onCreatedStep={onCreatedStep}
        onFocusField={onFocusField}
      />
    </div>
  )
}

/** Shown in the inspector when no step is selected: dialogue counts only. */
export function InspectorEmpty({ npc }: { npc: Npc }) {
  const entries = ordered(npc.dialogue)
  const missing = entries.reduce((sum, { step }) => sum + missingCount(step), 0)
  const unlinked = entries.filter((entry) => !entry.linked).length
  return (
    <div className="flex flex-col gap-4 p-4">
      <h2 className="text-sm font-medium font-heading text-muted-foreground">{t("steward.npcs.graph")}</h2>
      <dl className="grid grid-cols-3 gap-3">
        <div className="flex flex-col gap-1 rounded-md bg-secondary/50 p-3">
          <dt className="text-xs font-heading text-muted-foreground">{t("steward.npcs.steps")}</dt>
          <dd className="font-mono text-lg tnum">{entries.length}</dd>
        </div>
        <div className="flex flex-col gap-1 rounded-md bg-secondary/50 p-3">
          <dt className="text-xs font-heading text-muted-foreground">{t("steward.npcs.summary-missing")}</dt>
          <dd className={cn("font-mono text-lg tnum", missing > 0 && "text-destructive")}>{missing}</dd>
        </div>
        <div className="flex flex-col gap-1 rounded-md bg-secondary/50 p-3">
          <dt className="text-xs font-heading text-muted-foreground">{t("steward.npcs.summary-unlinked")}</dt>
          <dd className={cn("font-mono text-lg tnum", unlinked > 0 && "text-warning")}>{unlinked}</dd>
        </div>
      </dl>
    </div>
  )
}

/** Sticky collapsible preview docked at the bottom of the inspector, following canvas language and focused page. */
export function InspectorPreview({
  npc,
  step,
  pageIndex,
  language,
  measured,
  open,
  onToggle,
  onSelectPage,
}: {
  npc: Npc
  step: NpcStep
  pageIndex: number
  language: Language
  measured: Measured
  open: boolean
  onToggle: () => void
  onSelectPage: (pageIndex: number) => void
}) {
  const Caret = open ? CaretDownIcon : CaretUpIcon
  const clampedPage = Math.min(pageIndex, step.pages.length - 1)
  return (
    <section className="shrink-0 border-t bg-popover px-3 py-1">
      <div className="flex items-center justify-between gap-2">
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="-ml-1.5 text-muted-foreground"
          aria-expanded={open}
          onClick={onToggle}
        >
          <Caret aria-hidden />
          {t("steward.npcs.preview")}
        </Button>
        {open && step.pages.length > 1 ? (
          step.pages.length > 8 ? (
            <div className="flex items-center gap-1">
              <Hint tip={t("steward.npcs.previous-page")}>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-xs"
                  disabled={clampedPage <= 0}
                  aria-label={t("steward.npcs.previous-page")}
                  onClick={() => onSelectPage(Math.max(0, clampedPage - 1))}
                >
                  <CaretLeftIcon aria-hidden />
                </Button>
              </Hint>
              <span className="min-w-10 text-center text-xs text-muted-foreground tnum">
                {t("steward.npcs.page-of", { current: clampedPage + 1, total: step.pages.length })}
              </span>
              <Hint tip={t("steward.npcs.next-page")}>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-xs"
                  disabled={clampedPage >= step.pages.length - 1}
                  aria-label={t("steward.npcs.next-page")}
                  onClick={() => onSelectPage(Math.min(step.pages.length - 1, clampedPage + 1))}
                >
                  <CaretRightIcon aria-hidden />
                </Button>
              </Hint>
            </div>
          ) : (
            <Tabs value={String(clampedPage)} onValueChange={(value) => onSelectPage(Number(value))}>
              <TabsList>
                {step.pages.map((_, index) => (
                  <TabsTrigger key={index} value={String(index)} className="px-2 text-xs tnum">
                    {index + 1}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          )
        ) : null}
      </div>
      {open ? (
        <div className="mt-1">
          <DialoguePreview npc={npc} step={step} pageIndex={pageIndex} language={language} measured={measured} />
        </div>
      ) : null}
    </section>
  )
}
