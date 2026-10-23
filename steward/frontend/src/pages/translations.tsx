import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useNavigate, useSearch } from "@tanstack/react-router"

import type { MessageTexts } from "@/lib/api"
import { useDocuments, useMessageTexts } from "@/lib/queries"
import { onPendingTextJump, takePendingTextJump } from "@/lib/settings-search"
import { serviceTitle } from "@/lib/words"
import { t } from "@/lib/texts"
import { SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import type { Target } from "@/components/steward/settings-view"
import { type Opened, TEXTS_FILE, TextsForm } from "@/components/steward/texts"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

export type TranslationsSearch = { service?: string } & Opened

/** Keeps the service filter and the open document or text, each only when it is a word. */
export function translationsSearch(search: Record<string, unknown>): TranslationsSearch {
  return Object.fromEntries(
    (["service", "document", "text"] as const).flatMap((name) => {
      const value = search[name]
      return typeof value === "string" && value !== "" ? [[name, value]] : []
    }),
  )
}

/** The first choice of the filter. A service is named in lowercase, so none is called this. */
const EVERY_SERVICE = "ALL"

/** The service filter and the search share a width, and halve the row on a phone. */
const CONTROL_WIDTH = "min-w-0 flex-1 basis-0 sm:w-52 sm:flex-none sm:basis-auto"

/** A counter and not state, because the number only has to differ from the last one. */
let jumps = 0

export function TranslationsPage() {
  const search = useSearch({ from: "/translations" })
  const navigate = useNavigate({ from: "/translations" })
  const { service, ...opened } = search
  const onOpen = useCallback(
    // The card that opens or closes grows or shrinks between its two looks instead of jumping.
    (next: Opened) => void navigate({ search: (kept) => ({ service: kept.service, ...next }), viewTransition: true }),
    [navigate],
  )
  return (
    <Translations
      service={service}
      onService={(next) => void navigate({ search: (kept) => ({ ...kept, service: next }), replace: true })}
      opened={opened}
      onOpen={onOpen}
    />
  )
}

/**
 * Every Discord document and every text of every bundle, grouped by where they appear and then by area. A text that the command palette found opens in the language it was found in.
 */
export function Translations({
  service,
  onService,
  opened,
  onOpen,
}: {
  service: string | undefined
  onService: (service: string | undefined) => void
  opened: Opened
  onOpen: (opened: Opened) => void
}) {
  const texts = useMessageTexts()
  const documents = useDocuments()
  const [target, setTarget] = useState<Target | null>(null)
  const [query, setQuery] = useState("")
  const header = useRef<HTMLElement>(null)

  /**
   * A jump from the command palette. Each gets a new number, so the same hit twice lands twice.
   */
  useEffect(() => {
    const land = () => {
      const jump = takePendingTextJump()
      if (!jump) return
      setQuery("")
      setTarget({ file: TEXTS_FILE, id: jump.id, language: jump.language, seq: ++jumps })
    }
    land()
    return onPendingTextJump(land)
  }, [])

  const title = (
    <PageHeader
      ref={header}
      title={t("steward.shell.page", { page: "translations" })}
      actions={
        <>
          <ServiceFilter texts={texts.data} service={service} onService={onService} />
          {/* Without `min-w-0`, so on a phone the service filter yields and the field keeps its minimum width on screen. */}
          <div className="flex flex-1 basis-0 sm:w-52 sm:flex-none sm:basis-auto">
            <SearchField value={query} onValueChange={setQuery} label={t("steward.texts.search")} />
          </div>
        </>
      }
    />
  )
  return (
    <div className="flex flex-col gap-6">
      {title}
      <QueryState query={texts} rows={8}>
        {(read) => (
          <TextsForm
            texts={read}
            documents={documents.data}
            service={service}
            target={target}
            search={{ query, onQuery: setQuery, at: header }}
            opened={opened}
            onOpen={onOpen}
          />
        )}
      </QueryState>
    </div>
  )
}

function ServiceFilter({
  texts,
  service,
  onService,
}: {
  texts: MessageTexts | undefined
  service: string | undefined
  onService: (service: string | undefined) => void
}) {
  const services = useMemo(
    () => [...new Set((texts?.texts ?? []).flatMap((text) => text.services))].toSorted(),
    [texts],
  )
  return (
    <Select
      value={service ?? EVERY_SERVICE}
      onValueChange={(value) => onService(value === EVERY_SERVICE ? undefined : value)}
    >
      <SelectTrigger aria-label={t("steward.texts.service")} className={CONTROL_WIDTH}>
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
