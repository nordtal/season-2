import { CalendarDotIcon, FlagIcon } from "@phosphor-icons/react"
import { useNavigate, useSearch } from "@tanstack/react-router"
import { useState } from "react"
import { toast } from "sonner"

import type { Season } from "@/lib/api"
import { useSeason, useSetPhase, useSetSeasonDate } from "@/lib/queries"
import { SEASON_PHASES as PHASES, type SeasonPhaseName as PhaseName } from "@/lib/season-phases"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { PageHeader } from "@/components/steward/page-header"
import { NETWORK, ServiceSettings } from "@/components/steward/settings"
import { Failure, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { choice, t } from "@/lib/texts"

/** The open network settings group, in the URL like a service's, so Ctrl-K can land on it. */
export type SeasonSearch = { file?: string }

export function seasonSearch(search: Record<string, unknown>): SeasonSearch {
  return typeof search.file === "string" && search.file !== "" ? { file: search.file } : {}
}

/**
 * The season page: the phase, which decides who may join and where, its two dates, and the network's settings.
 *
 * Every process reads the phase from one row, so a switch applies on the next join; that is why it asks twice.
 */
export function SeasonPage() {
  const season = useSeason()
  const search = useSearch({ from: "/season" })
  const navigate = useNavigate({ from: "/season" })

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.season.title")} />

      <QueryState query={season}>
        {(current) => (
          <>
            <PhaseCard season={current} />
            {/* No button ends a season: each one is a full rebuild, with nothing carried over. */}
            <DatesCard season={current} />
          </>
        )}
      </QueryState>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold text-foreground">{t("steward.season.network")}</h2>
        <ServiceSettings
          service={NETWORK}
          file={search.file}
          onFile={(file, replace) => void navigate({ search: file ? { file } : {}, replace })}
        />
      </section>
    </div>
  )
}

function PhaseCard({ season }: { season?: Season }) {
  const [asked, setAsked] = useState<PhaseName | null>(null)
  const [reason, setReason] = useState("")
  const change = useSetPhase()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FlagIcon className="size-4 text-muted-foreground" aria-hidden />
          {t("steward.season.phase-heading")}
        </CardTitle>
        <CardDescription>
          {season ? (
            <>
              <span className="font-medium text-foreground">{phaseName(season.phase)}</span>{" "}
              <span className="font-mono text-xs">({season.phase})</span>
            </>
          ) : (
            <SkeletonText width="medium" />
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {/* Only while the dialog is shut; a refused switch keeps it open and repeats the refusal inside. */}
        {change.error && asked === null ? <Failure error={change.error} /> : null}

        {/* The five phases are static, so only which one is current waits. */}
        {PHASES.map((phase) => {
          const active = phase.name === season?.phase
          return (
            <div
              key={phase.name}
              className={`flex flex-wrap items-start justify-between gap-3 rounded-md border px-3 py-3 ${
                active ? "border-primary/40 bg-primary/5" : "border-border"
              }`}
            >
              <div className="flex min-w-0 flex-col gap-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{phaseName(phase.name)}</span>
                  {active ? <Badge variant="secondary">{t("steward.season.now")}</Badge> : null}
                  {season ? null : <Skeleton className="h-5 w-10 rounded-full" />}
                  <span className="font-mono text-xs text-muted-foreground">{phase.name}</span>
                </div>
                <p className="text-sm text-muted-foreground">{t("steward.season.lands-on", { where: phase.where })}</p>
              </div>
              <Button
                type="button"
                variant={active ? "ghost" : "outline"}
                size="sm"
                disabled={!season || active || change.isPending}
                onClick={() => setAsked(phase.name)}
              >
                {active ? t("steward.season.current") : t("steward.season.switch-phase")}
              </Button>
            </div>
          )
        })}
      </CardContent>

      {/* The reason is cleared with the dialog, or the next confirmation would send a cancelled one. */}
      <AskThenAct
        open={asked !== null}
        onOpenChange={(open) => {
          if (open) return
          setAsked(null)
          setReason("")
        }}
        title={asked ? t("steward.season.switch-title", { phase: phaseName(asked) }) : ""}
        action={t("steward.season.switch-phase")}
        acting={t("steward.season.switching")}
        act={() => {
          const phase = asked
          if (!phase) return Promise.resolve()
          return change
            .mutateAsync({ phase, reason })
            .then(() => toast.success(t("steward.season.phase-is-now", { phase: phaseName(phase) })))
        }}
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="phase-reason">{t("steward.season.reason")}</Label>
          <Input
            id="phase-reason"
            value={reason}
            placeholder={t("steward.season.reason-placeholder")}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
      </AskThenAct>
    </Card>
  )
}

function DatesCard({ season }: { season?: Season }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CalendarDotIcon className="size-4 text-muted-foreground" aria-hidden />
          {t("steward.season.dates")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <DateField which="launch" label={t("steward.season.launch")} at={season?.launch} waiting={!season} />
        <DateField
          which="smpStart"
          label={t("steward.season.smp-start")}
          removal={t("steward.season.smp-start-removal")}
          at={season?.smpStart}
          waiting={!season}
        />
      </CardContent>
    </Card>
  )
}

function DateField({
  which,
  label,
  removal,
  at,
  waiting,
}: {
  which: "launch" | "smpStart"
  label: string
  /** What removing this date leaves behind, where that is not obvious. */
  removal?: string
  at?: string
  /** `at` is absent for two different reasons: no date is set, or none has arrived yet. */
  waiting?: boolean
}) {
  const saved = toLocalInput(at)
  const [local, setLocal] = useState(saved)
  const change = useSetSeasonDate()
  const dirty = local !== saved
  const [removing, setRemoving] = useState(false)

  /** The field mounts before the answer, so it takes the saved value only while still untouched. */
  if (local === "" && saved !== "") setLocal(saved)

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={which}>{label}</Label>
      <div className="flex flex-wrap items-center gap-2">
        {waiting ? (
          <Skeleton className="h-control w-56" />
        ) : (
          <Input
            id={which}
            type="datetime-local"
            className="w-auto"
            value={local}
            onChange={(event) => setLocal(event.target.value)}
          />
        )}
        <Button
          type="button"
          size="sm"
          disabled={waiting || !dirty || local === "" || change.isPending}
          onClick={() =>
            change.mutate(
              { which, at: new Date(local).toISOString() },
              { onSuccess: () => toast.success(t("steward.season.date-saved", { date: label })) },
            )
          }
        >
          {t("steward.form.save")}
        </Button>
        {/* Reset while something is typed, Remove otherwise, so the row never wraps on a phone. */}
        {dirty ? (
          <Button type="button" variant="ghost" size="sm" onClick={() => setLocal(toLocalInput(at))}>
            {t("steward.form.reset")}
          </Button>
        ) : at && !waiting ? (
          <Button type="button" variant="ghost" size="sm" disabled={change.isPending} onClick={() => setRemoving(true)}>
            {t("steward.form.remove")}
          </Button>
        ) : null}
      </div>
      <AskThenAct
        open={removing}
        onOpenChange={setRemoving}
        title={t("steward.season.remove-title", { date: label })}
        description={removal}
        action={t("steward.form.remove")}
        acting={t("steward.form.removing")}
        destructive
        act={() =>
          change.mutateAsync({ which, at: null }).then(() => {
            setLocal("")
            toast.success(t("steward.season.date-removed", { date: label }))
          })
        }
      />
      {waiting ? (
        <SkeletonText className="text-sm" width="long" />
      ) : (
        <p className="text-sm text-muted-foreground">
          {at ? t("steward.season.saved-at", { at }) : t("steward.season.no-date")}
        </p>
      )}
      {change.error && !removing ? <Failure error={change.error} /> : null}
    </div>
  )
}

/** A phase's name from the bundle, or the constant itself for one this page does not know. */
function phaseName(phase: string): string {
  return t("steward.season.phase", { phase: choice(phase) })
}

function pad(value: number): string {
  return String(value).padStart(2, "0")
}

/**
 * An instant as `datetime-local` wants it: local wall clock, no zone, minute precision.
 *
 * `toISOString().slice(0, 16)` would be UTC and off by the zone offset.
 */
function toLocalInput(at?: string): string {
  if (!at) return ""
  const date = new Date(at)
  if (Number.isNaN(date.getTime())) return ""
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}
