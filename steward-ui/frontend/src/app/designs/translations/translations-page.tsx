import { useEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"
import { useNavigate, useSearch } from "@tanstack/react-router"
import { CaretUpDownIcon } from "@phosphor-icons/react"

import type { MessageArg, MessageBundle, MessageEntry } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { overrideOf, packagedOf, unknownPlaceholders } from "@/lib/message-text"
import type { Language } from "@/lib/message-text"
import { useGlyphs, useMessageBundle, useMessageBundles, useMessageExamples, useSaveMessageBundle } from "@/lib/queries"
import { formatOf, normalize, parse, same, serialize } from "@/lib/rich-text"
import type { Format, Run } from "@/lib/rich-text"
import { QueryState } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Command, CommandEmpty, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { exampleOf } from "@/app/designs/translations/examples"
import { Preview } from "@/app/designs/translations/preview"
import { SegmentEditor } from "@/app/designs/translations/segment-editor"
import { SourceEditor } from "@/app/designs/translations/source-editor"
import { VisualEditor } from "@/app/designs/translations/visual-editor"

/**
 * The three translation editors, on real bundles, saving through the same API the service page
 * uses: A types into the text as it looks, B edits the source with its syntax coloured, C builds
 * the text piece by piece. All three edit one text, so switching between them mid-edit loses
 * nothing - which is also the fairest way to compare them. Deleted with the rest of
 * `app/designs/` once one is picked.
 */

type Editor = "visual" | "source" | "segments"

export type TranslationsSearch = { bundle?: string; key?: string; editor?: Editor; lang?: Language }

export function translationsSearch(search: Record<string, unknown>): TranslationsSearch {
  const answer: TranslationsSearch = {}
  if (typeof search.bundle === "string" && search.bundle) answer.bundle = search.bundle
  if (typeof search.key === "string" && search.key) answer.key = search.key
  if (search.editor === "visual" || search.editor === "source" || search.editor === "segments") answer.editor = search.editor
  if (search.lang === "en" || search.lang === "de") answer.lang = search.lang
  return answer
}

const DEFAULT_BUNDLE = "proxy/proxy"
const DEFAULT_KEY = "info.discord"

const EDITORS: { value: Editor; mark: string; name: string }[] = [
  { value: "visual", mark: "A", name: "As it looks" },
  { value: "source", mark: "B", name: "Source" },
  { value: "segments", mark: "C", name: "Pieces" },
]

export function TranslationsPage() {
  const search = useSearch({ from: "/designs/translations" })
  const navigate = useNavigate({ from: "/designs/translations" })
  const go = (patch: TranslationsSearch) => navigate({ search: (old) => ({ ...old, ...patch }), replace: true })

  const bundles = useMessageBundles()
  const path = search.bundle ?? DEFAULT_BUNDLE
  const bundle = useMessageBundle(path)
  const editor = search.editor ?? "visual"
  const language = search.lang ?? "en"

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <h1 className="text-2xl font-semibold tracking-tight">Translations</h1>
      <div className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
        <Select value={path} onValueChange={(value) => go({ bundle: value, key: undefined })}>
          <SelectTrigger aria-label="Bundle" className="w-full min-w-0">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(bundles.data ?? [{ path }]).map((location) => (
              <SelectItem key={location.path} value={location.path}>
                {location.path}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <KeyPicker entries={bundle.data?.entries ?? []} value={search.key ?? DEFAULT_KEY} onPick={(key) => go({ key })} />
      </div>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <Tabs value={editor} onValueChange={(value) => go({ editor: value as Editor })}>
          <TabsList>
            {EDITORS.map((option) => (
              <TabsTrigger key={option.value} value={option.value} className="gap-1.5">
                <span className="font-semibold">{option.mark}</span>
                <span className="text-muted-foreground">{option.name}</span>
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        <Tabs value={language} onValueChange={(value) => go({ lang: value as Language })}>
          <TabsList>
            <TabsTrigger value="en">EN</TabsTrigger>
            <TabsTrigger value="de">DE</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>
      <QueryState query={bundle} rows={6}>
        {(data) => {
          const entry = data.entries.find((candidate) => candidate.key === (search.key ?? DEFAULT_KEY)) ?? data.entries[0]
          if (!entry) return null
          return <Workbench key={`${data.path} ${entry.key} ${language}`} bundle={data} entry={entry} language={language} editor={editor} />
        }}
      </QueryState>
    </div>
  )
}

function KeyPicker({ entries, value, onPick }: { entries: MessageEntry[]; value: string; onPick: (key: string) => void }) {
  const [open, setOpen] = useState(false)
  const current = entries.find((entry) => entry.key === value)
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" aria-label="Message" className="w-full min-w-0 justify-between">
          <span className="truncate">{current?.name ?? value}</span>
          <CaretUpDownIcon aria-hidden className="shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[min(28rem,calc(100vw-2rem))] p-0">
        <Command>
          <CommandInput placeholder="Search" />
          <CommandList>
            <CommandEmpty>Nothing found</CommandEmpty>
            {entries.map((entry) => (
              <CommandItem
                key={entry.key}
                value={`${entry.key} ${entry.name ?? ""} ${entry.english ?? ""}`}
                onSelect={() => {
                  onPick(entry.key)
                  setOpen(false)
                }}
                className="flex min-w-0 flex-col items-start gap-0"
              >
                <span className="w-full truncate">{entry.name ?? entry.key}</span>
                <span className="w-full truncate font-mono text-xs text-muted-foreground">{entry.key}</span>
              </CommandItem>
            ))}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

function Workbench({ bundle, entry, language, editor }: { bundle: MessageBundle; entry: MessageEntry; language: Language; editor: Editor }) {
  const packaged = packagedOf(entry, language) ?? ""
  const stored = overrideOf(entry, language) ?? packaged
  const [text, setText] = useState(stored)
  const format = formatOf(entry.format)
  const args = entry.args
  const examples = useMessageExamples().data
  const glyphs = useGlyphs().data ?? []
  const save = useSaveMessageBundle(bundle.path)
  const fill = useMemo(() => (name: string) => exampleOf(name, args, examples), [args, examples])
  const [runs, setRuns] = useRuns(text, format, args, stored, setText)
  const previewRuns = useMemo(() => parse(text, format, args), [text, format, args])

  const unknown = unknownPlaceholders(entry, text)
  const changed = text !== stored
  const label = entry.name ?? entry.key

  function write(value: string | null) {
    save.mutate(
      { changes: { [entry.key]: { [language]: value } } },
      { onSuccess: (saved) => announceSave(label, saved.reload, bundle.path) },
    )
  }

  const common = { format, args, glyphs, fill, disabled: !bundle.writable }
  let body: ReactNode
  if (editor === "visual") body = <VisualEditor {...common} runs={runs} onChange={setRuns} label={label} />
  else if (editor === "source") body = <SourceEditor {...common} text={text} onChange={setText} label={label} />
  else body = <SegmentEditor {...common} runs={runs} onChange={setRuns} />

  return (
    <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
      <section className="flex min-w-0 flex-col gap-3">
        {body}
        {unknown.length > 0 ? <p className="text-sm text-destructive">{unknown.join(" ")}</p> : null}
        <div className="flex flex-wrap gap-2">
          <Button type="button" disabled={!changed || unknown.length > 0 || save.isPending || !bundle.writable} onClick={() => write(text === packaged ? null : text)}>
            Save
          </Button>
          <Button type="button" variant="outline" disabled={!changed} onClick={() => setText(stored)}>
            Undo
          </Button>
          {overrideOf(entry, language) !== undefined ? (
            <Button
              type="button"
              variant="ghost"
              disabled={save.isPending || !bundle.writable}
              onClick={() => {
                setText(packaged)
                write(null)
              }}
            >
              Reset
            </Button>
          ) : null}
        </div>
      </section>
      <section className="flex min-w-0 flex-col gap-2">
        <Preview runs={previewRuns} format={format} shown={entry.shown} keyName={entry.key} fill={fill} glyphs={glyphs} />
        <pre className="min-w-0 rounded-md bg-muted/50 px-2.5 py-2 font-mono text-xs whitespace-pre-wrap text-muted-foreground [overflow-wrap:anywhere]">{text}</pre>
      </section>
    </div>
  )
}

/**
 * The runs an editor edits, kept next to the text rather than re-read from it on every key: the
 * segment list must not merge rows under the author's finger, and the caret mapping must see the
 * runs it drew. They are re-read only when the text changed from outside - another editor, Undo.
 * A text whose runs read the same as what was stored is written back as it was stored, so opening
 * and closing a text in the visual editor never rewrites how its tags are nested.
 */
function useRuns(text: string, format: Format, args: MessageArg[], stored: string, setText: (text: string) => void): [Run[], (runs: Run[]) => void] {
  const [runs, setLocal] = useState<Run[]>(() => parse(text, format, args))
  const written = useRef(text)
  const original = useMemo(() => parse(stored, format, args), [stored, format, args])

  useEffect(() => {
    if (text !== written.current) {
      written.current = text
      setLocal(parse(text, format, args))
    }
  }, [text, format, args])

  const update = (next: Run[]) => {
    setLocal(next)
    const value = same(normalize(next), original) ? stored : serialize(next, format, args)
    written.current = value
    setText(value)
  }
  return [runs, update]
}
