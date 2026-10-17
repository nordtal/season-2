import { useEffect, useMemo, useRef, useState } from "react"
import { useNavigate, useSearch } from "@tanstack/react-router"

import type { MessageTexts } from "@/lib/api"
import { useMessageTexts } from "@/lib/queries"
import { onPendingTextJump, takePendingTextJump } from "@/lib/settings-search"
import { serviceTitle } from "@/lib/words"
import { t } from "@/lib/texts"
import { SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import type { Target } from "@/components/steward/settings-view"
import { TEXTS_FILE, TextsForm } from "@/components/steward/texts"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/** What `/translations` keeps in its URL: the service it is filtered to, if any. */
export type TranslationsSearch = { service?: string }

export function translationsSearch(search: Record<string, unknown>): TranslationsSearch {
  return typeof search.service === "string" && search.service !== "" ? { service: search.service } : {}
}

/** The value of the filter's first choice; a service is named in lowercase, so none is called this. */
const EVERY_SERVICE = "ALL"

/** The service filter and the search share a width, and halve the row on a phone. */
const CONTROL_WIDTH = "min-w-0 flex-1 basis-0 sm:w-52 sm:flex-none sm:basis-auto"

/** A counter, not state: the number only has to differ from the last one. */
let jumps = 0

/** The Translations page, its filter kept in the URL's `?service=`. */
export function TranslationsPage() {
  const search = useSearch({ from: "/translations" })
  const navigate = useNavigate({ from: "/translations" })
  return (
    <Translations
      service={search.service}
      onService={(service) => void navigate({ search: service ? { service } : {}, replace: true })}
    />
  )
}

/**
 * Every text of every bundle, grouped by where it appears and then by topic, filtered to one service if asked.
 *
 * A text the command palette found is opened here, in the language it was found in.
 */
export function Translations({
  service,
  onService,
}: {
  service: string | undefined
  onService: (service: string | undefined) => void
}) {
  const texts = useMessageTexts()
  const [target, setTarget] = useState<Target | null>(null)
  const [query, setQuery] = useState("")
  const header = useRef<HTMLElement>(null)

  /** A jump from the command palette; each gets a new number, so the same hit twice lands twice. */
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

  return (
    // The editors and previews read best at a bounded width, which the title row keeps too.
    <div className="flex max-w-5xl flex-col gap-6">
      <PageHeader
        ref={header}
        title={t("steward.shell.page", { page: "translations" })}
        actions={
          <>
            <ServiceFilter texts={texts.data} service={service} onService={onService} />
            <SearchField
              value={query}
              onValueChange={setQuery}
              label={t("steward.texts.search")}
              className={CONTROL_WIDTH}
            />
          </>
        }
      />
      <QueryState query={texts} rows={8}>
        {(read) => (
          <TextsForm texts={read} service={service} target={target} search={{ query, onQuery: setQuery, at: header }} />
        )}
      </QueryState>
    </div>
  )
}

/** Every service that shows a text, and none for every text in the network's palette. */
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
