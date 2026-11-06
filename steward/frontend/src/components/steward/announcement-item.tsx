import { ArrowLeftIcon, CheckIcon, CopyIcon, SpinnerIcon, TrashIcon } from "@/components/ui/icons"
import { useState, type ReactNode } from "react"
import { toast } from "sonner"

import type { AnnouncementView, Document, DocumentView } from "@/lib/api"
import { channelsOf, missing, same, viewFor } from "@/lib/announcements"
import { dateTime } from "@/lib/format"
import { languageName } from "@/lib/language-names"
import type { Language } from "@/lib/message-text"
import { useDeleteAnnouncement, useGuildChannels, useSaveAnnouncement, useSendAnnouncement } from "@/lib/queries"
import { t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { DocumentEditor } from "@/components/steward/document-editor"
import { RequestIcon } from "@/components/steward/game-actions"
import { DocumentPreview } from "@/components/steward/message-editor/document-preview"
import { Failure } from "@/components/steward/query-state"
import { ReferencePicker } from "@/components/steward/reference-picker"
import { Hint } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"

const CHANNEL = { to: "DISCORD_CHANNEL", optional: true } as const

/** One announcement: a draft with its editor and a channel per language, or a sent one to read, copy and retry. */
export function AnnouncementItem({
  announcement,
  template,
  languages,
  language,
  heading,
  toggle,
  onBack,
  onCopy,
}: {
  announcement: AnnouncementView
  template: DocumentView
  languages: string[]
  language: Language
  heading: string
  /** The language toggle, when the list beside it does not show it. */
  toggle: ReactNode
  onBack: () => void
  onCopy: () => void
}) {
  return (
    <article aria-label={heading} className="flex min-w-0 flex-col gap-4">
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex min-w-0 items-center gap-2">
          <Hint tip={t("steward.announcements.tab")}>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("steward.announcements.tab")}
              className="-ml-2 lg:hidden"
              onClick={onBack}
            >
              <ArrowLeftIcon aria-hidden />
            </Button>
          </Hint>
          <h2 className="min-w-0 flex-1 truncate text-lg font-semibold">{heading}</h2>
          {toggle}
        </div>
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {announcement.sent ? (
            <>
              <span className="text-xs text-muted-foreground tabular-nums">
                {t("steward.announcements.sent-at", { time: dateTime(announcement.sent) })}
              </span>
              <DeliveryPills announcement={announcement} />
            </>
          ) : (
            <Badge variant="outline">{t("steward.announcements.draft")}</Badge>
          )}
        </div>
      </div>
      {announcement.sent ? (
        <SentItem announcement={announcement} template={template} language={language} onCopy={onCopy} />
      ) : (
        <DraftItem
          announcement={announcement}
          template={template}
          languages={languages}
          language={language}
          onDeleted={onBack}
        />
      )}
    </article>
  )
}

function DraftItem({
  announcement,
  template,
  languages,
  language,
  onDeleted,
}: {
  announcement: AnnouncementView
  template: DocumentView
  languages: string[]
  language: Language
  onDeleted: () => void
}) {
  const [document, setDocument] = useState<Document>(announcement.document)
  const [channels, setChannels] = useState<Record<string, string>>(() => channelsOf(announcement))
  const [asking, setAsking] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const save = useSaveAnnouncement()
  const remove = useDeleteAnnouncement()
  const send = useSendAnnouncement()
  const view = viewFor(template, announcement.document)
  const changed = !same(document, announcement.document) || !same(channels, channelsOf(announcement))
  const shown = [...new Set([...languages, ...Object.keys(channels)])]
  const busy = save.isPending || send.isPending || remove.isPending

  const discard = () => {
    setDocument(announcement.document)
    setChannels(channelsOf(announcement))
  }
  const saved = () => save.mutateAsync({ id: announcement.id, draft: { document, channels } })

  return (
    <>
      <DocumentPreview declaration={view.declaration} game={false} document={document} language={language} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {shown.map((tag) => (
          <ChannelField
            key={tag}
            tag={tag}
            channel={channels[tag] ?? ""}
            disabled={busy}
            onChange={(channel) =>
              setChannels((current) => {
                const next = { ...current }
                if (channel === "") delete next[tag]
                else next[tag] = channel
                return next
              })
            }
          />
        ))}
      </div>
      <DocumentEditor
        view={view}
        draft={document}
        language={language}
        onDraft={(next) => (next ? setDocument(next) : discard())}
        renderActions={(refused) => (
          <>
            <Hint tip={t("steward.announcements.delete")}>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={t("steward.announcements.delete")}
                disabled={busy}
                onClick={() => setDeleting(true)}
              >
                <TrashIcon aria-hidden />
              </Button>
            </Hint>
            <span className="ml-auto flex items-center gap-1 md:gap-2">
              <Button type="button" variant="ghost" disabled={!changed || busy} onClick={discard}>
                {t("steward.document-editor.discard")}
              </Button>
              <Button
                type="button"
                variant="secondary"
                disabled={!changed || refused || busy}
                onClick={() => void saved().then(() => toast.success(t("steward.announcements.saved")))}
              >
                {t("steward.document-editor.save")}
              </Button>
              <Button
                type="button"
                aria-invalid={send.isError || undefined}
                disabled={refused || busy || Object.keys(channels).length === 0}
                onClick={() => setAsking(true)}
              >
                <RequestIcon request={send} icon={TERM_ICON.announcement} />
                <span className="max-sm:hidden">{t("steward.announcements.send")}</span>
                <span className="sm:hidden">{t("steward.announcements.send-short")}</span>
              </Button>
            </span>
          </>
        )}
      />
      {save.error ? <Failure error={save.error} write /> : null}
      {remove.error ? <Failure error={remove.error} write /> : null}
      {send.error && !asking ? <Failure error={send.error} write /> : null}

      <AskThenAct
        open={asking}
        onOpenChange={setAsking}
        title={t("steward.announcements.ask")}
        description={<Where channels={channels} />}
        action={t("steward.announcements.send-short")}
        acting={t("steward.form.waiting")}
        act={async () => {
          if (changed) await saved()
          await send.mutateAsync(announcement.id)
        }}
      />
      <AskThenAct
        open={deleting}
        onOpenChange={setDeleting}
        title={t("steward.announcements.delete")}
        action={t("steward.announcements.delete")}
        destructive
        act={() =>
          remove.mutateAsync(announcement.id, {
            onSuccess: () => {
              toast.success(t("steward.announcements.deleted"))
              onDeleted()
            },
          })
        }
      />
    </>
  )
}

function ChannelField({
  tag,
  channel,
  disabled,
  onChange,
}: {
  tag: string
  channel: string
  disabled: boolean
  onChange: (channel: string) => void
}) {
  const id = `announcement-channel-${tag}`
  const label = t("steward.announcements.channel", { language: languageName(tag) })
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <ReferencePicker
        id={id}
        label={label}
        reference={CHANNEL}
        values={channel === "" ? [] : [channel]}
        multi={false}
        disabled={disabled}
        typed={null}
        face={
          channel === "" ? (
            <span className="text-muted-foreground">{t("steward.announcements.no-channel")}</span>
          ) : undefined
        }
        onChange={(values) => onChange(values[0] ?? "")}
      />
    </div>
  )
}

/** The channels a send posts in, by their names where the guild's list knows them. */
function Where({ channels }: { channels: Record<string, string> }) {
  const guild = useGuildChannels()
  const named = Object.values(channels).map(
    (id) => `#${guild.data?.entries.find((entry) => entry.id === id)?.name ?? id}`,
  )
  return <>{t("steward.announcements.where", { channels: named.join(", ") })}</>
}

function SentItem({
  announcement,
  template,
  language,
  onCopy,
}: {
  announcement: AnnouncementView
  template: DocumentView
  language: Language
  onCopy: () => void
}) {
  const send = useSendAnnouncement()
  const open = missing(announcement)
  return (
    <>
      <DocumentPreview
        declaration={template.declaration}
        game={false}
        document={announcement.document}
        language={language}
      />
      {send.error ? <Failure error={send.error} write /> : null}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCopy}>
          <CopyIcon aria-hidden />
          {t("steward.announcements.copy")}
        </Button>
        {open.length > 0 ? (
          <Button
            type="button"
            aria-invalid={send.isError || undefined}
            disabled={send.isPending}
            onClick={() => send.mutate(announcement.id)}
          >
            <RequestIcon request={send} icon={TERM_ICON.announcement} />
            {t("steward.announcements.retry", { languages: open.map(languageName).join(", ") })}
          </Button>
        ) : null}
      </div>
    </>
  )
}

/** One pill per language of a sent announcement: posted, on its way, or not posted. */
export function DeliveryPills({ announcement, short }: { announcement: AnnouncementView; short?: boolean }) {
  return (
    <ul className="flex shrink-0 flex-wrap gap-1">
      {announcement.targets.map((target) => {
        const name = languageName(target.language)
        const [label, icon, tone] =
          target.delivery === "POSTED"
            ? [t("steward.announcements.posted", { language: name }), <CheckIcon key="icon" aria-hidden />, undefined]
            : target.delivery === "SENDING" || target.delivery === "UNSENT"
              ? [
                  t("steward.announcements.posting", { language: name }),
                  <SpinnerIcon key="icon" aria-hidden className="animate-spin" />,
                  "text-muted-foreground",
                ]
              : [
                  t("steward.announcements.not-posted", { language: name }),
                  <TERM_ICON.failed key="icon" aria-hidden />,
                  "text-destructive",
                ]
        return (
          <li key={target.language}>
            <Hint tip={short ? label : undefined}>
              <Badge variant="outline" className={tone}>
                {icon}
                {short ? target.language.toUpperCase() : label}
              </Badge>
            </Hint>
          </li>
        )
      })}
    </ul>
  )
}
