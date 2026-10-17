import { useMemo, useState } from "react"
import { PlusIcon, XIcon } from "@phosphor-icons/react"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { choiceOf, choiceSource, inside, type Choice, type Format, type Run, type Style } from "@/lib/rich-text"
import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { InputGroup } from "@/components/ui/input-group"
import type { Fill } from "@/components/steward/message-editor/examples"
import { PlaceholderChip } from "@/components/steward/message-editor/parts"
import type { Tones } from "@/components/steward/message-editor/preview"
import { VisualEditor } from "@/components/steward/message-editor/visual-editor"

/** What every case field is drawn with, as the field around the choice is. */
export type CaseContext = {
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  tones: Tones
  kinds: Record<string, string[]>
  label: string
  /** The language edited, whose plural categories a plural offers. */
  language: string
  /** The style the runs sit in, which each case field draws its own text inside. */
  around?: Style
  disabled?: boolean
}

/** A key a case may have: a plural's `=n`, or a choice's value. */
const CASE_KEY = { plural: /^=\d+$/, select: /^[A-Za-z0-9_-]+$/ }

/**
 * Every plural and choice of `runs`, each case in a field of its own, a choice inside a case below its field.
 *
 * `outer` names the plural a `#` stands for around these runs, for the choices inside a plural's case.
 */
export function ChoiceFields({
  runs,
  onChange,
  outer = null,
  ...context
}: CaseContext & { runs: Run[]; onChange: (runs: Run[]) => void; outer?: string | null }) {
  const tags = useMemo(() => Object.keys(context.tones), [context.tones])
  const choices = runs.flatMap((run, index) => {
    const choice = choiceOf(run, context.format, tags, outer)
    return choice ? [{ index, choice }] : []
  })
  if (choices.length === 0) return null
  return (
    <div className="flex flex-col gap-4">
      {choices.map(({ index, choice }) => (
        <ChoiceCases
          key={index}
          {...context}
          around={inside(context.around ?? {}, runs[index].style)}
          choice={choice}
          outer={outer}
          onChange={(next) =>
            onChange(
              runs.with(index, {
                kind: "raw",
                source: choiceSource(next, context.format, outer),
                style: runs[index].style,
              }),
            )
          }
        />
      ))}
    </div>
  )
}

/** One plural or choice: the value it depends on, a field per case, and the cases the language still needs. */
function ChoiceCases({
  choice,
  outer,
  onChange,
  ...context
}: CaseContext & { choice: Choice; outer: string | null; onChange: (choice: Choice) => void }) {
  const inner = choice.plural ? choice.name : outer
  const keys = choice.cases.map(([key]) => key)
  const needed = choice.plural
    ? new Intl.PluralRules(context.language).resolvedOptions().pluralCategories.filter((key) => !keys.includes(key))
    : []
  const heading = t(choice.plural ? "steward.message-editor.by-number" : "steward.message-editor.by-value")
  const setCase = (at: number, runs: Run[]) =>
    onChange({ ...choice, cases: choice.cases.with(at, [choice.cases[at][0], runs]) })
  const add = (key: string) => {
    const other = choice.cases.find(([name]) => name === "other")?.[1] ?? []
    const at = keys.includes("other") ? keys.indexOf("other") : keys.length
    onChange({ ...choice, cases: choice.cases.toSpliced(at, 0, [key, other]) })
  }

  return (
    <section aria-label={`${heading} ${choice.name}`} className="flex min-w-0 flex-col gap-2">
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        {heading}
        <PlaceholderChip name={choice.name} className="text-xs" />
      </div>
      {choice.cases.map(([key, runs], at) => (
        <div key={key} className="flex min-w-0 flex-col gap-1 md:grid md:grid-cols-[7rem_minmax(0,1fr)] md:gap-3">
          <div className="flex min-h-6 items-center gap-1 md:h-9 md:self-start">
            <span translate="no" className="min-w-0 flex-1 truncate font-mono text-sm">
              {key}
            </span>
            {key !== "other" && !context.disabled ? (
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                aria-label={`${t("steward.message-editor.remove-case")} ${key}`}
                title={t("steward.message-editor.remove-case")}
                onClick={() => onChange({ ...choice, cases: choice.cases.toSpliced(at, 1) })}
              >
                <XIcon aria-hidden />
              </Button>
            ) : null}
          </div>
          <div className="flex min-w-0 flex-col gap-3">
            <InputGroup className="h-auto flex-col items-stretch">
              <VisualEditor
                {...context}
                runs={runs}
                onChange={(next) => setCase(at, next)}
                label={`${context.label} ${key}`}
                pound={inner ?? undefined}
              />
            </InputGroup>
            <ChoiceFields {...context} runs={runs} onChange={(next) => setCase(at, next)} outer={inner} />
          </div>
        </div>
      ))}
      {context.disabled ? null : <AddCase plural={choice.plural} needed={needed} taken={keys} onAdd={add} />}
    </section>
  )
}

/** The plural categories the language needs and has no case for, then any other case by its key. */
function AddCase({
  plural,
  needed,
  taken,
  onAdd,
}: {
  plural: boolean
  needed: string[]
  taken: string[]
  onAdd: (key: string) => void
}) {
  const [key, setKey] = useState("")
  const typed = key.trim()
  const valid = (plural ? CASE_KEY.plural : CASE_KEY.select).test(typed) && !taken.includes(typed)
  const submit = () => {
    if (!valid) return
    onAdd(typed)
    setKey("")
  }
  return (
    <div className="flex flex-wrap items-center gap-2 md:pl-[calc(7rem+0.75rem)]">
      {needed.map((category) => (
        <Button key={category} type="button" variant="outline" size="sm" onClick={() => onAdd(category)}>
          <PlusIcon aria-hidden />
          <span translate="no" className="font-mono">
            {category}
          </span>
        </Button>
      ))}
      <form
        className="flex items-center gap-1"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Input
          value={key}
          onChange={(event) => setKey(event.target.value)}
          name="case"
          aria-label={t("steward.message-editor.new-case")}
          placeholder={plural ? "=0" : t("steward.message-editor.new-case")}
          autoComplete="off"
          spellCheck={false}
          className="h-7 w-32 font-mono text-sm max-md:text-base"
        />
        <Button
          type="submit"
          variant="ghost"
          size="icon-sm"
          aria-label={t("steward.message-editor.add-case")}
          title={t("steward.message-editor.add-case")}
          disabled={!valid}
        >
          <PlusIcon aria-hidden />
        </Button>
      </form>
    </div>
  )
}
