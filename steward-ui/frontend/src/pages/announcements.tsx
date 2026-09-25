import { HashIcon, MegaphoneIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { Announcement, GuildList } from "@/lib/api"
import {
  ACCESS_FILE,
  FALLBACK_LANGUAGES,
  announcementTargets,
  type AnnouncementTargets,
} from "@/lib/announcement-targets"
import { relative } from "@/lib/format"
import { languageName } from "@/lib/language-names"
import {
  useAnnouncements,
  useCommandRun,
  useConfig,
  useGuildChannels,
  useSendAnnouncement,
} from "@/lib/queries"
import { RequestOutcome } from "@/components/steward/game-actions"
import { PageHeader } from "@/components/steward/page-header"
import { Failure, QueryState, SkeletonText } from "@/components/steward/query-state"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/**
 * Announcements: one text per language, each posted by the bot into that language's channel.
 *
 * The same `announce` row the SMP writes at a milestone, so the list beside the form is one channel
 * with two senders. Every language needs its text before anything is sent - a line that went out
 * in one language of two is the mistake this page is for.
 */

/** Discord's own cap on one message, mirrored from the backend's refusal. */
const MAX_LENGTH = 2000

export function AnnouncementsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Announcements" />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] xl:items-start">
        <Compose />
        <Recent />
      </div>
    </div>
  )
}

function Compose() {
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
          New announcement
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
            Send
          </Button>
        </div>
      </CardContent>

      <ResponsiveAlertDialog
        open={asking}
        onOpenChange={(open) => {
          if (!open && !send.isPending) setAsking(false)
        }}
      >
        <ResponsiveAlertDialogContent>
          <ResponsiveAlertDialogHeader>
            <ResponsiveAlertDialogTitle>Send this announcement?</ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription>
              {tags.length === 1
                ? "It is posted in Discord at once."
                : `One post per language, ${tags.length} in all, in Discord at once.`}
            </ResponsiveAlertDialogDescription>
          </ResponsiveAlertDialogHeader>
          {send.error ? <Failure error={send.error} /> : null}
          <ResponsiveAlertDialogFooter>
            <ResponsiveAlertDialogCancel disabled={send.isPending}>Cancel</ResponsiveAlertDialogCancel>
            <ResponsiveAlertDialogAction
              disabled={send.isPending}
              onClick={(event) => {
                event.preventDefault()
                const body = Object.fromEntries(tags.map((tag) => [tag, text(tag).trim()]))
                send.mutate(body, {
                  onSuccess: (answer) => {
                    setSent(answer.ids)
                    setTexts({})
                    setAsking(false)
                  },
                })
              }}
            >
              {send.isPending ? "Sending…" : "Send"}
            </ResponsiveAlertDialogAction>
          </ResponsiveAlertDialogFooter>
        </ResponsiveAlertDialogContent>
      </ResponsiveAlertDialog>
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
    return <span className="text-xs text-muted-foreground">channel set by the host</span>
  }
  const channel = targets.languages.find((language) => language.tag === tag)?.channel ?? ""
  if (channel === "") {
    return (
      <Badge variant="outline" className="text-destructive">
        no channel
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

function Recent() {
  const announcements = useAnnouncements()
  return (
    <Card>
      <CardHeader>
        <CardTitle>Recent</CardTitle>
      </CardHeader>
      <CardContent>
        <QueryState
          query={announcements}
          isEmpty={(data) => data.recent.length === 0}
          empty={{ title: "Nothing announced yet." }}
        >
          {(data) =>
            data ? (
              <ul className="flex flex-col divide-y divide-border">
                {data.recent.map((line) => (
                  <RecentLine key={line.id} line={line} />
                ))}
              </ul>
            ) : (
              <SkeletonText width="long" />
            )
          }
        </QueryState>
      </CardContent>
    </Card>
  )
}

function RecentLine({ line }: { line: Announcement }) {
  const failed = line.status === "FAILED" || line.status === "EXPIRED"
  return (
    <li className="flex flex-col gap-1 py-3 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <Badge variant="secondary">{line.language}</Badge>
        <span className="min-w-0 truncate">{line.source === "WEB" ? senderName(line.requestedBy) : line.requestedBy}</span>
        <span className="tabular-nums">{relative(line.requested)}</span>
        {line.status !== "DONE" ? (
          <Badge variant="outline" className={failed ? "text-destructive" : undefined}>
            {line.status.toLowerCase()}
          </Badge>
        ) : null}
      </div>
      <p className="whitespace-pre-wrap break-words text-sm">{line.text}</p>
      {line.result ? <p className="text-xs text-muted-foreground">{line.result}</p> : null}
    </li>
  )
}

/** `requested_by` of a web row is "name (discord id)"; the name is what a person reads. */
export function senderName(requestedBy: string): string {
  return requestedBy.replace(/\s*\(\d+\)$/, "")
}
