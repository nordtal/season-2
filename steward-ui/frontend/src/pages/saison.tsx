import { useState } from "react"
import { CalendarClock, Flag, ShieldAlert } from "lucide-react"
import { toast } from "sonner"

import type { Season } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { useSeason, useSetPhase, useSetSeasonDate } from "@/lib/queries"
import { CommandCard } from "@/components/steward/command-card"
import { PageHeader } from "@/components/steward/page-header"
import { Failure, QueryState } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

/**
 * The season: which phase the network is in, and the two dates that hang off it.
 *
 * **The phase is not a label, it is the door policy.** It decides who may join and where they
 * land, and every process reads it out of one row rather than caching it - so a switch made here
 * is in force on the next join, with no restart anywhere. That is also why it is the one setting
 * in this interface that asks twice before it changes.
 */

type PhaseName = "PRE_LAUNCH" | "PRE_EVENT" | "START_EVENT" | "SMP" | "MAINTENANCE"

/**
 * What each phase actually does, in the words of `SeasonPhase` in :common.
 *
 * Kept as the admission rule rather than as a description, because "Vorbereitung" tells nobody
 * whether their players can log in and "nur Admins" does.
 */
const PHASES: { name: PhaseName; label: string; who: string; where: string }[] = [
  {
    name: "PRE_LAUNCH",
    label: "Vor dem Start",
    who: "Nur Admins. Alle anderen sehen einen Countdown auf den Starttermin.",
    where: "—",
  },
  {
    name: "PRE_EVENT",
    label: "Vor dem Event",
    who: "Jedes verknüpfte, nicht gesperrte Discord-Mitglied. Ein Beitragszeitraum ist nicht nötig.",
    where: "hunger-games",
  },
  {
    name: "START_EVENT",
    label: "Startevent",
    who: "Wie „Vor dem Event“.",
    where: "hunger-games",
  },
  {
    name: "SMP",
    label: "Saison läuft",
    who: "Verknüpft, nicht gesperrt – und als einzige Phase zusätzlich mit laufendem Zugang.",
    where: "smp",
  },
  {
    name: "MAINTENANCE",
    label: "Wartung",
    who: "Alle kommen ins Netzwerk und bleiben in limbo; Admins werden nicht umgeleitet.",
    where: "limbo",
  },
]

export function SaisonPage() {
  const season = useSeason()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Saison"
        note="Die Phase entscheidet, wer hereinkommt und wo er landet. Sie gilt sofort, ohne Neustart."
      />

      <QueryState query={season} rows={4}>
        {(current) => (
          <>
            <PhaseCard season={current} />
            <DatesCard season={current} />
            <CommandCard />
            <Alert>
              <ShieldAlert aria-hidden />
              <AlertTitle>Zwischen Saisons wird nichts übernommen.</AlertTitle>
              <AlertDescription>
                Jede Saison ist ein vollständiger Neuaufbau: jeder Dienst, jede Datenbank, jede
                Konfiguration von vorn. Es gibt hier deshalb bewusst keinen Knopf, der eine Saison
                beendet – das ist kein Schalter, sondern ein Aufbau.
              </AlertDescription>
            </Alert>
          </>
        )}
      </QueryState>
    </div>
  )
}

function PhaseCard({ season }: { season: Season }) {
  const [asked, setAsked] = useState<PhaseName | null>(null)
  const [reason, setReason] = useState("")
  const change = useSetPhase()
  const current = PHASES.find((phase) => phase.name === season.phase)

  function confirm() {
    if (!asked) return
    const phase = asked
    change.mutate(
      { phase, reason },
      {
        onSuccess: () => {
          toast.success(`Phase ist jetzt ${PHASES.find((p) => p.name === phase)?.label ?? phase}.`)
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
          <Flag className="size-4 text-muted-foreground" aria-hidden />
          Phase
        </CardTitle>
        <CardDescription>
          Aktuell: <span className="font-medium text-foreground">{current?.label ?? season.phase}</span>{" "}
          <span className="font-mono text-xs">({season.phase})</span>
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {/*
          Only while the dialog is shut. A refused switch leaves it open - `setAsked(null)` is in
          `onSuccess` and nowhere else - so this card is behind a modal at exactly the moment it
          has something to say, and Radix marks everything out here aria-hidden on top of that.
          The refusal is repeated inside the dialog; see below. It is the same mistake command-card
          was just corrected for, one file over.
        */}
        {change.error && asked === null ? <Failure error={change.error} /> : null}

        {PHASES.map((phase) => {
          const active = phase.name === season.phase
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
                  {active ? <Badge variant="secondary">aktuell</Badge> : null}
                  <span className="font-mono text-xs text-muted-foreground">{phase.name}</span>
                </div>
                <p className="max-w-prose text-sm text-muted-foreground">{phase.who}</p>
                <p className="text-sm text-muted-foreground">
                  Spieler landen auf: <span className="font-mono">{phase.where}</span>
                </p>
              </div>
              <Button
                type="button"
                variant={active ? "ghost" : "outline"}
                size="sm"
                disabled={active || change.isPending}
                onClick={() => setAsked(phase.name)}
              >
                {active ? "läuft" : "Umschalten"}
              </Button>
            </div>
          )
        })}
      </CardContent>

      {/*
        The reason is cleared with the dialog, not only after a successful switch. A cancelled
        sentence left in the field is not a harmless leftover: the next confirmation sends it, and
        the journal then records the reason for a phase change that nobody gave it.
      */}
      <AlertDialog
        open={asked !== null}
        onOpenChange={(open) => {
          if (open) return
          setAsked(null)
          setReason("")
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Phase auf „{PHASES.find((phase) => phase.name === asked)?.label}“ umschalten?
            </AlertDialogTitle>
            <AlertDialogDescription>
              {PHASES.find((phase) => phase.name === asked)?.who} Die Änderung gilt ab dem nächsten
              Beitritt – Spieler, die bereits im Netzwerk sind, werden nicht verschoben.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor="phase-reason">Grund</Label>
            <Input
              id="phase-reason"
              value={reason}
              placeholder="steht später im Journal"
              onChange={(event) => setReason(event.target.value)}
            />
            <p className="text-sm text-muted-foreground">
              Der Grund landet im Journal, zusammen mit deinem Namen. Er darf leer bleiben; dann
              steht dort nur, wer umgeschaltet hat.
            </p>
          </div>
          {change.error ? <Failure error={change.error} /> : null}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={change.isPending}>Abbrechen</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault()
                confirm()
              }}
              disabled={change.isPending}
            >
              {change.isPending ? "Wird umgeschaltet…" : "Umschalten"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  )
}

function DatesCard({ season }: { season: Season }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CalendarClock className="size-4 text-muted-foreground" aria-hidden />
          Termine
        </CardTitle>
        <CardDescription>
          Zwei Zeitpunkte, und keiner von beiden schaltet selbst um. Der Countdown vor dem Start
          zeigt den ersten an; wer hereindarf, bleibt eine Entscheidung und kein Zeitstempel.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <DateField
          which="launch"
          label="Start des Netzwerks"
          note="Worauf der Countdown in „Vor dem Start“ zählt."
          at={season.launch}
        />
        <DateField
          which="smpStart"
          label="Start der SMP"
          note="Wann die Saison richtig losgeht."
          at={season.smpStart}
        />
      </CardContent>
    </Card>
  )
}

function DateField({
  which,
  label,
  note,
  at,
}: {
  which: "launch" | "smpStart"
  label: string
  note: string
  at?: string
}) {
  const [local, setLocal] = useState(toLocalInput(at))
  const change = useSetSeasonDate()
  const dirty = local !== toLocalInput(at)

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={which}>{label}</Label>
      <p className="max-w-prose text-sm text-muted-foreground">{note}</p>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          id={which}
          type="datetime-local"
          className="w-auto"
          value={local}
          onChange={(event) => setLocal(event.target.value)}
        />
        <Button
          type="button"
          size="sm"
          disabled={!dirty || local === "" || change.isPending}
          onClick={() =>
            change.mutate(
              { which, at: new Date(local).toISOString() },
              { onSuccess: () => toast.success(`${label} gespeichert.`) },
            )
          }
        >
          Speichern
        </Button>
        {dirty ? (
          <Button type="button" variant="ghost" size="sm" onClick={() => setLocal(toLocalInput(at))}>
            Zurücksetzen
          </Button>
        ) : null}
      </div>
      <p className="text-sm text-muted-foreground">
        {at ? `Gespeichert: ${dateTime(at)} (${relative(at)})` : "Noch kein Termin gesetzt."}
      </p>
      {change.error ? <Failure error={change.error} /> : null}
    </div>
  )
}

/**
 * An instant as `datetime-local` wants it: local wall-clock, no zone, minute precision.
 *
 * Done by hand rather than with `toISOString().slice(0, 16)`, which is the obvious version and is
 * wrong - that string is UTC, so in Berlin it would show a time one or two hours off and save it
 * back as the time it showed.
 */
function toLocalInput(at?: string): string {
  if (!at) return ""
  const date = new Date(at)
  if (Number.isNaN(date.getTime())) return ""
  const pad = (value: number) => String(value).padStart(2, "0")
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}
