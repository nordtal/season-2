import { useEffect, useMemo, useRef, useState } from "react"
import {
  ArrowCounterClockwiseIcon,
  CodeIcon,
  EraserIcon,
  EyeIcon,
  MinusIcon,
  PaperPlaneTiltIcon,
  PlusIcon,
} from "@phosphor-icons/react"
import { cn } from "cn"

import type { MessageEntry, MessageFallback, MessagePreviewTarget } from "@/lib/api"
import { ENGLISH, isLanguage, overrideOf, packagedOf, type Language } from "@/lib/message-text"
import {
  useCommandRun,
  useGameAction,
  useGlyphs,
  useMessageCheck,
  useMessageExamples,
  useMessageSyntax,
} from "@/lib/queries"
import { formatOf, normalize, parse, plainText, same, serialize } from "@/lib/rich-text"
import type { Format, Run } from "@/lib/rich-text"
import { message, t } from "@/lib/texts"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { InputGroup, InputGroupAddon, InputGroupButton } from "@/components/ui/input-group"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { RequestOutcome } from "@/components/steward/game-actions"
import { exampleOf, type Fill } from "@/components/steward/message-editor/examples"
import { discordLimit, Preview } from "@/components/steward/message-editor/preview"
import { Failure } from "@/components/steward/query-state"
import { SourceEditor } from "@/components/steward/message-editor/source-editor"
import { VisualEditor } from "@/components/steward/message-editor/visual-editor"
import { LIT, UNSEEN, useLanding, type Highlight } from "@/components/steward/settings-view"

/** One key's draft: per language, its new variants, `null` for "back to the jar", absent for no change. */
export type MessageDraft = Record<Language, string[] | null>

/** How long typing pauses before the validator is asked. */
const CHECK_DELAY = 300

export type MessageEditorProps = {
  entry: MessageEntry
  /** The bundle's path, as the routes name it. */
  bundle: string
  /** The bundle's languages, English first. */
  languages: Language[]
  writable: boolean
  draft: MessageDraft | undefined
  highlight: Highlight | null
  /** `undefined` drops this language's draft, `null` asks for the packaged texts back. */
  onChange: (language: Language, value: string[] | null | undefined) => void
  /** The key's overrides no process shows, one per language at most. */
  fallbacks: MessageFallback[]
  /** Saves a fallen-back override as it is, which takes it over. */
  onTakeOver: (language: Language, texts: string[]) => void
  takingOver: boolean
  /** Where a preview of the key reaches the admin, absent for a key none reaches. */
  preview?: MessagePreviewTarget
}

/**
 * One key's text in one language and variant: A as it looks or B as it is written, the validator's word on it, and
 * where it is shown with example values. A fallen-back override comes first, with what it was written over.
 */
export function MessageEditor({
  entry,
  bundle,
  languages,
  writable,
  draft,
  highlight,
  onChange,
  fallbacks,
  onTakeOver,
  takingOver,
  preview,
}: MessageEditorProps) {
  const [language, setLanguage] = useState<Language>(ENGLISH)
  const [variant, setVariant] = useState(0)
  const [source, setSource] = useState(false)
  const ref = useLanding(highlight)
  const syntax = useMessageSyntax()
  const glyphs = useGlyphs()
  const examples = useMessageExamples()
  const tones = useMemo(() => syntax.data?.tones ?? {}, [syntax.data])
  const toneTags = useMemo(() => Object.keys(tones), [tones])
  const kinds = syntax.data?.kinds ?? {}
  const format = formatOf(entry.format)
  const fill = (name: string) => exampleOf(name, entry.args, examples.data)

  const seenSeq = useRef<number | undefined | typeof UNSEEN>(UNSEEN)
  if (seenSeq.current !== highlight?.seq) {
    seenSeq.current = highlight?.seq
    if (highlight?.language) setLanguage(highlight.language)
  }

  const typed = draft?.[language]
  const packaged = packagedOf(entry, language)
  const override = overrideOf(entry, language)
  const stored = override ?? packaged
  const variants = typed === undefined ? stored : (typed ?? packaged)
  const at = Math.min(variant, Math.max(variants.length - 1, 0))
  const text = variants[at] ?? ""
  const write = (next: string[]) => onChange(language, next)
  const writeText = (value: string) => write(variants.length === 0 ? [value] : variants.with(at, value))

  const [runs, setRuns] = useRuns(text, format, toneTags, stored[at] ?? "", writeText)
  const readable = runs !== null
  const visual = readable && !source

  const checked = useDebounced(typed === undefined ? null : text, CHECK_DELAY)
  const check = useMessageCheck(bundle, entry.key, checked)
  const problems = typed === undefined ? [] : (check.data ?? [])
  const send = usePreview(bundle, entry, language, text, fill)
  const sendLabel =
    preview === "GAME" ? t("steward.message-editor.show-in-game") : t("steward.message-editor.send-in-discord")

  const overridden = (tab: Language) => {
    const own = draft?.[tab]
    return own === null ? false : own !== undefined ? true : overrideOf(entry, tab) !== undefined
  }
  const empty = (tab: Language) => packagedOf(entry, tab).length === 0 && !overridden(tab)
  const fallback = fallbacks.find((candidate) => candidate.language === language)
  const limit =
    format === "DISCORD_MARKDOWN" || entry.shown?.startsWith("DISCORD_") ? discordLimit(entry.shown, entry.key) : null
  const length = runs ? plainText(runs, fill).length : text.length
  const label = entry.name ?? entry.key

  function pickLanguage(next: string) {
    if (!isLanguage(next)) return
    setLanguage(next)
    setVariant(0)
  }

  const common = { format, args: entry.args, glyphs: glyphs.data ?? [], fill, tones, label, disabled: !writable }

  return (
    <div
      ref={ref}
      className={cn(
        "flex min-w-0 scroll-mt-4 flex-col gap-2 rounded-md transition-colors duration-300",
        highlight && LIT,
      )}
    >
      {fallback ? (
        <FallbackPanel
          fallback={fallback}
          format={format}
          writable={writable}
          busy={takingOver}
          onTakeOver={() => onTakeOver(language, fallback.override)}
        />
      ) : null}
      <InputGroup
        className={cn(
          "h-auto flex-col items-stretch",
          problems.some((problem) => problem.error) && "border-destructive",
        )}
      >
        {visual ? (
          <VisualEditor key={`${language}/${at}`} {...common} runs={runs} onChange={setRuns} kinds={kinds} />
        ) : (
          <SourceEditor {...common} text={text} onChange={writeText} />
        )}
        <InputGroupAddon align="block-end" className="flex-wrap gap-1 border-t border-input pt-1.5 pb-1.5">
          <Tabs value={language} onValueChange={pickLanguage}>
            <TabsList className="group-data-horizontal/tabs:h-7 p-0.5">
              {languages.map((tab) => (
                <TabsTrigger key={tab} value={tab} className={cn("gap-1 px-1.5 text-xs", empty(tab) && "opacity-50")}>
                  {tab.toUpperCase()}
                  {overridden(tab) ? (
                    <span aria-label={t("steward.settings.overridden")} className="size-1 rounded-full bg-primary" />
                  ) : null}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          {variants.length > 1 ? (
            <Tabs value={String(at)} onValueChange={(next) => setVariant(Number(next))}>
              <TabsList
                aria-label={t("steward.message-editor.variant")}
                className="group-data-horizontal/tabs:h-7 p-0.5"
              >
                {variants.map((_, index) => (
                  <TabsTrigger key={index} value={String(index)} className="px-2 text-xs tabular-nums">
                    {index + 1}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          ) : null}
          {writable ? (
            <>
              <InputGroupButton
                size="icon-xs"
                aria-label={t("steward.message-editor.add-variant")}
                title={t("steward.message-editor.add-variant")}
                onClick={() => {
                  write([...(variants.length === 0 ? [""] : variants), text])
                  setVariant(Math.max(variants.length, 1))
                }}
              >
                <PlusIcon aria-hidden />
              </InputGroupButton>
              {variants.length > 1 ? (
                <InputGroupButton
                  size="icon-xs"
                  aria-label={t("steward.message-editor.remove-variant")}
                  title={t("steward.message-editor.remove-variant")}
                  onClick={() => {
                    write(variants.toSpliced(at, 1))
                    setVariant(Math.max(at - 1, 0))
                  }}
                >
                  <MinusIcon aria-hidden />
                </InputGroupButton>
              ) : null}
            </>
          ) : null}
          {preview ? (
            <InputGroupButton
              size="icon-xs"
              aria-label={sendLabel}
              title={sendLabel}
              disabled={send.busy || text === "" || problems.some((problem) => problem.error)}
              onClick={send.send}
            >
              <PaperPlaneTiltIcon aria-hidden />
            </InputGroupButton>
          ) : null}
          <span className="ml-auto flex items-center gap-1">
            {limit !== null ? (
              <span
                className={cn("text-xs tabular-nums", length > limit ? "text-destructive" : "text-muted-foreground")}
              >
                {length}/{limit}
              </span>
            ) : null}
            {typed !== undefined ? (
              <InputGroupButton
                size="icon-xs"
                aria-label={t("steward.settings.undo")}
                title={t("steward.settings.undo")}
                onClick={() => onChange(language, undefined)}
              >
                <ArrowCounterClockwiseIcon aria-hidden />
              </InputGroupButton>
            ) : null}
            {override !== undefined && typed !== null && writable ? (
              <InputGroupButton
                size="icon-xs"
                aria-label={t("steward.settings.reset-to-packaged")}
                title={t("steward.settings.reset-to-packaged")}
                onClick={() => onChange(language, null)}
              >
                <EraserIcon aria-hidden />
              </InputGroupButton>
            ) : null}
            <span role="group" className="flex rounded-md bg-muted p-0.5">
              <InputGroupButton
                size="icon-xs"
                variant={visual ? "secondary" : "ghost"}
                aria-pressed={visual}
                aria-label={t("steward.message-editor.as-it-looks")}
                title={t("steward.message-editor.as-it-looks")}
                disabled={!readable}
                onClick={() => setSource(false)}
              >
                <EyeIcon aria-hidden />
              </InputGroupButton>
              <InputGroupButton
                size="icon-xs"
                variant={visual ? "ghost" : "secondary"}
                aria-pressed={!visual}
                aria-label={t("steward.message-editor.source")}
                title={t("steward.message-editor.source")}
                onClick={() => setSource(true)}
              >
                <CodeIcon aria-hidden />
              </InputGroupButton>
            </span>
          </span>
        </InputGroupAddon>
      </InputGroup>
      {problems.length > 0 ? (
        <ul className="flex flex-col gap-0.5 text-xs">
          {problems.map((problem, index) => (
            <li key={index} className={problem.error ? "text-destructive" : "text-warning"}>
              {message(problem.text)}
            </li>
          ))}
        </ul>
      ) : null}
      {send.error ? <Failure error={send.error} /> : null}
      {send.run ? <RequestOutcome run={send.run} /> : null}
      {runs !== null ? (
        <Preview
          runs={runs}
          format={format}
          shown={entry.shown}
          keyName={entry.key}
          fill={fill}
          glyphs={glyphs.data ?? []}
          tones={tones}
        />
      ) : null}
      {entry.description ? <p className="text-xs text-muted-foreground">{entry.description}</p> : null}
    </div>
  )
}

/** An override no process shows: what it was written over, what the jar has now, and the override itself. */
function FallbackPanel({
  fallback,
  format,
  writable,
  busy,
  onTakeOver,
}: {
  fallback: MessageFallback
  format: Format
  writable: boolean
  busy: boolean
  onTakeOver: () => void
}) {
  const rows: [string, string[] | undefined][] = [
    [t("steward.message-editor.packaged-then"), fallback.original],
    [t("steward.message-editor.packaged-now"), fallback.packaged],
    [t("steward.message-editor.override"), fallback.override],
  ]
  return (
    <section className="flex flex-col gap-2 rounded-lg border border-warning/40 p-3">
      <div className="flex items-center gap-2">
        <Badge variant="outline" className="border-warning/40 text-warning">
          {t("steward.message-editor.fallen-back")}
        </Badge>
        {fallback.reason === "STALE" && writable ? (
          <Button type="button" size="sm" className="ml-auto" disabled={busy} onClick={onTakeOver}>
            {t("steward.message-editor.take-over")}
          </Button>
        ) : null}
      </div>
      <dl className="grid grid-cols-1 gap-x-3 gap-y-1 text-xs md:grid-cols-[auto_1fr]">
        {rows.map(([name, texts]) =>
          texts === undefined ? null : (
            <div key={name} className="contents">
              <dt className="text-muted-foreground">{name}</dt>
              <dd className={cn("min-w-0 break-words whitespace-pre-wrap", format !== "PLAIN" && "font-mono")}>
                {texts.join("\n")}
              </dd>
            </div>
          ),
        )}
      </dl>
      {fallback.problems.length > 0 ? (
        <ul className="flex flex-col gap-0.5 text-xs text-destructive">
          {fallback.problems.map((problem, index) => (
            <li key={index}>{message(problem)}</li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}

/**
 * The runs A edits, kept apart from the text so that a run boundary A made survives until the text changes from
 * elsewhere. A change that leaves the runs as the stored text reads writes the stored text back as it was written.
 */
function useRuns(
  text: string,
  format: Format,
  tones: readonly string[],
  stored: string,
  setText: (text: string) => void,
): [Run[] | null, (runs: Run[]) => void] {
  const [runs, setLocal] = useState<Run[] | null>(() => parse(text, format, tones))
  const written = useRef(text)
  const original = useMemo(() => parse(stored, format, tones), [stored, format, tones])

  useEffect(() => {
    if (text !== written.current) {
      written.current = text
      setLocal(parse(text, format, tones))
    }
  }, [text, format, tones])

  // The tones arrive after the first render; read the text again with them.
  useEffect(() => {
    setLocal(parse(written.current, format, tones))
  }, [format, tones])

  const update = (next: Run[]) => {
    setLocal(next)
    const value = original !== null && same(normalize(next), original) ? stored : serialize(next, format)
    written.current = value
    setText(value)
  }
  return [runs, update]
}

/**
 * The text as it stands, sent to the admin alone where the key is shown, with the values the preview shows; what
 * became of the request follows. A value every message has is the receiving process's own.
 */
function usePreview(bundle: string, entry: MessageEntry, language: Language, text: string, fill: Fill) {
  const action = useGameAction()
  const [id, setId] = useState<string | null>(null)
  const run = useCommandRun(id)
  const settled = run.data !== undefined && run.data.status !== "PENDING" && run.data.status !== "RUNNING"
  const values = Object.fromEntries(
    entry.args.filter((arg) => !arg.action && !arg.global).map((arg) => [arg.name, fill(arg.name)]),
  )
  return {
    busy: action.isPending || (id !== null && !settled && !run.error),
    error: action.error ?? run.error,
    run: id === null ? undefined : run.data,
    send: () => {
      setId(null)
      action.mutate(
        { path: "/api/message-preview", body: { bundle, key: entry.key, language, text, values } },
        { onSuccess: (asked) => setId(asked.id) },
      )
    },
  }
}

function useDebounced<T>(value: T, delay: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return settled
}
