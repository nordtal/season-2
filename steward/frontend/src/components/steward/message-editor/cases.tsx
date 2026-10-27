import { useMemo, useState } from "react"
import { PlusIcon, XIcon } from "@phosphor-icons/react"

import type { GlyphInfo, MessageArg } from "@/lib/api"
import { choiceOf, choiceSource, inside, type Choice, type Format, type Run, type Style } from "@/lib/rich-text"
import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { InputGroup } from "@/components/ui/input-group"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import type { Fill } from "@/components/steward/message-editor/examples"
import { PlaceholderChip } from "@/components/steward/message-editor/parts"
import type { Tones } from "@/components/steward/message-editor/preview"
import { VisualEditor } from "@/components/steward/message-editor/visual-editor"

export type CaseContext = {
  format: Format
  args: MessageArg[]
  glyphs: GlyphInfo[]
  fill: Fill
  tones: Tones
  kinds: Record<string, string[]>
  label: string
  language: string
  around?: Style
  disabled?: boolean
}

const CASE_KEY = { plural: /^=\d+$/, select: /^[A-Za-z0-9_-]+$/ }

/** `outer` names the plural that a `#` stands for around these runs. */
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

  const [picked, setPicked] = useState(keys[0] ?? "other")
  const at = Math.max(0, keys.indexOf(picked))
  const [name, runs] = choice.cases[at] ?? ["other", []]
  // One case at a time: a tab per case, named with the numbers it stands for, and the open case's text under them.
  return (
    <section aria-label={`${heading} ${choice.name}`} className="flex min-w-0 flex-col gap-2">
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        {heading}
        <PlaceholderChip name={choice.name} className="text-xs" />
      </div>
      <div className="flex min-w-0 flex-wrap items-center gap-1">
        <Tabs value={name} onValueChange={setPicked} className="min-w-0">
          <TabsList className="h-auto flex-wrap justify-start p-0.5">
            {keys.map((key) => (
              <TabsTrigger key={key} value={key} className="flex-none gap-1.5 px-2 text-xs">
                <CaseName name={key} numbers={numbersOf(key, choice.plural, context.language)} />
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        {context.disabled ? null : (
          <AddCase
            plural={choice.plural}
            needed={needed}
            taken={keys}
            language={context.language}
            onAdd={(key) => {
              add(key)
              setPicked(key)
            }}
          />
        )}
      </div>
      <div key={name} className="flex min-w-0 flex-col gap-3">
        <InputGroup className="h-auto flex-col items-stretch">
          <VisualEditor
            {...context}
            runs={runs}
            onChange={(next) => setCase(at, next)}
            label={`${context.label} ${name}`}
            pound={inner ?? undefined}
          />
        </InputGroup>
        <ChoiceFields {...context} runs={runs} onChange={(next) => setCase(at, next)} outer={inner} />
      </div>
      {name !== "other" && !context.disabled ? (
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="self-end"
          onClick={() => onChange({ ...choice, cases: choice.cases.toSpliced(at, 1) })}
        >
          <XIcon aria-hidden />
          {t("steward.message-editor.remove-case")}
        </Button>
      ) : null}
    </section>
  )
}

/** The first whole numbers a plural case of `language` stands for; none for an exact number or a value. */
function numbersOf(key: string, plural: boolean, language: string): string {
  if (!plural || key.startsWith("=")) return ""
  const rules = new Intl.PluralRules(language)
  const found: number[] = []
  for (let n = 0; n <= 200 && found.length < 3; n++) if (rules.select(n) === key) found.push(n)
  return found.join(", ")
}

function CaseName({ name, numbers }: { name: string; numbers: string }) {
  return (
    <>
      <span translate="no" className="font-mono">
        {name}
      </span>
      {numbers ? <span className="text-muted-foreground tabular-nums">{numbers}</span> : null}
    </>
  )
}

/** The cases the language still lacks, and an exact number or a value of one's own. */
function AddCase({
  plural,
  needed,
  taken,
  language,
  onAdd,
}: {
  plural: boolean
  needed: string[]
  taken: string[]
  language: string
  onAdd: (key: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [key, setKey] = useState("")
  const typed = plural && /^\d+$/.test(key.trim()) ? `=${key.trim()}` : key.trim()
  const valid = (plural ? CASE_KEY.plural : CASE_KEY.select).test(typed) && !taken.includes(typed)
  const pick = (chosen: string) => {
    onAdd(chosen)
    setKey("")
    setOpen(false)
  }
  const label = t("steward.message-editor.add-case")
  const field = plural ? t("steward.message-editor.exact-number") : t("steward.message-editor.new-case")
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={label} title={label}>
          <PlusIcon aria-hidden />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={16} className="flex w-64 flex-col gap-1 p-2">
        {needed.map((category) => (
          <Button
            key={category}
            type="button"
            variant="ghost"
            size="sm"
            className="justify-start gap-2"
            onClick={() => pick(category)}
          >
            <CaseName name={category} numbers={numbersOf(category, plural, language)} />
          </Button>
        ))}
        <form
          className="flex items-center gap-1"
          onSubmit={(event) => {
            event.preventDefault()
            if (valid) pick(typed)
          }}
        >
          <Input
            value={key}
            onChange={(event) => setKey(event.target.value)}
            name="case"
            inputMode={plural ? "numeric" : undefined}
            aria-label={field}
            placeholder={field}
            autoComplete="off"
            spellCheck={false}
            className="h-8 min-w-0 flex-1 font-mono text-sm max-md:text-base"
          />
          <Button type="submit" variant="ghost" size="icon-sm" aria-label={label} title={label} disabled={!valid}>
            <PlusIcon aria-hidden />
          </Button>
        </form>
      </PopoverContent>
    </Popover>
  )
}
