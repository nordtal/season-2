import { TERM_ICON } from "@/components/steward/term-icons"
import { useState } from "react"
import { CheckIcon } from "@phosphor-icons/react"

import type { GuildList } from "@/lib/api"
import {
  ACCESS_FILE,
  FALLBACK_LANGUAGES,
  announcementTargets,
  type AnnouncementTargets,
} from "@/lib/announcement-targets"
import { t } from "@/lib/texts"
import { languageName } from "@/lib/language-names"
import { useConfig, useGuildChannels, useOpenAnnouncement, useSendAnnouncement } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { RequestIcon } from "@/components/steward/game-actions"
import { Failure } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/** Discord's limit for one message. */
const MAX_LENGTH = 2000

/** The form sends nothing until every language has its text. */
export function AnnouncementForm() {
  const file = useConfig(ACCESS_FILE)
  const channels = useGuildChannels()
  const [texts, setTexts] = useState<Record<string, string>>({})
  const [asking, setAsking] = useState(false)
  /** The text each language went out with, so a retry sends only the others. */
  const [posted, setPosted] = useState<Record<string, string>>({})
  /** Whether the last send failed, so the pills name which languages went out. */
  const [failed, setFailed] = useState(false)
  /** A send that failed for some languages before a reload comes back with its texts, so a retry still sends only the others. */
  const open = useOpenAnnouncement()
  const [resumed, setResumed] = useState(false)
  if (!resumed && open.data) {
    setResumed(true)
    const lines = open.data.lines
    if (lines.length > 0 && Object.values(texts).every((value) => value.trim() === "")) {
      setTexts(Object.fromEntries(lines.map((line) => [line.language, line.text])))
      setPosted(Object.fromEntries(lines.filter((line) => line.posted).map((line) => [line.language, line.text])))
      setFailed(true)
    }
  }

  const text = (tag: string) => texts[tag] ?? ""
  const send = useSendAnnouncement((tag, sent) => setPosted((current) => ({ ...current, [tag]: sent })))
  const targets = announcementTargets(file.data)
  const tags = targets?.languages.map((language) => language.tag) ?? FALLBACK_LANGUAGES
  const unposted = tags.filter((tag) => posted[tag] !== text(tag).trim())

  const complete = tags.every((tag) => text(tag).trim() !== "" && text(tag).trim().length <= MAX_LENGTH)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <TERM_ICON.announcement className="size-4 text-muted-foreground" aria-hidden />
          {t("steward.announcements.compose")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {tags.map((tag) => (
            <div key={tag} className="flex flex-col gap-2">
              <div className="flex min-h-5 flex-wrap items-center justify-between gap-2">
                <Label htmlFor={`announcement-${tag}`}>{languageName(tag)}</Label>
                <Destination tag={tag} targets={targets} channels={channels.data} />
              </div>
              <Textarea
                id={`announcement-${tag}`}
                className="min-h-30"
                value={text(tag)}
                aria-invalid={text(tag).trim().length > MAX_LENGTH || undefined}
                onChange={(event) => {
                  setTexts((current) => ({ ...current, [tag]: event.target.value }))
                  send.reset()
                }}
              />
              {text(tag).trim().length > MAX_LENGTH ? (
                <span className="text-xs text-destructive">
                  {text(tag).trim().length.toLocaleString("en")} / {MAX_LENGTH.toLocaleString("en")}
                </span>
              ) : null}
            </div>
          ))}
        </div>
        {asking ? null : (
          <>
            {failed ? <PostedLanguages tags={tags} unposted={unposted} /> : null}
            {send.error ? <Failure error={send.error} write /> : null}
          </>
        )}
        <div className="flex justify-end">
          <Button
            type="button"
            aria-label={t("steward.announcements.send")}
            aria-invalid={send.isError || undefined}
            disabled={!complete || unposted.length === 0 || send.isPending}
            onClick={() => setAsking(true)}
          >
            <RequestIcon request={send} />
            {t("steward.announcements.send")}
          </Button>
        </div>
      </CardContent>

      <AskThenAct
        open={asking}
        onOpenChange={setAsking}
        title={t("steward.announcements.ask")}
        description={t("steward.announcements.where", { languages: unposted.length })}
        action={t("steward.announcements.send")}
        acting={t("steward.form.waiting")}
        act={() =>
          send.mutateAsync(Object.fromEntries(unposted.map((tag) => [tag, text(tag).trim()])), {
            onSettled: (_, error) => setFailed(error !== null),
          })
        }
      >
        {failed ? <PostedLanguages tags={tags} unposted={unposted} /> : null}
      </AskThenAct>
    </Card>
  )
}

/** One pill per language when some languages are posted and others are not. Nothing shows when none or all are posted. */
function PostedLanguages({ tags, unposted }: { tags: string[]; unposted: string[] }) {
  if (unposted.length === 0 || unposted.length === tags.length) return null
  return (
    <ul className="flex flex-wrap gap-2">
      {tags.map((tag) => {
        const missing = unposted.includes(tag)
        return (
          <li key={tag}>
            <Badge variant="outline" className={missing ? "text-destructive" : undefined}>
              {missing ? <TERM_ICON.failed aria-hidden /> : <CheckIcon aria-hidden />}
              {t(missing ? "steward.announcements.not-posted" : "steward.announcements.posted", {
                language: languageName(tag),
              })}
            </Badge>
          </li>
        )
      })}
    </ul>
  )
}

function Destination({
  tag,
  targets,
  channels,
}: {
  tag: string
  targets: AnnouncementTargets | null
  channels: GuildList | undefined
}) {
  if (!targets) return null
  if (targets.overridden) {
    return <span className="text-xs text-muted-foreground">{t("steward.announcements.host-channel")}</span>
  }
  const channel = targets.languages.find((language) => language.tag === tag)?.channel ?? ""
  if (channel === "") {
    return (
      <Badge variant="outline" className="text-destructive">
        {t("steward.announcements.no-channel")}
      </Badge>
    )
  }
  const name = channels?.entries.find((entry) => entry.id === channel)?.name
  return (
    <span className="flex min-w-0 items-center gap-0.5 text-xs text-muted-foreground">
      <TERM_ICON.textChannel aria-hidden className="shrink-0" />
      <span className="truncate">{name ?? channel}</span>
    </span>
  )
}
