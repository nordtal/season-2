import { Link } from "@tanstack/react-router"
import { Fingerprint, LogOut, SlidersHorizontal, UserRound } from "lucide-react"

import { api } from "@/lib/api"
import { useMe, useSettings } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"

/**
 * Steward itself: who is signed in, how, and on what numbers the Ampel fires.
 *
 * **Nothing here is edited in place.** The thresholds are keys in `steward-ui.yml`, and that file
 * already has a form on the Konfiguration page - a second form over the same three values would be
 * two places to change one number, which is one place too many. This page shows them and links
 * there.
 */
export function EinstellungenPage() {
  const me = useMe()
  const settings = useSettings()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Einstellungen"
        note="Wer angemeldet ist, womit – und die beiden Schwellen, ab denen die Ampel gelb wird."
      />

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UserRound className="size-4 text-muted-foreground" aria-hidden />
            Angemeldet
          </CardTitle>
          <CardDescription>
            Die Sitzung liegt im Arbeitsspeicher dieses Containers. Ein Neustart von steward-ui
            beendet sie – das ist kein Fehler, sondern die Kehrseite davon, keinen Sitzungsspeicher
            sichern zu müssen.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <QueryState query={me} rows={2}>
            {(who) => (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex min-w-0 flex-col gap-1">
                  <span className="text-sm font-medium">{who.name ?? "unbekannt"}</span>
                  <span className="font-mono text-xs text-muted-foreground">
                    Discord {who.id ?? "—"}
                  </span>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={async () => {
                    await api<void>("/auth/logout", { method: "POST" })
                    window.location.assign("/")
                  }}
                >
                  <LogOut aria-hidden />
                  Abmelden
                </Button>
              </div>
            )}
          </QueryState>

          <Separator />

          <Alert>
            <Fingerprint aria-hidden />
            <AlertTitle>Diese Alpha kennt keinen zweiten Faktor.</AlertTitle>
            <AlertDescription>
              {me.data?.webauthn ??
                "Es gibt kein Passwort, keinen Sicherheitsschlüssel und keine zweite Identität: wer die Discord-Sitzung eines Admins hat, hat diese Oberfläche."}{" "}
              Deshalb landet jede Änderung, die hier gemacht wird, im{" "}
              <Link to="/journal" className="underline underline-offset-4">
                Journal
              </Link>
              .
            </AlertDescription>
          </Alert>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <SlidersHorizontal className="size-4 text-muted-foreground" aria-hidden />
            Schwellen der Ampel
          </CardTitle>
          <CardDescription>
            Zwei davon sind Geschmackssache und stehen hier. Die anderen beiden Auslöser – ein
            Dienst, der nicht läuft, und eine fehlende Sicherung – sind nicht einstellbar und sollen
            es nicht sein. Eine stehende SMP ist keine Vorliebe.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <QueryState query={settings} rows={3}>
            {(thresholds) => (
              <>
                <Threshold
                  label="Platte belegt"
                  value={`ab ${thresholds.disk} %`}
                  note="Gelb, sobald die Platte voller ist als das."
                />
                <Threshold
                  label="Arbeitsspeicher belegt"
                  value={`ab ${thresholds.memory} %`}
                  note="Kein Container im Stack setzt ein Limit, also ist das der Anteil an der ganzen Maschine."
                />
                <Threshold
                  label="Alter der neuesten Sicherung"
                  value={`ab ${thresholds.backupAgeHours} Stunden`}
                  note="Rot. Gezählt werden Dateien auf der Platte, nicht Läufe, die Erfolg gemeldet haben."
                />
              </>
            )}
          </QueryState>

          <p className="text-sm text-muted-foreground">
            Geändert werden sie in{" "}
            <Link
              to="/konfiguration/$"
              params={{ _splat: "steward-ui/steward-ui.yml" }}
              className="font-mono underline underline-offset-4"
            >
              steward-ui.yml
            </Link>
            , Abschnitt „Alerts“. Eine Änderung greift beim nächsten Start von steward-ui.
          </p>
        </CardContent>
      </Card>
    </div>
  )
}

function Threshold({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3 last:border-0 last:pb-0">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-sm font-medium">{label}</span>
        <span className="max-w-prose text-sm text-muted-foreground">{note}</span>
      </div>
      <Badge variant="secondary" className="shrink-0 tabular-nums">
        {value}
      </Badge>
    </div>
  )
}
