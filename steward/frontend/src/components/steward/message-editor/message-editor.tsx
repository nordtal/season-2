import { useMemo, useState } from "react"
import { createPortal } from "react-dom"
import { ArrowCounterClockwiseIcon, EraserIcon, MinusIcon, PaperPlaneTiltIcon, PlusIcon } from "@phosphor-icons/react"
import { cn } from "@/lib/utils"
import { useDebounced } from "@/lib/use-debounced"

import type { MessageEntry, MessageFallback, MessagePreviewTarget } from "@/lib/api"
import { previewPlacesOf, type Places } from "@/lib/text-places"
import { overrideOf, packagedOf, type Language } from "@/lib/message-text"
import { useGameAction, useGlyphs, useMessageCheck, useMessageExamples, useMessageSyntax } from "@/lib/queries"
import { branchesOf, formatOf, longestLength, parse } from "@/lib/rich-text"
import type { Format, Run } from "@/lib/rich-text"
import type { GlyphInfo } from "@/lib/api"
import { choice, message, t } from "@/lib/texts"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { InputGroupButton } from "@/components/ui/input-group"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { RequestIcon } from "@/components/steward/game-actions"
import { fillOf, sentOf, type Fill, useWords, valuesOf } from "@/components/steward/message-editor/examples"
import { Preview, type Tones } from "@/components/steward/message-editor/preview"
import { Failure } from "@/components/steward/query-state"
import { RichField } from "@/components/steward/message-editor/rich-field"
import { LIT, useLanding, type Highlight } from "@/components/steward/settings-view"

/**
 * One key's draft: per language its new variants, `null` for "back to the jar", and absent for no change.
 */
export type MessageDraft = Record<Language, string[] | null>

const CHECK_DELAY = 300

export type MessageEditorProps = {
  entry: MessageEntry
  path: string
  service?: string
  colours: Record<string, string>
  places: Places
  draft: MessageDraft | undefined
  highlight: Highlight | null
  /** `undefined` drops this language's draft. `null` asks for the packaged texts back. */
  onChange: (language: Language, value: string[] | null | undefined) => void
  fallbacks: MessageFallback[]
  onTakeOver: (language: Language, texts: string[]) => void
  takingOver: boolean
  previews?: Record<string, MessagePreviewTarget>
  /** The language shown and edited, which the page holds. */
  language: Language
  /** Where the text is drawn in each of its places, each with a button that sends it: the card's preview column. */
  previewsIn: HTMLElement | null
}

/**
 * A shows the text as it looks and B as it is written. A fallen-back override comes first, with the text it was written over.
 */
export function MessageEditor({
  entry,
  path,
  service,
  colours,
  places,
  draft,
  highlight,
  onChange,
  fallbacks,
  onTakeOver,
  takingOver,
  previews,
  language,
  previewsIn,
}: MessageEditorProps) {
  const [variant, setVariant] = useState(0)
  // Another language starts on its first variant.
  const [variantOf, setVariantOf] = useState(language)
  if (variantOf !== language) {
    setVariantOf(language)
    setVariant(0)
  }
  const ref = useLanding(highlight)
  const syntax = useMessageSyntax()
  const glyphs = useGlyphs()
  const examples = useMessageExamples()
  const words = useWords()
  const tones = useMemo(
    () => (Object.keys(colours).length > 0 ? colours : (syntax.data?.tones ?? {})),
    [colours, syntax.data],
  )
  const toneTags = useMemo(() => Object.keys(tones), [tones])
  const kinds = syntax.data?.kinds ?? {}
  const format = formatOf(entry.format)
  // A nested message shows as its words here. The server renders the key itself, so the preview sends the key.
  const fill = fillOf(entry.args, examples.data, words(language, entry.bundle))
  const sent = sentOf(entry.args, examples.data)

  const typed = draft?.[language]
  const packaged = packagedOf(entry, language)
  const override = overrideOf(entry, language)
  const stored = override ?? packaged
  const variants = typed === undefined ? stored : (typed ?? packaged)
  const at = Math.min(variant, Math.max(variants.length - 1, 0))
  const text = variants[at] ?? ""
  const write = (next: string[]) => onChange(language, next)
  const writeText = (value: string) => write(variants.length === 0 ? [value] : variants.with(at, value))

  const runs = useMemo(() => parse(text, format, toneTags), [text, format, toneTags])
  const drawn =
    runs === null
      ? null
      : branchesOf(runs, format, toneTags, valuesOf(entry.args, examples.data), words(language, entry.bundle))

  const checked = useDebounced(typed === undefined ? null : text, CHECK_DELAY)
  const check = useMessageCheck(path, entry.key, checked)
  const problems = typed === undefined ? [] : (check.data ?? [])
  const shownAt = previewPlacesOf(entry)

  const fallback = fallbacks.find((candidate) => candidate.language === language)
  const length = runs ? longestLength(runs, format, toneTags, fill) : text.length
  const label = entry.name ?? entry.key

  const common = { format, args: entry.args, fill, tones, label }

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
          busy={takingOver}
          onTakeOver={() => onTakeOver(language, fallback.override)}
        />
      ) : null}
      <RichField
        text={text}
        stored={stored[at] ?? ""}
        onText={writeText}
        {...common}
        kinds={kinds}
        language={language}
        revision={`${language}/${at}`}
        invalid={problems.some((problem) => problem.error)}
        start={
          <>
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
        }
        end={
          <>
            {entry.limit > 0 ? (
              <span
                className={cn(
                  "text-xs tabular-nums",
                  length > entry.limit ? "text-destructive" : "text-muted-foreground",
                )}
              >
                {length}/{entry.limit}
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
            {override !== undefined && typed !== null ? (
              <InputGroupButton
                size="icon-xs"
                aria-label={t("steward.settings.reset-to-packaged")}
                title={t("steward.settings.reset-to-packaged")}
                onClick={() => onChange(language, null)}
              >
                <EraserIcon aria-hidden />
              </InputGroupButton>
            ) : null}
          </>
        }
      />
      {problems.length > 0 ? (
        <ul className="flex flex-col gap-0.5 text-xs">
          {problems.map((problem, index) => (
            <li key={index} className={problem.error ? "text-destructive" : "text-warning"}>
              {message(problem.text)}
            </li>
          ))}
        </ul>
      ) : null}
      {drawn !== null && previewsIn
        ? createPortal(
            <div className="flex flex-col gap-3">
              {shownAt.map((place) => (
                <PlacePreview
                  key={place ?? ""}
                  place={place}
                  places={places}
                  target={place === undefined ? undefined : previews?.[place]}
                  runs={drawn}
                  fill={fill}
                  glyphs={glyphs.data ?? []}
                  tones={tones}
                  blocked={text === "" || problems.some((problem) => problem.error)}
                  request={{ path, service, entry, language, text, fill: sent }}
                />
              ))}
            </div>,
            previewsIn,
          )
        : null}
      {entry.description ? <p className="text-xs text-muted-foreground">{entry.description}</p> : null}
    </div>
  )
}

function FallbackPanel({
  fallback,
  format,
  busy,
  onTakeOver,
}: {
  fallback: MessageFallback
  format: Format
  busy: boolean
  onTakeOver: () => void
}) {
  const rows: [string, string[] | undefined][] = [
    [t("steward.message-editor.packaged-then"), fallback.original],
    [t("steward.message-editor.packaged-now"), fallback.packaged],
    [t("steward.message-editor.override"), fallback.override],
  ]
  return (
    <Alert variant="warning" role="status">
      <TERM_ICON.warning aria-hidden />
      <AlertTitle>{t("steward.message-editor.fallen-back")}</AlertTitle>
      <AlertDescription className="flex min-w-0 flex-col items-start gap-2 text-foreground">
        <dl className="grid w-full grid-cols-1 gap-x-3 gap-y-1 text-xs md:grid-cols-[auto_1fr]">
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
        {fallback.reason === "STALE" ? (
          <Button type="button" size="sm" disabled={busy} onClick={onTakeOver}>
            {t("steward.message-editor.take-over")}
          </Button>
        ) : null}
      </AlertDescription>
    </Alert>
  )
}

/**
 * Kept apart from the text, so a run boundary made by A survives until the text changes from elsewhere.
 */
function PlacePreview({
  place,
  places,
  target,
  runs,
  fill,
  glyphs,
  tones,
  blocked,
  request,
}: {
  place: string | undefined
  places: Places
  target: MessagePreviewTarget | undefined
  runs: Run[]
  fill: Fill
  glyphs: GlyphInfo[]
  tones: Tones
  blocked: boolean
  request: PreviewRequest
}) {
  const send = usePreview(request, place)
  const label = place === undefined ? undefined : t("steward.message-editor.place", { place: choice(place) })
  const sendLabel =
    target === "GAME" ? t("steward.message-editor.show-in-game") : t("steward.message-editor.send-in-discord")
  return (
    <figure aria-label={label} className="flex min-w-0 flex-col gap-1">
      {label ? (
        <figcaption className="flex min-h-6 items-center gap-2 text-xs text-muted-foreground">
          <span className="min-w-0 flex-1 truncate">{label}</span>
          {target ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              aria-label={sendLabel}
              title={sendLabel}
              aria-invalid={send.isError || undefined}
              disabled={send.isPending || blocked}
              onClick={send.send}
            >
              <RequestIcon request={send} icon={PaperPlaneTiltIcon} />
            </Button>
          ) : null}
        </figcaption>
      ) : null}
      <Preview
        runs={runs}
        shown={place}
        surface={place === undefined ? undefined : places[place]}
        fill={fill}
        glyphs={glyphs}
        tones={tones}
      />
      {send.error ? <Failure error={send.error} write /> : null}
    </figure>
  )
}

type PreviewRequest = {
  path: string
  service: string | undefined
  entry: MessageEntry
  language: Language
  text: string
  fill: Fill
}

/** A value that every message has belongs to the receiving process. */
function usePreview({ path, service, entry, language, text, fill }: PreviewRequest, shown: string | undefined) {
  const action = useGameAction()
  const values = Object.fromEntries(
    entry.args.filter((arg) => !arg.action && !arg.global).map((arg) => [arg.name, fill(arg.name)]),
  )
  const body = { bundle: path, key: entry.key, language, text, shown, values }
  return {
    ...action,
    send: () =>
      action.mutate({ path: "/api/message-preview", body: service === undefined ? body : { ...body, service } }),
  }
}
