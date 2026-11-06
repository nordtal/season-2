import { CaretRightIcon, PlusIcon } from "@/components/ui/icons"
import { useState } from "react"

import type { AnnouncementView, DocumentView } from "@/lib/api"
import { ACCESS_FILE, FALLBACK_LANGUAGES, announcementTargets } from "@/lib/announcement-targets"
import { defaultChannels, matches, missing, templateOf, titleOf } from "@/lib/announcements"
import { dateTime, relative } from "@/lib/format"
import { ENGLISH, type Language } from "@/lib/message-text"
import { useAnnouncements, useConfig, useCreateAnnouncement, useDocuments } from "@/lib/queries"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import { AnnouncementItem, DeliveryPills } from "@/components/steward/announcement-item"
import { LanguageToggle } from "@/components/steward/document-fields"
import { SearchField } from "@/components/steward/filter-bar"
import { Empty, Failure, QueryState } from "@/components/steward/query-state"
import { useWide } from "@/components/steward/settings-view"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

/**
 * The bot's announcements: the drafts and the searchable sent ones beside the one open, as on Translations. On a phone the
 * list and the open announcement are two steps.
 */
export function Announcements() {
  const announcements = useAnnouncements()
  const documents = useDocuments()
  return (
    <QueryState query={announcements} rows={4}>
      {(list) => (
        <QueryState query={documents} rows={4}>
          {(loaded) => (
            <AnnouncementsView drafts={list.drafts} sent={list.sent} template={templateOf(loaded.documents)} />
          )}
        </QueryState>
      )}
    </QueryState>
  )
}

function AnnouncementsView({
  drafts,
  sent,
  template,
}: {
  drafts: AnnouncementView[]
  sent: AnnouncementView[]
  template: DocumentView | undefined
}) {
  const file = useConfig(ACCESS_FILE)
  const targets = announcementTargets(file.data)
  const languages = targets?.languages.map((language) => language.tag) ?? FALLBACK_LANGUAGES
  const [open, setOpen] = useState<number>()
  const [language, setLanguage] = useState<Language>(ENGLISH)
  const [sentOpen, setSentOpen] = useState(false)
  const [query, setQuery] = useState("")
  const create = useCreateAnnouncement()
  const wide = useWide()
  const item = [...drafts, ...sent].find((each) => each.id === open)
  const found = sent.filter((each) => matches(each.document, query))
  const notEverywhere = sent.filter((each) => missing(each).length > 0).length
  const nothing = drafts.length === 0 && sent.length === 0
  const toggle = <LanguageToggle languages={languages} language={language} onLanguage={setLanguage} />
  const title = (each: AnnouncementView) => titleOf(each.document, language) || t("steward.announcements.untitled")

  const startNew = (
    from = template ? (template.written ?? template.packaged) : undefined,
    channels = defaultChannels(targets),
  ) => {
    if (!from) return
    create.mutate({ document: from, channels }, { onSuccess: (made) => setOpen(made.id) })
  }

  return (
    <div className="grid min-w-0 grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
      <div className={cn("flex min-w-0 flex-col gap-4", item && "max-lg:hidden")}>
        <div className="flex min-w-0 items-center gap-2">
          <Button type="button" disabled={!template || file.isPending || create.isPending} onClick={() => startNew()}>
            <PlusIcon aria-hidden />
            {t("steward.announcements.compose")}
          </Button>
          <span className="ml-auto">{wide || !item ? toggle : null}</span>
        </div>
        {create.error ? <Failure error={create.error} write /> : null}
        {nothing ? null : (
          <section aria-label={t("steward.announcements.drafts")} className="flex flex-col gap-2">
            <h3 className="flex items-center gap-2 px-1 text-sm font-medium">
              {t("steward.announcements.drafts")}
              <Badge variant="secondary" className="tabular-nums">
                {drafts.length}
              </Badge>
            </h3>
            {drafts.length === 0 ? (
              <p className="px-1 text-sm text-muted-foreground">{t("steward.announcements.no-drafts")}</p>
            ) : (
              <ul className="flex flex-col rounded-md border">
                {drafts.map((draft) => (
                  <li key={draft.id} className="border-b last:border-b-0">
                    <ListButton current={open === draft.id} onClick={() => setOpen(draft.id)}>
                      <TERM_ICON.announcement aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span className="truncate text-sm">{title(draft)}</span>
                        <span className="text-xs text-muted-foreground">
                          {t("steward.announcements.changed", { time: relative(draft.changed) })}
                        </span>
                      </span>
                    </ListButton>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
        {sent.length === 0 ? null : (
          <section aria-label={t("steward.announcements.sent")} className="flex flex-col gap-2">
            <button
              type="button"
              aria-expanded={sentOpen}
              onClick={() => setSentOpen(!sentOpen)}
              className="flex items-center gap-2 rounded-sm px-1 text-left text-sm font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <CaretRightIcon aria-hidden className={cn("size-3.5 transition-transform", sentOpen && "rotate-90")} />
              {t("steward.announcements.sent")}
              <Badge variant="secondary" className="tabular-nums">
                {sent.length}
              </Badge>
              {notEverywhere > 0 ? (
                <Badge variant="outline" className="text-destructive">
                  <TERM_ICON.failed aria-hidden />
                  {t("steward.announcements.not-everywhere", { count: notEverywhere })}
                </Badge>
              ) : null}
            </button>
            {sentOpen ? (
              <>
                <div className="flex min-w-0">
                  <SearchField value={query} onValueChange={setQuery} label={t("steward.announcements.search")} />
                </div>
                {found.length === 0 ? (
                  <p className="px-1 text-sm text-muted-foreground">{t("steward.announcements.no-match")}</p>
                ) : (
                  <ul className="flex flex-col rounded-md border">
                    {found.map((each) => (
                      <li key={each.id} className="border-b last:border-b-0">
                        <ListButton current={open === each.id} onClick={() => setOpen(each.id)}>
                          <span className="flex min-w-0 flex-1 flex-col gap-1">
                            <span className="truncate text-sm">
                              <Highlighted words={title(each)} query={query} />
                            </span>
                            <span className="text-xs text-muted-foreground tabular-nums">{dateTime(each.sent)}</span>
                          </span>
                          <DeliveryPills announcement={each} short />
                        </ListButton>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            ) : null}
          </section>
        )}
        {nothing ? (
          <Empty title={t("steward.announcements.empty")} note={t("steward.announcements.empty-note")} />
        ) : null}
      </div>

      {item && template ? (
        <AnnouncementItem
          key={item.id}
          announcement={item}
          template={template}
          languages={languages}
          language={language}
          heading={title(item)}
          toggle={wide ? null : toggle}
          onBack={() => setOpen(undefined)}
          onCopy={() => startNew(item.document, defaultChannels(targets))}
        />
      ) : (
        <div className="max-lg:hidden">
          <Empty title={t("steward.announcements.none-open")} note={t("steward.announcements.none-open-note")} />
        </div>
      )}
    </div>
  )
}

function ListButton({
  current,
  onClick,
  children,
}: {
  current: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-current={current || undefined}
      onClick={onClick}
      className={cn(
        "flex w-full min-w-0 items-center gap-2 p-3 text-left outline-none hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring",
        current && "bg-muted/60",
      )}
    >
      {children}
    </button>
  )
}

function Highlighted({ words, query }: { words: string; query: string }) {
  const wanted = query.trim()
  const at = wanted === "" ? -1 : words.toLowerCase().indexOf(wanted.toLowerCase())
  if (at < 0) return <>{words}</>
  const end = at + wanted.length
  return (
    <>
      {words.slice(0, at)}
      <mark className="rounded-sm bg-mark/20 text-foreground">{words.slice(at, end)}</mark>
      {words.slice(end)}
    </>
  )
}
