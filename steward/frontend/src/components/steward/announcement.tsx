import { HashIcon, MegaphoneIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { GuildList } from "@/lib/api"
import {
  ACCESS_FILE,
  FALLBACK_LANGUAGES,
  announcementTargets,
  type AnnouncementTargets,
} from "@/lib/announcement-targets"
import { t } from "@/lib/texts"
import { languageName } from "@/lib/language-names"
import { useCommandRun, useConfig, useGuildChannels, useSendAnnouncement } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { RequestOutcome } from "@/components/steward/game-actions"
import { Failure, SkeletonText } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/** Discord's own cap on one message, mirrored from the backend's refusal. */
const MAX_LENGTH = 2000

/**
 * An announcement: one text per language, each posted by the bot into that language's channel.
 *
 * Nothing is sent until every language has its text.
 */
export function AnnouncementForm() {
  const file = useConfig(ACCESS_FILE)
  const channels = useGuildChannels()
  const send = useSendAnnouncement()
  const targets = announcementTargets(file.data)
  const tags = targets?.languages.map((language) => language.tag) ?? FALLBACK_LANGUAGES

  const [texts, setTexts] = useState<Record<string, string>>({})
  const [asking, setAsking] = useState(false)
  const [sent, setSent] = useState<Record<string, string> | null>(null)

  const text = (tag: string) => texts[tag] ?? ""
  const complete = tags.every((tag) => text(tag).trim() !== "" && text(tag).trim().length <= MAX_LENGTH)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <MegaphoneIcon className="size-4 text-muted-foreground" aria-hidden />
          {t("steward.announcements.compose")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {tags.map((tag) => (
            <div key={tag} className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Label htmlFor={`announcement-${tag}`}>{languageName(tag)}</Label>
                <Destination tag={tag} targets={targets} channels={channels.data} />
              </div>
              <Textarea
                id={`announcement-${tag}`}
                rows={5}
                value={text(tag)}
                aria-invalid={text(tag).trim().length > MAX_LENGTH || undefined}
                onChange={(event) => {
                  setTexts((current) => ({ ...current, [tag]: event.target.value }))
                  setSent(null)
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
        {send.error && !asking ? <Failure error={send.error} /> : null}
        {sent ? (
          <div className="flex flex-col gap-2">
            {Object.entries(sent).map(([tag, id]) => (
              <SentLine key={id} tag={tag} id={id} />
            ))}
          </div>
        ) : null}
        <div className="flex justify-end">
          <Button type="button" disabled={!complete} onClick={() => setAsking(true)}>
            {t("steward.announcements.send")}
          </Button>
        </div>
      </CardContent>

      <AskThenAct
        open={asking}
        onOpenChange={setAsking}
        title={t("steward.announcements.ask")}
        description={t("steward.announcements.where", { languages: tags.length })}
        action={t("steward.announcements.send")}
        acting={t("steward.form.sending")}
        act={() =>
          send.mutateAsync(Object.fromEntries(tags.map((tag) => [tag, text(tag).trim()]))).then((answer) => {
            setSent(answer.ids)
            setTexts({})
          })
        }
      />
    </Card>
  )
}

/** Where one language's line will land, as far as the file can say. */
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
      <HashIcon aria-hidden className="shrink-0" />
      <span className="truncate">{name ?? channel}</span>
    </span>
  )
}

function SentLine({ tag, id }: { tag: string; id: string }) {
  const run = useCommandRun(id)
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-medium text-muted-foreground">{languageName(tag)}</span>
      {run.error ? <Failure error={run.error} onRetry={run.refetch} /> : null}
      {run.data ? <RequestOutcome run={run.data} /> : <SkeletonText width="medium" />}
    </div>
  )
}
