import { useCallback, useEffect, useMemo, useState } from "react"
import { useNavigate, useSearch } from "@tanstack/react-router"

import type { DocumentList, MessageTexts } from "@/lib/api"
import { DOCUMENT_LEAF } from "@/lib/settings-tree"
import { useDocuments, useMessageTexts } from "@/lib/queries"
import { onPendingTextJump, takePendingTextJump } from "@/lib/settings-search"
import { serviceTitle } from "@/lib/words"
import { t } from "@/lib/texts"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import type { Target } from "@/components/steward/settings-view"
import { TEXTS_FILE } from "@/components/steward/texts"
import { TranslationsView, type TranslationsState } from "@/components/steward/translations-view"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

export type TranslationsSearch = { service?: string } & TranslationsState

/** Keeps the service filter, the area listed and the open document or text, each only when it is a word. */
export function translationsSearch(search: Record<string, unknown>): TranslationsSearch {
  return Object.fromEntries(
    (["service", "area", "document", "text"] as const).flatMap((name) => {
      const value = search[name]
      return typeof value === "string" && value !== "" ? [[name, value]] : []
    }),
  )
}

/** The first choice of the filter. A service is named in lowercase, so none is called this. */
const EVERY_SERVICE = "ALL"

/** A counter and not state, because the number only has to differ from the last one. */
let jumps = 0

export function TranslationsPage() {
  const search = useSearch({ from: "/translations" })
  const navigate = useNavigate({ from: "/translations" })
  const { service, ...state } = search
  const onState = useCallback(
    (next: TranslationsState) => void navigate({ search: (kept) => ({ ...kept, ...next }) }),
    [navigate],
  )
  return (
    <Translations
      service={service}
      onService={(next) => void navigate({ search: (kept) => ({ ...kept, service: next }), replace: true })}
      state={state}
      onState={onState}
    />
  )
}

/**
 * Every Discord document and every text of every bundle, listed by area and section. A text or document that the
 * command palette found opens in the language it was found in.
 */
export function Translations({
  service,
  onService,
  state,
  onState,
}: {
  service: string | undefined
  onService: (service: string | undefined) => void
  state: TranslationsState
  onState: (next: TranslationsState) => void
}) {
  const texts = useMessageTexts()
  const documents = useDocuments()
  const [target, setTarget] = useState<Target | null>(null)

  /**
   * A jump from the command palette. Each gets a new number, so the same hit twice lands twice.
   */
  useEffect(() => {
    const land = () => {
      const jump = takePendingTextJump()
      if (!jump) return
      const id = jump.id.startsWith(DOCUMENT_LEAF) ? jump.id.slice(DOCUMENT_LEAF.length) : jump.id
      setTarget({ file: TEXTS_FILE, id, language: jump.language, seq: ++jumps })
    }
    land()
    return onPendingTextJump(land)
  }, [])

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.shell.page", { page: "translations" })} />
      <QueryState query={texts} rows={8}>
        {(read) => (
          <TranslationsView
            texts={read}
            documents={documents.data}
            service={service}
            serviceFilter={
              <ServiceFilter texts={read} documents={documents.data} service={service} onService={onService} />
            }
            state={state}
            onState={onState}
            target={target}
          />
        )}
      </QueryState>
    </div>
  )
}

function ServiceFilter({
  texts,
  documents,
  service,
  onService,
}: {
  texts: MessageTexts | undefined
  documents: DocumentList | undefined
  service: string | undefined
  onService: (service: string | undefined) => void
}) {
  const services = useMemo(
    () =>
      [
        ...new Set([...(texts?.texts ?? []), ...(documents?.documents ?? [])].flatMap((shown) => shown.services)),
      ].toSorted(),
    [texts, documents],
  )
  return (
    <Select
      value={service ?? EVERY_SERVICE}
      onValueChange={(value) => onService(value === EVERY_SERVICE ? undefined : value)}
    >
      <SelectTrigger aria-label={t("steward.texts.service")} className="min-w-0 flex-1">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={EVERY_SERVICE}>{t("steward.texts.all-services")}</SelectItem>
        {services.map((name) => (
          <SelectItem key={name} value={name}>
            {serviceTitle(name)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
