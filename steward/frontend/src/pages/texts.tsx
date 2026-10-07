import { useEffect, useMemo, useState } from "react"
import { useNavigate, useSearch } from "@tanstack/react-router"

import type { MessageTexts } from "@/lib/api"
import { useMessageTexts } from "@/lib/queries"
import { onPendingTextJump, takePendingTextJump } from "@/lib/settings-search"
import { serviceTitle } from "@/lib/words"
import { t } from "@/lib/texts"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import type { Target } from "@/components/steward/settings-view"
import { TEXTS_FILE, TextsForm } from "@/components/steward/texts"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/** What `/texts` keeps in its URL: the service it is filtered to, if any. */
export type TextsSearch = { service?: string }

export function textsSearch(search: Record<string, unknown>): TextsSearch {
  return typeof search.service === "string" && search.service !== "" ? { service: search.service } : {}
}

/** The value of the filter's first choice; a service is named in lowercase, so none is called this. */
const EVERY_SERVICE = "ALL"

/** A counter, not state: the number only has to differ from the last one. */
let jumps = 0

/** The Texts page, its filter kept in the URL's `?service=`. */
export function TextsPage() {
  const search = useSearch({ from: "/texts" })
  const navigate = useNavigate({ from: "/texts" })
  return (
    <Texts
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
export function Texts({
  service,
  onService,
}: {
  service: string | undefined
  onService: (service: string | undefined) => void
}) {
  const texts = useMessageTexts()
  const [target, setTarget] = useState<Target | null>(null)

  /** A jump from the command palette; each gets a new number, so the same hit twice lands twice. */
  useEffect(() => {
    const land = () => {
      const jump = takePendingTextJump()
      if (jump) setTarget({ file: TEXTS_FILE, id: jump.id, language: jump.language, seq: ++jumps })
    }
    land()
    return onPendingTextJump(land)
  }, [])

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t("steward.shell.page", { page: "texts" })}
        actions={<ServiceFilter texts={texts.data} service={service} onService={onService} />}
      />
      <QueryState query={texts} rows={8}>
        {(read) => <TextsForm texts={read} service={service} target={target} />}
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
      <SelectTrigger aria-label={t("steward.texts.service")} className="w-full sm:w-52">
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
