import { CalendarDotIcon, FlagIcon, ShieldWarningIcon } from "@phosphor-icons/react"
import { useNavigate, useSearch } from "@tanstack/react-router"
import { useState } from "react"
import { toast } from "sonner"

import type { Season } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { useSeason, useSetPhase, useSetSeasonDate } from "@/lib/queries"
import { SEASON_PHASES as PHASES, type SeasonPhaseName as PhaseName } from "@/lib/season-phases"
import { PageHeader } from "@/components/steward/page-header"
import { NETWORK, ServiceSettings } from "@/components/steward/settings"
import { Failure, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

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
      <PageHeader title="Season" />

      <QueryState query={season}>
        {(current) => (
          <>
            <PhaseCard season={current} />
            <DatesCard season={current} />
            <Alert>
              <ShieldWarningIcon aria-hidden />
              <AlertTitle>Nothing is carried between seasons.</AlertTitle>
              <AlertDescription>
                Every season is a full rebuild: every service, every database, every config from scratch. That is why
                there is deliberately no button here that ends a season - it is not a switch, it is a build.
              </AlertDescription>
            </Alert>
          </>
        )}
      </QueryState>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold text-foreground">Network</h2>
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
  const current = PHASES.find((phase) => phase.name === season?.phase)

  function confirm() {
    if (!asked) return
    const phase = asked
    change.mutate(
      { phase, reason },
      {
        onSuccess: () => {
          toast.success(`Phase is now ${PHASES.find((p) => p.name === phase)?.label ?? phase}.`)
          setAsked(null)
          setReason("")
        },
      },
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FlagIcon className="size-4 text-muted-foreground" aria-hidden />
          Phase
        </CardTitle>
        <CardDescription>
          {season ? (
            <>
              <span className="font-medium text-foreground">{current?.label ?? season.phase}</span>{" "}
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
                  <span className="text-sm font-medium">{phase.label}</span>
                  {active ? <Badge variant="secondary">now</Badge> : null}
                  {season ? null : <Skeleton className="h-5 w-10 rounded-full" />}
                  <span className="font-mono text-xs text-muted-foreground">{phase.name}</span>
                </div>
                <p className="max-w-prose text-sm text-muted-foreground">{phase.who}</p>
                <p className="text-sm text-muted-foreground">
                  Players land on: <span className="font-mono">{phase.where}</span>
                </p>
              </div>
              <Button
                type="button"
                variant={active ? "ghost" : "outline"}
                size="sm"
                disabled={!season || active || change.isPending}
                onClick={() => setAsked(phase.name)}
              >
                {active ? "current" : "Switch"}
              </Button>
            </div>
          )
        })}
      </CardContent>

      {/* The reason is cleared with the dialog, or the next confirmation would send a cancelled one. */}
      <ResponsiveAlertDialog
        open={asked !== null}
        onOpenChange={(open) => {
          if (open) return
          setAsked(null)
          setReason("")
        }}
      >
        <ResponsiveAlertDialogContent>
          <ResponsiveAlertDialogHeader>
            <ResponsiveAlertDialogTitle>
              Switch the phase to "{PHASES.find((phase) => phase.name === asked)?.label}"?
            </ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription>
              {PHASES.find((phase) => phase.name === asked)?.who} The change applies from the next join - players
              already on the network are not moved.
            </ResponsiveAlertDialogDescription>
          </ResponsiveAlertDialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor="phase-reason">Reason</Label>
            <Input
              id="phase-reason"
              value={reason}
              placeholder="ends up in the journal"
              onChange={(event) => setReason(event.target.value)}
            />
            <p className="text-sm text-muted-foreground">
              The reason lands in the journal, together with your name. It may stay empty; then it only records who
              switched.
            </p>
          </div>
          {change.error ? <Failure error={change.error} /> : null}
          <ResponsiveAlertDialogFooter>
            <ResponsiveAlertDialogCancel disabled={change.isPending}>Cancel</ResponsiveAlertDialogCancel>
            <ResponsiveAlertDialogAction
              onClick={(event) => {
                event.preventDefault()
                confirm()
              }}
              disabled={change.isPending}
            >
              {change.isPending ? "Switching…" : "Switch"}
            </ResponsiveAlertDialogAction>
          </ResponsiveAlertDialogFooter>
        </ResponsiveAlertDialogContent>
      </ResponsiveAlertDialog>
    </Card>
  )
}

function DatesCard({ season }: { season?: Season }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CalendarDotIcon className="size-4 text-muted-foreground" aria-hidden />
          Dates
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <DateField
          which="launch"
          label="Network launch"
          note="What the countdown before launch counts towards."
          removal="The countdown and the start page go back to having no date. Nothing else moves."
          at={season?.launch}
          waiting={!season}
        />
        <DateField
          which="smpStart"
          label="SMP launch"
          note="When the season properly begins."
          removal="Access periods stay where they are, and the next date set moves every live one onto it - this is not an undo."
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
  note,
  removal,
  at,
  waiting,
}: {
  which: "launch" | "smpStart"
  label: string
  note: string
  /** What removing this date does, in one sentence. The two dates differ exactly here. */
  removal: string
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
      <p className="max-w-prose text-sm text-muted-foreground">{note}</p>
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
              { onSuccess: () => toast.success(`${label} saved.`) },
            )
          }
        >
          Save
        </Button>
        {/* Reset while something is typed, Remove otherwise, so the row never wraps on a phone. */}
        {dirty ? (
          <Button type="button" variant="ghost" size="sm" onClick={() => setLocal(toLocalInput(at))}>
            Reset
          </Button>
        ) : at && !waiting ? (
          <Button type="button" variant="ghost" size="sm" disabled={change.isPending} onClick={() => setRemoving(true)}>
            Remove
          </Button>
        ) : null}
      </div>
      <ResponsiveAlertDialog open={removing} onOpenChange={setRemoving}>
        <ResponsiveAlertDialogContent>
          <ResponsiveAlertDialogHeader>
            <ResponsiveAlertDialogTitle>Remove the {label.toLowerCase()} date?</ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription>{removal}</ResponsiveAlertDialogDescription>
          </ResponsiveAlertDialogHeader>
          {change.error ? <Failure error={change.error} /> : null}
          <ResponsiveAlertDialogFooter>
            <ResponsiveAlertDialogCancel disabled={change.isPending}>Cancel</ResponsiveAlertDialogCancel>
            <ResponsiveAlertDialogAction
              variant="destructive"
              disabled={change.isPending}
              onClick={(event) => {
                event.preventDefault()
                change.mutate(
                  { which, at: null },
                  {
                    onSuccess: () => {
                      setLocal("")
                      setRemoving(false)
                      toast.success(`${label} removed.`)
                    },
                  },
                )
              }}
            >
              {change.isPending ? "Removing…" : "Remove"}
            </ResponsiveAlertDialogAction>
          </ResponsiveAlertDialogFooter>
        </ResponsiveAlertDialogContent>
      </ResponsiveAlertDialog>
      {waiting ? (
        <SkeletonText className="text-sm" width="long" />
      ) : (
        <p className="text-sm text-muted-foreground">
          {at ? `Saved: ${dateTime(at)} (${relative(at)})` : "No date set yet."}
        </p>
      )}
      {change.error && !removing ? <Failure error={change.error} /> : null}
    </div>
  )
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
