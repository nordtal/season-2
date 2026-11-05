import { useId, useState } from "react"
import { CalendarPlusIcon, PencilSimpleIcon } from "@phosphor-icons/react"

import type { Router } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { choice, t } from "@/lib/texts"
import { StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { CloseDialog, DateDialog, LaunchNowDialog, zone } from "@/components/steward/router/router-dialogs"
import { Fact, Facts, KeyedButton, Note, Parts, RouterCard } from "@/components/steward/router/router-parts"

type Open = "date" | "launch-now" | "close" | null

/**
 * The launch date, the one field behind the gate: none keeps the season closed, a date ahead counts down to it, and a
 * date passed has launched it.
 */
export function LaunchCard({ router, now }: { router: Router; now: number }) {
  const [editing, setEditing] = useState<Date | null>(null)
  const [open, setOpen] = useState<Open>(null)
  const opening = router.opening
  const edit = () => setEditing(router.launch && opening === "COUNTDOWN" ? new Date(router.launch) : tomorrow(now))

  return (
    <RouterCard
      icon={TERM_ICON.season}
      title={t("steward.router.launch-title")}
      badges={
        <StatusBadge tone={opening === "LAUNCHED" ? "ok" : "idle"}>
          {t("steward.router.opening", { opening: choice(opening) })}
        </StatusBadge>
      }
    >
      {editing ? (
        <DateEditor
          at={editing}
          now={now}
          launched={opening === "LAUNCHED"}
          onChange={setEditing}
          onSave={() => setOpen("date")}
          onCancel={() => setEditing(null)}
        />
      ) : opening === "CLOSED" ? (
        <>
          <Facts>
            <Fact term={t("steward.router.launch-date")} hint={t("steward.router.closed-screen")}>
              <span className="text-muted-foreground">{t("steward.router.not-set")}</span>
            </Fact>
          </Facts>
          <KeyedButton variant="outline" className="mt-auto w-full" onClick={edit}>
            <CalendarPlusIcon aria-hidden />
            {t("steward.router.set-launch-date")}
          </KeyedButton>
        </>
      ) : (
        <>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <Facts>
              <Fact term={t("steward.router.launch-date")} hint={relative(router.launch, now)}>
                <span className="tnum whitespace-nowrap">{dateTime(router.launch)}</span>
              </Fact>
            </Facts>
            <KeyedButton variant="ghost" size="sm" onClick={edit}>
              <PencilSimpleIcon aria-hidden />
              {t("steward.router.change")}
            </KeyedButton>
          </div>
          {opening === "COUNTDOWN" ? (
            <KeyedButton variant="outline" className="mt-auto w-full" onClick={() => setOpen("launch-now")}>
              <TERM_ICON.launchNow aria-hidden />
              {t("steward.router.launch-now")}
            </KeyedButton>
          ) : (
            <KeyedButton variant="outline" className="mt-auto w-full" onClick={() => setOpen("close")}>
              <TERM_ICON.closed aria-hidden />
              {t("steward.router.close-season")}
            </KeyedButton>
          )}
        </>
      )}
      {editing ? (
        <DateDialog
          router={router}
          at={editing}
          now={now}
          open={open === "date"}
          onOpenChange={(next) => setOpen(next ? "date" : null)}
          onDone={() => setEditing(null)}
        />
      ) : null}
      <LaunchNowDialog
        router={router}
        now={now}
        open={open === "launch-now"}
        onOpenChange={(next) => setOpen(next ? "launch-now" : null)}
      />
      <CloseDialog router={router} open={open === "close"} onOpenChange={(next) => setOpen(next ? "close" : null)} />
    </RouterCard>
  )
}

/** Returns the instant as a `datetime-local` field writes it, in the browser's time zone, to the minute. */
export function localInput(at: Date): string {
  return `${String(at.getFullYear()).padStart(4, "0")}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`
}

/** The date the editor offers where none lies ahead: the same hour tomorrow, on the full hour. */
export function tomorrow(now: number): Date {
  const at = new Date(now)
  at.setDate(at.getDate() + 1)
  at.setMinutes(0, 0, 0)
  return at
}

function pad(value: number): string {
  return String(value).padStart(2, "0")
}

function DateEditor({
  at,
  now,
  launched,
  onChange,
  onSave,
  onCancel,
}: {
  at: Date
  now: number
  launched: boolean
  onChange: (at: Date) => void
  onSave: () => void
  onCancel: () => void
}) {
  const id = useId()
  const error = `${id}-error`
  const past = at.getTime() < now
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{t("steward.router.launch-date")}</Label>
      <Input
        id={id}
        type="datetime-local"
        className="tnum"
        value={localInput(at)}
        min={localInput(new Date(now))}
        aria-invalid={past}
        aria-describedby={past ? error : undefined}
        onChange={(event) => {
          const next = new Date(event.target.value)
          if (!Number.isNaN(next.getTime())) onChange(next)
        }}
      />
      <Parts className="text-xs text-muted-foreground tnum">
        <span className="whitespace-nowrap">{dateTime(at)}</span>
        {past ? null : <span className="whitespace-nowrap">{relative(at, now)}</span>}
        <span className="whitespace-nowrap">{zone()}</span>
      </Parts>
      {past ? (
        <p id={error} className="flex items-center gap-1.5 text-xs text-destructive">
          <TERM_ICON.warning aria-hidden className="size-3.5 shrink-0" />
          {t("steward.router.not-in-past", { earliest: new Date(now) })}
        </p>
      ) : null}
      {launched && !past ? (
        <Note tone="warn" small>
          {t("steward.router.closes-until")}
        </Note>
      ) : null}
      <div className="flex items-center gap-2">
        <Button type="button" size="sm" disabled={past} onClick={onSave}>
          {t("steward.form.save")}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
          {t("steward.form.cancel")}
        </Button>
      </div>
    </div>
  )
}
