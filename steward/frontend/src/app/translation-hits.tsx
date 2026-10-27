import * as React from "react"
import { useNavigate } from "@tanstack/react-router"

import { CommandGroup, CommandItem, CommandSeparator } from "@/components/ui/command"
import { TERM_ICON } from "@/components/steward/term-icons"
import { argsOf } from "@/components/steward/document-fields"
import { useDrawLine } from "@/components/steward/message-editor/examples"
import { Badge } from "@/components/ui/badge"
import type { DocumentDeclaration, MessageArg, TranslationHit } from "@/lib/api"
import { documentId } from "@/lib/documents"
import { useDocuments, useMessageTexts, useTranslationSearch } from "@/lib/queries"
import { formatOf, type Format } from "@/lib/rich-text"
import { setPendingTextJump } from "@/lib/settings-search"
import { DOCUMENT_LEAF, leafPaths, messageName, textId, textsTree } from "@/lib/settings-tree"
import { t } from "@/lib/texts"
import { hitItemId } from "@/lib/translation-items"

const MAX_HITS = 30

/** One row: what the hit opens, the leaf it is on the Translations page and what it is shown by. */
type Row = { hit: TranslationHit; leaf: string; name: string; format: Format; args: MessageArg[]; bundle?: string }

/** Every value a document's text can name: its own and its slots'. */
function argsOfDeclaration({ values, slots }: DocumentDeclaration): MessageArg[] {
  return argsOf(Object.assign({}, values, ...slots.map((slot) => slot.values)))
}

/**
 * The texts, documents and shared parts the server's search finds, in its order, each under its path on the Translations page with
 * the language it was found in. A hit opens the page on it in that language.
 */
export function TranslationHits({ search, open, onDone }: { search: string; open: boolean; onDone: () => void }) {
  const navigate = useNavigate()
  const typed = open && search.trim().length > 0
  const hits = useTranslationSearch(search, open).data
  const texts = useMessageTexts(typed).data
  const documents = useDocuments(typed).data
  const drawLine = useDrawLine()

  const paths = React.useMemo(
    () =>
      leafPaths(
        textsTree(
          texts?.texts ?? [],
          texts?.places ?? {},
          documents?.documents ?? [],
          undefined,
          documents?.sharedParts,
        ),
      ),
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
      if (hit.kind === "SHARED_PART") {
        const shared = documents?.sharedParts.find((part) => part.name === hit.id)
        if (!shared) return []
        // The part's placeholders are the values of the documents that name it, so the first one's fill them.
        const named = views.get(shared.documents[0] ?? "")?.declaration
        return [
          {
            hit,
            leaf: hitItemId(hit),
            name: shared.name,
            format: "MINIMESSAGE",
            args: named ? argsOfDeclaration(named) : [],
          },
        ]
      }
      const view = views.get(hit.id)
      if (!view) return []
      const args = argsOfDeclaration(view.declaration)
      return [{ hit, leaf: `${DOCUMENT_LEAF}${hit.id}`, name: view.declaration.title, format: "MINIMESSAGE", args }]
    })
  }, [typed, hits, texts, documents])

  if (rows.length === 0) return null
  return (
    <>
      <CommandSeparator />
      <CommandGroup heading={t("steward.shell.page", { page: "translations" })}>
        {rows.map(({ hit, leaf, name, format, args, bundle }) => {
          const text = drawLine(hit.text, format, args, hit.language, bundle)
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
