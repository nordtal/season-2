import * as React from "react"
import { useNavigate } from "@tanstack/react-router"

import { CommandGroup, CommandItem, CommandSeparator } from "@/components/ui/command"
import { TERM_ICON } from "@/components/steward/term-icons"
import { argsOf } from "@/components/steward/document-fields"
import { exampleOf, fillOf, useWords } from "@/components/steward/message-editor/examples"
import { Badge } from "@/components/ui/badge"
import type { MessageArg, MessageExamples, TranslationHit } from "@/lib/api"
import { documentId } from "@/lib/documents"
import {
  useDocuments,
  useMessageExamples,
  useMessageSyntax,
  useMessageTexts,
  useTranslationSearch,
} from "@/lib/queries"
import { branchesOf, formatOf, parse, plainText, type Format } from "@/lib/rich-text"
import { setPendingTextJump } from "@/lib/settings-search"
import { DOCUMENT_LEAF, leafPaths, messageName, textId, textsTree } from "@/lib/settings-tree"
import { t } from "@/lib/texts"

const MAX_HITS = 30

/** One row: what the hit opens, the leaf it is on the Translations page and what it is shown by. */
type Row = { hit: TranslationHit; leaf: string; name: string; format: Format; args: MessageArg[]; bundle?: string }

/** Returns `source` as its examples draw it, with every plural and select as the case its example picks. */
export function renderedText(
  source: string,
  format: Format,
  tones: readonly string[],
  args: MessageArg[],
  examples: MessageExamples | undefined,
  fill: (name: string, style?: string) => string,
): string {
  const runs = parse(source, format, tones)
  if (!runs) return source
  return plainText(
    branchesOf(runs, format, tones, (name) => exampleOf(name, args, examples)),
    fill,
  )
}

/**
 * The texts and documents the server's search finds, in its order, each under its path on the Translations page with
 * the language it was found in. A hit opens the page on it in that language.
 */
export function TranslationHits({ search, open, onDone }: { search: string; open: boolean; onDone: () => void }) {
  const navigate = useNavigate()
  const typed = open && search.trim().length > 0
  const hits = useTranslationSearch(search, open).data
  const texts = useMessageTexts(typed).data
  const documents = useDocuments(typed).data
  const tones = Object.keys(useMessageSyntax().data?.tones ?? {})
  const examples = useMessageExamples().data
  const words = useWords()

  const paths = React.useMemo(
    () => leafPaths(textsTree(texts?.texts ?? [], texts?.places ?? {}, documents?.documents ?? [])),
    [texts, documents],
  )
  const rows = React.useMemo(() => {
    const entries = new Map((texts?.texts ?? []).map((text) => [textId(text.entry), text.entry]))
    const views = new Map((documents?.documents ?? []).map((view) => [documentId(view.declaration), view]))
    return (typed ? (hits ?? []) : []).slice(0, MAX_HITS).flatMap((hit): Row[] => {
      if (hit.kind === "TEXT") {
        const entry = entries.get(hit.id)
        if (!entry) return []
        const format = formatOf(entry.format)
        return [{ hit, leaf: hit.id, name: messageName(entry), format, args: entry.args, bundle: entry.bundle }]
      }
      const view = views.get(hit.id)
      if (!view) return []
      const { values, slots } = view.declaration
      const args = argsOf(Object.assign({}, values, ...slots.map((slot) => slot.values)))
      return [{ hit, leaf: `${DOCUMENT_LEAF}${hit.id}`, name: view.declaration.title, format: "MINIMESSAGE", args }]
    })
  }, [typed, hits, texts, documents])

  if (rows.length === 0) return null
  return (
    <>
      <CommandSeparator />
      <CommandGroup heading={t("steward.shell.page", { page: "translations" })}>
        {rows.map(({ hit, leaf, name, format, args, bundle }) => {
          const fill = fillOf(args, examples, words(hit.language, bundle))
          const text = renderedText(hit.text, format, tones, args, examples, fill).replace(/\s+/g, " ").trim()
          return (
            <CommandItem
              key={leaf}
              value={`translation-${leaf}`}
              onSelect={() => {
                onDone()
                setPendingTextJump({ id: leaf, language: hit.language })
                void navigate({ to: "/translations", search: {} })
              }}
              className="min-h-control items-start gap-2.5 py-1.5"
            >
              <TERM_ICON.translations aria-hidden className="mt-0.5 text-muted-foreground" />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="flex min-w-0 items-center gap-1.5">
                  <span className="min-w-0 truncate">{name}</span>
                  <Badge variant="outline" className="h-4 shrink-0 px-1.5 text-[0.625rem] text-muted-foreground">
                    {hit.language.toUpperCase()}
                  </Badge>
                </span>
                <span className="truncate text-xs text-muted-foreground">{(paths.get(leaf) ?? []).join(" › ")}</span>
                {text ? <span className="truncate text-xs text-muted-foreground/80">{text}</span> : null}
              </span>
            </CommandItem>
          )
        })}
      </CommandGroup>
    </>
  )
}
