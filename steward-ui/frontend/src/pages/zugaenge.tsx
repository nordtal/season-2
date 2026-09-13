import { useState } from "react"
import { Link } from "@tanstack/react-router"
import {
  CircleAlert,
  ExternalLink,
  KeyRound,
  Search,
  ShieldCheck,
  ShieldX,
  TriangleAlert,
  UserPlus,
} from "lucide-react"
import { toast } from "sonner"

import type { Grant, JournalEntry, Payment, Person } from "@/lib/api"
import { count, dateTime, euros, relative } from "@/lib/format"
import {
  useGrantAccess,
  useGrants,
  useJournal,
  useMe,
  usePayments,
  usePeople,
  useRevokeAccess,
} from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, Failure, Loading, QueryState } from "@/components/steward/query-state"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * The four pages of the access half (concept §10a.7): who may join, what they paid, which accounts
 * are one person, and what an admin did about it.
 *
 * They are one file because they are one chain, read left to right: **Anfrage → Tab → gezahlt →
 * Zugang → verknüpft**. Zahlungen is the first three links, Zugänge the fourth, Konten the fifth,
 * and Journal is the record of every hand that reached into any of them. Splitting them into four
 * files would put the vocabulary that has to agree - what "aktiv" means, what a revoked period
 * looks like - in four places.
 *
 * **Nothing here is computed that the database does not answer.** Where a number would be a guess
 * the page says so in a sentence instead of printing it: the amount of a paid request is what the
 * tab asked for and not what arrived, and a person whose access is inactive while their latest
 * period still runs may have been revoked *or* may be waiting for the SMP to open. Both are written
 * out below rather than quietly rounded into a badge.
 */

// --- the vocabulary the four pages share ----------------------------------------------------------

/**
 * Guild membership.
 *
 * `MEMBER` is deliberately the quiet one. Green would spend a status colour on the ordinary case
 * and leave nothing louder for the two cases an admin is actually scanning for - and a ban does not
 * stop a paid period running down, it only refuses the login right now.
 */
const MEMBER_STATES: Record<string, { label: string; tone: Tone; title: string }> = {
  MEMBER: { label: "Mitglied", tone: "idle", title: "In der Gilde, wie der Bot sie zuletzt sah." },
  LEFT: {
    label: "ausgetreten",
    tone: "warn",
    title:
      "Nicht mehr in der Gilde. Ein gekaufter Zeitraum läuft trotzdem weiter – er wird nicht angehalten.",
  },
  BANNED: {
    label: "gebannt",
    tone: "down",
    title:
      "In Discord gebannt. Der Login wird abgelehnt, solange das gilt; der bezahlte Zeitraum läuft dabei weiter ab.",
  },
}

function MemberBadge({ state }: { state: string }) {
  const known = MEMBER_STATES[state]
  // An unknown value is shown, not swallowed: `member_state` has a CHECK constraint today, and a
  // page that printed nothing for a value added tomorrow would look empty rather than new.
  if (!known) {
    return (
      <StatusBadge tone="idle" title="Diesen Mitgliedszustand kennt diese Oberfläche nicht.">
        {state}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone={known.tone} title={known.title}>
      {known.label}
    </StatusBadge>
  )
}

/**
 * Access, from the two fields that are deliberately not one.
 *
 * `accessActive` is the login decision - a revoked grant never counts, not even inside its own
 * window. `accessUntil` is the end of the latest period *on record*, revoked ones included. Keeping
 * both is what makes "dem wurde der Zugang genommen" visible at all: without the date, somebody
 * whose access was taken away would look exactly like somebody who never bought any.
 *
 * The third case - inactive, but the latest period still lies in the future - has two possible
 * causes and this badge does not pretend to know which: revoked, or bought before the SMP opened
 * and therefore not yet started. Both are named in the tooltip, and the per-person dialog answers
 * it for certain, because a grant carries its own `revoked`.
 */
function AccessBadge({ person, now }: { person: Person; now: number }) {
  const until = person.accessUntil ? new Date(person.accessUntil).getTime() : null

  if (person.accessActive) {
    return (
      <StatusBadge tone="ok" title={`Ein nicht entzogener Zeitraum deckt gerade jetzt ab.`}>
        aktiv bis {dateTime(person.accessUntil)}
      </StatusBadge>
    )
  }
  if (until === null) {
    return (
      <StatusBadge tone="idle" title="Für dieses Konto ist noch nie ein Zeitraum geschrieben worden.">
        nie
      </StatusBadge>
    )
  }
  if (until > now) {
    return (
      <StatusBadge
        tone="down"
        title={
          "Kein gültiger Zeitraum deckt jetzt ab, obwohl der letzte auf dem Papier noch bis " +
          dateTime(person.accessUntil) +
          " läuft. Das heißt entweder entzogen – oder gekauft, bevor der SMP geöffnet hat, und damit noch nicht begonnen. Welches von beidem, steht in den Zeiträumen dieser Person."
        }
      >
        kein Zugang · Zeitraum bis {dateTime(person.accessUntil)}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="idle" title="Der letzte Zeitraum ist abgelaufen.">
      abgelaufen {relative(person.accessUntil, now)}
    </StatusBadge>
  )
}

/**
 * The fifth link of the chain.
 *
 * Paid but unlinked is the one combination worth a warning colour: that person has spent money and
 * still cannot join, because the proxy knows Minecraft accounts and not Discord ones.
 */
function LinkBadge({ person }: { person: Person }) {
  if (person.minecraftUuid) {
    return (
      <StatusBadge tone="idle" title={`Verknüpft ${dateTime(person.linked)}.`}>
        verknüpft
      </StatusBadge>
    )
  }
  return (
    <StatusBadge
      tone={person.accessActive ? "warn" : "idle"}
      title={
        person.accessActive
          ? "Zugang bezahlt, aber kein Minecraft-Konto verknüpft – diese Person kommt nicht auf den Server, bis sie den Code aus dem Loginbildschirm in Discord eintippt."
          : "Kein Minecraft-Konto verknüpft."
      }
    >
      nicht verknüpft
    </StatusBadge>
  )
}

const GRANT_SOURCES: Record<string, string> = {
  PURCHASE: "Kauf",
  ADMIN: "von Hand",
}

/** Where a period stands right now, judged from the row itself rather than from the roster. */
function grantTone(grant: Grant, now: number): { label: string; tone: Tone; title: string } {
  if (grant.revoked) {
    return {
      label: `entzogen ${dateTime(grant.revoked)}`,
      tone: "down",
      title: "Ein entzogener Zeitraum zählt nie, auch nicht innerhalb seines eigenen Fensters.",
    }
  }
  const from = new Date(grant.validFrom).getTime()
  const until = new Date(grant.validUntil).getTime()
  if (from > now) {
    return {
      label: `beginnt ${relative(grant.validFrom, now)}`,
      tone: "idle",
      title: "Gekauft, aber noch nicht begonnen – ein Zeitraum wird hinten angehängt, nie überschrieben.",
    }
  }
  if (until > now) {
    return { label: "läuft", tone: "ok", title: "Dieser Zeitraum deckt gerade jetzt ab." }
  }
  return { label: "abgelaufen", tone: "idle", title: "Dieser Zeitraum liegt vollständig hinter uns." }
}

/** A uuid or a request id, short enough for a cell and complete in the title attribute. */
function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

// --- 1. /zugaenge ---------------------------------------------------------------------------------

/**
 * The roster: everyone the bot knows, and what they may.
 *
 * The two writes on this page are the reason it needs a paragraph of its own. Granting and revoking
 * used to be `/access` in Discord and nothing else; since 2026-09-13 this is a second door into the
 * same room, and the price of a second door is that "wer hat den reingelassen" has to stay
 * answerable. It does, because every click here writes an `audit_log` row naming the admin - which
 * is exactly what the Journal page shows.
 */
export function ZugaengePage() {
  const people = usePeople()
  const [needle, setNeedle] = useState("")
  const [onlyWithAccess, setOnlyWithAccess] = useState(false)
  const [selected, setSelected] = useState<Person | null>(null)
  // One clock for the whole render, so that two badges in one row cannot disagree about "jetzt".
  const now = Date.now()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Zugänge"
        note="Wer auf den Server darf, und warum er das darf – Anfrage, Zahlung, Zeitraum, Verknüpfung."
        actions={<GrantDialog />}
      />

      <SecondDoorNote />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Personen</CardTitle>
          <CardDescription>
            Aus <code className="text-xs">discord_user</code>, mit Verknüpfung und Zugang daneben.
            Geladen werden die zuletzt geänderten 500 Konten; gefiltert wird in dieser Liste, nicht
            in der Datenbank.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-4">
            <div className="flex min-w-64 flex-1 items-center gap-2">
              <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={needle}
                onChange={(event) => setNeedle(event.target.value)}
                placeholder="Discord-ID filtern…"
                aria-label="Discord-ID filtern"
                autoComplete="off"
              />
            </div>
            <div className="flex items-center gap-2">
              <Switch
                id="only-with-access"
                checked={onlyWithAccess}
                onCheckedChange={setOnlyWithAccess}
              />
              <Label htmlFor="only-with-access">nur mit Zugang</Label>
            </div>
          </div>

          <QueryState
            query={people}
            rows={8}
            empty={{
              title: "Noch niemand",
              note: "Der Bot hat noch kein Discord-Konto gesehen – oder er läuft nicht.",
            }}
            isEmpty={(list: Person[]) => list.length === 0}
          >
            {(list) => {
              const rows = list.filter(
                (person) =>
                  (!onlyWithAccess || person.accessActive) &&
                  (needle.trim() === "" || person.discordId.includes(needle.trim())),
              )
              if (rows.length === 0) {
                return (
                  <Empty
                    title="Keine Person passt"
                    note={
                      onlyWithAccess
                        ? "Mit diesem Filter und „nur mit Zugang“ bleibt niemand übrig."
                        : "Kein geladenes Konto enthält diese Zeichenfolge in seiner Discord-ID."
                    }
                  />
                )
              }
              return (
                <>
                  <Table className="steward-table">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-[14rem]">Discord-ID</TableHead>
                        <TableHead className="w-[8rem]">Gilde</TableHead>
                        <TableHead className="w-[20rem]">
                          <AccessColumnHead />
                        </TableHead>
                        <TableHead className="w-[9rem]">Minecraft</TableHead>
                        <TableHead className="w-[9rem]">Rollen</TableHead>
                        <TableHead className="w-[11rem]" />
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {rows.map((person) => (
                        <TableRow key={person.discordId}>
                          <TableCell className="font-medium">
                            {/* A button rather than a clickable row: the row also carries a
                             * destructive action, and „ich wollte nur nachsehen" must not be one
                             * misplaced click away from it. */}
                            <button
                              type="button"
                              onClick={() => setSelected(person)}
                              className="underline-offset-4 hover:text-primary hover:underline"
                            >
                              {person.discordId}
                            </button>
                          </TableCell>
                          <TableCell>
                            <MemberBadge state={person.memberState} />
                          </TableCell>
                          <TableCell>
                            <AccessBadge person={person} now={now} />
                          </TableCell>
                          <TableCell>
                            <LinkBadge person={person} />
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-1">
                              {person.donor ? (
                                <StatusBadge
                                  tone="idle"
                                  title="Einmal vergeben, nie wieder entzogen – deshalb ist das Handvergeben der Rolle in Discord gefahrlos."
                                >
                                  Unterstützer
                                </StatusBadge>
                              ) : null}
                              {person.admin ? (
                                <StatusBadge
                                  tone="idle"
                                  title="Spiegelt die Discord-Adminrolle. Verschwindet die Rolle, verschwindet dieses Kennzeichen wieder."
                                >
                                  Admin
                                </StatusBadge>
                              ) : null}
                              {!person.donor && !person.admin ? (
                                <span className="text-xs text-muted-foreground">–</span>
                              ) : null}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center justify-end gap-1">
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                onClick={() => setSelected(person)}
                              >
                                Zeiträume
                              </Button>
                              {/* No greyed-out button for somebody without access: there is
                               * nothing to take away, and a disabled destructive control reads as
                               * "not allowed" rather than "not applicable". */}
                              {person.accessActive ? <RevokeDialog person={person} /> : null}
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  <p className="text-xs text-muted-foreground">
                    {count(rows.length)} von {count(list.length)} geladenen Konten.
                  </p>
                </>
              )
            }}
          </QueryState>
        </CardContent>
      </Card>

      <Dialog open={selected !== null} onOpenChange={(open) => (open ? null : setSelected(null))}>
        <DialogContent className="max-w-2xl">
          {selected ? <PersonGrants person={selected} now={now} /> : null}
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** The one paragraph that justifies this page being allowed to write at all. */
function SecondDoorNote() {
  return (
    <div className="flex items-start gap-3 rounded-md border border-border bg-card px-4 py-3">
      <KeyRound className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
      <p className="max-w-prose text-sm text-muted-foreground">
        Dies ist die <span className="text-foreground">zweite Tür in denselben Raum</span>:{" "}
        <code className="text-xs">/access</code> in Discord ist die erste, und beide schreiben in
        dieselben Tabellen. Bezahlt wird diese zweite Tür im Protokoll – jedes Erteilen und jedes
        Entziehen von hier schreibt eine Zeile ins{" "}
        <Link to="/journal" className="text-primary underline-offset-4 hover:underline">
          Journal
        </Link>
        , die den Admin nennt, der geklickt hat.
      </p>
    </div>
  )
}

/** The header of the access column, with the reason its two halves disagree. */
function AccessColumnHead() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="underline decoration-dotted underline-offset-4">
          Zugang
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">
        Zwei Angaben, absichtlich nicht eine: ob gerade jetzt ein nicht entzogener Zeitraum abdeckt –
        und wann der letzte Zeitraum endet, entzogene eingerechnet. Ohne das zweite sähe jemand, dem
        der Zugang genommen wurde, genauso aus wie jemand, der nie einen hatte.
      </TooltipContent>
    </Tooltip>
  )
}

/**
 * Granting, with the arithmetic named out loud.
 *
 * The rules are the database's, not this form's: a day is exactly 24 hours, a new period is
 * appended behind a running one instead of replacing it, and a purchase made before the SMP opens
 * starts on the opening day. Writing them here is the only way the person clicking can predict what
 * the row will say afterwards.
 */
function GrantDialog() {
  const grant = useGrantAccess()
  const [discordId, setDiscordId] = useState("")
  const [days, setDays] = useState("30")
  const parsedDays = Number.parseInt(days, 10)
  const usable = discordId.trim().length > 0 && Number.isFinite(parsedDays) && parsedDays > 0

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button">
          <UserPlus aria-hidden />
          Zugang erteilen
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Zugang von Hand erteilen</AlertDialogTitle>
          <AlertDialogDescription>
            Schreibt einen Zeitraum mit der Quelle <code className="text-xs">ADMIN</code> – ohne
            Zahlung, ohne bunq-Tab. Die Person darf danach auf den Server, sobald ihr
            Minecraft-Konto verknüpft ist.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="grant-discord-id">Discord-ID</Label>
            <Input
              id="grant-discord-id"
              value={discordId}
              onChange={(event) => setDiscordId(event.target.value)}
              placeholder="z. B. 214906139328839681"
              className="font-mono"
              autoComplete="off"
              spellCheck={false}
              inputMode="numeric"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="grant-days">Tage</Label>
            <Input
              id="grant-days"
              value={days}
              onChange={(event) => setDays(event.target.value)}
              type="number"
              min={1}
              className="w-32"
            />
          </div>
          <ul className="flex list-disc flex-col gap-1 pl-4 text-sm text-muted-foreground">
            <li>Ein Tag sind genau 24 Stunden, nicht ein Kalendertag.</li>
            <li>
              Läuft schon ein Zeitraum, wird der neue hinten angehängt – bezahlte Zeit geht nie
              verloren, und Zeiträume werden nie summiert, wenn dazwischen eine Lücke lag.
            </li>
            <li>
              Ist der SMP-Start noch nicht erreicht, beginnt der Zeitraum an diesem Termin und nicht
              heute.
            </li>
            <li>
              Kennt der Bot diese Discord-ID noch nicht, wird das Konto dafür angelegt. Eine
              vertippte ID erzeugt also eine Person, die es nicht gibt – und keinen Fehler.
            </li>
          </ul>
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            disabled={!usable || grant.isPending}
            onClick={() => {
              grant.mutate(
                { discordId: discordId.trim(), days: parsedDays },
                {
                  onSuccess: (written: Grant) => {
                    toast.success(`Zugang für ${written.discordId} erteilt`, {
                      description: `Gültig ${dateTime(written.validFrom)} bis ${dateTime(
                        written.validUntil,
                      )}. Eine Journalzeile nennt dich.`,
                    })
                    setDiscordId("")
                  },
                  onError: (error) => {
                    toast.error("Es wurde kein Zugang erteilt", { description: String(error) })
                  },
                },
              )
            }}
          >
            Erteilen
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/**
 * Revoking - the destructive half.
 *
 * It takes the whole remaining run and not one period: that is what the backend's single statement
 * does, and it is what lets the login path get away with one `max(valid_until)`. Saying "alle
 * laufenden Zeiträume" here is therefore accurate and not a simplification.
 */
function RevokeDialog({ person }: { person: Person }) {
  const revoke = useRevokeAccess()

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant="ghost" size="sm" className="text-destructive">
          <ShieldX aria-hidden />
          Entziehen
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Zugang entziehen?</AlertDialogTitle>
          <AlertDialogDescription>
            Entzogen wird der <span className="text-foreground">gesamte verbleibende Lauf</span> von{" "}
            <span className="font-mono text-foreground">{person.discordId}</span> – jeder noch nicht
            abgelaufene Zeitraum auf einmal, nicht ein einzelner.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="flex flex-col gap-3 text-sm">
          <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-warning">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
            Wer gerade spielt, fliegt heraus: der Proxy prüft den Zugang jedes verbundenen Spielers
            regelmäßig nach und trennt, sobald er nicht mehr gilt – nicht erst beim nächsten Login.
          </p>
          <p className="text-muted-foreground">
            Bezahlte Zeit kommt dadurch nicht zurück. Ein späteres Erteilen beginnt neu und rechnet
            den entzogenen Rest nicht an.
          </p>
          <p className="text-muted-foreground">
            Der Eintrag bleibt stehen und wird nur als entzogen markiert – deshalb steht in der
            Liste weiter ein Datum neben „kein Zugang“, statt dass die Person wie eine Fremde
            aussieht.
          </p>
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={revoke.isPending}
            onClick={() => {
              revoke.mutate(person.discordId, {
                onSuccess: (result) => {
                  // Zero is a real answer and not a success: between opening this dialog and
                  // clicking, the run may have ended or somebody else may have revoked it.
                  if (result.revoked === 0) {
                    toast.warning("Es gab nichts zu entziehen", {
                      description: `Für ${person.discordId} lief kein Zeitraum mehr.`,
                    })
                    return
                  }
                  toast.success(
                    `${count(result.revoked)} Zeitraum/Zeiträume von ${person.discordId} entzogen`,
                    { description: "Eine Journalzeile nennt dich." },
                  )
                },
                onError: (error) => {
                  toast.error("Es wurde nichts entzogen", { description: String(error) })
                },
              })
            }}
          >
            Entziehen
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** One person's chain, period by period. Read-only: the two writes stand in the table row. */
function PersonGrants({ person, now }: { person: Person; now: number }) {
  const grants = useGrants(person.discordId)

  return (
    <>
      <DialogHeader>
        <DialogTitle className="font-mono">{person.discordId}</DialogTitle>
        <DialogDescription>
          Anfrage → Tab → gezahlt → Zugang → verknüpft. Hier steht das vierte Glied: jeder Zeitraum,
          seine Quelle und – beim Kauf – die Zahlungsanfrage, aus der er stammt.
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap gap-6">
        <Stat label="Gilde" value={<MemberBadge state={person.memberState} />} />
        <Stat
          label="Minecraft"
          value={
            person.minecraftUuid ? (
              <span className="font-mono text-sm" title={person.minecraftUuid}>
                {shortId(person.minecraftUuid)}
              </span>
            ) : (
              "–"
            )
          }
          hint={person.linked ? `verknüpft ${dateTime(person.linked)}` : "nicht verknüpft"}
        />
        <Stat
          label="Sprache"
          value={person.locale}
          hint={`zuletzt geändert ${relative(person.updated, now)}`}
        />
      </div>

      <Separator />

      {grants.isPending ? (
        <Loading rows={3} />
      ) : grants.error ? (
        <Failure error={grants.error} onRetry={grants.refetch} />
      ) : (grants.data ?? []).length === 0 ? (
        <Empty
          title="Kein Zeitraum"
          note="Für dieses Konto ist nie einer geschrieben worden – weder gekauft noch von Hand."
        />
      ) : (
        <Table className="steward-table">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[7rem]">Quelle</TableHead>
              <TableHead>Fenster</TableHead>
              <TableHead className="w-[13rem]">Stand</TableHead>
              <TableHead className="w-[8rem]">Anfrage</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(grants.data ?? []).map((row) => {
              const state = grantTone(row, now)
              return (
                <TableRow key={row.id}>
                  <TableCell>{GRANT_SOURCES[row.source] ?? row.source}</TableCell>
                  <TableCell className="text-muted-foreground tnum">
                    {dateTime(row.validFrom)} – {dateTime(row.validUntil)}
                  </TableCell>
                  <TableCell>
                    <StatusBadge tone={state.tone} title={state.title}>
                      {state.label}
                    </StatusBadge>
                  </TableCell>
                  <TableCell>
                    {row.paymentRequestId ? (
                      <span className="font-mono text-xs" title={row.paymentRequestId}>
                        {shortId(row.paymentRequestId)}
                      </span>
                    ) : (
                      <span
                        className="text-xs text-muted-foreground"
                        title={
                          row.source === "PURCHASE"
                            ? "Gekauft, aber die Zahlungsanfrage steht nicht mehr in der Datenbank – sie wird beim Löschen der Anfrage auf NULL gesetzt."
                            : "Von Hand erteilt, also gibt es keine Zahlungsanfrage."
                        }
                      >
                        –
                      </span>
                    )}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}

      <p className="text-sm text-muted-foreground">
        Entzogen wird in der Zeile dieser Person in der Tabelle – nicht hier, damit eine
        Sicherheitsabfrage nie in einem schon offenen Fenster steckt.
      </p>
    </>
  )
}

// --- 2. /zahlungen --------------------------------------------------------------------------------

const PAYMENT_STATES: Record<string, { label: string; tone: Tone; title: string }> = {
  OPEN: {
    label: "offen",
    tone: "idle",
    title: "Der Tab steht, bezahlt wurde noch nicht. Je Person kann nur eine Anfrage offen sein.",
  },
  PAID: { label: "bezahlt", tone: "ok", title: "Das Geld ist eingegangen und der Zeitraum steht." },
  EXPIRED: {
    label: "verfallen",
    tone: "idle",
    title: "Die Frist ist abgelaufen, ohne dass gezahlt wurde.",
  },
  CANCELLED: { label: "abgebrochen", tone: "idle", title: "Abgebrochen, bevor gezahlt wurde." },
  SUPERSEDED: {
    label: "ersetzt",
    tone: "idle",
    title: "Dieselbe Person hat eine neue Anfrage gestartet; diese wurde dabei geschlossen.",
  },
}

/** OPEN and past its `expires`: nobody is about to pay this, the sweep has just not run yet. */
function isOverdue(payment: Payment, now: number): boolean {
  return payment.status === "OPEN" && new Date(payment.expires).getTime() <= now
}

/**
 * The payments: the first three links of the chain.
 *
 * The sum at the top is the one number on these four pages that could mislead, so it is labelled
 * rather than printed bare: `amount_cents` is what the tab **asked for**, and the payer can edit the
 * amount on the bunq.me page. What actually arrived is not in this table at all.
 */
export function ZahlungenPage() {
  const payments = usePayments()
  const [status, setStatus] = useState("")
  const now = Date.now()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Zahlungen"
        note="Anfrage, bunq-Tab und Ausgang – die drei Glieder, aus denen ein gekaufter Zugang entsteht."
      />

      <QueryState
        query={payments}
        rows={8}
        empty={{
          title: "Keine Zahlungsanfrage",
          note: "Es hat noch niemand Zugang angefragt – oder der Bot läuft nicht.",
        }}
        isEmpty={(list: Payment[]) => list.length === 0}
      >
        {(list) => {
          const open = list.filter((payment) => payment.status === "OPEN")
          const overdue = open.filter((payment) => isOverdue(payment, now))
          const paid = list.filter((payment) => payment.status === "PAID")
          const requested = paid.reduce(
            (sum, payment) => sum + payment.amountCents + payment.donationCents,
            0,
          )
          // Built from what is here, plus the value being filtered on, so that a status added to
          // the CHECK constraint later still appears the moment one row carries it.
          const present = [...new Set(list.map((payment) => payment.status))].sort()
          const shown = list.filter((payment) => status === "" || payment.status === status)

          return (
            <>
              <Card>
                <CardContent className="flex flex-wrap items-start gap-8 pt-6">
                  <Stat
                    label="Offen"
                    value={count(open.length)}
                    hint={`davon ${count(overdue.length)} über die Frist hinaus`}
                    tone={overdue.length > 0 ? "warn" : undefined}
                  />
                  <Stat label="Bezahlt" value={count(paid.length)} />
                  <Separator orientation="vertical" className="h-14" />
                  <Stat
                    label="Angefragt (bezahlte Anfragen)"
                    value={euros(requested)}
                    hint="Betrag plus Spende, wie der Tab sie verlangt hat"
                  />
                  <p className="max-w-prose text-xs text-muted-foreground">
                    Das ist <span className="text-foreground">nicht der Kontostand</span>: auf der
                    bunq.me-Seite kann der zahlende Mensch den Betrag ändern, und was tatsächlich
                    ankam, steht in keiner dieser Spalten. Diese Oberfläche fragt bunq nicht – das
                    tut der Bot.
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-sm font-medium">Anfragen</CardTitle>
                  <CardDescription>
                    Aus <code className="text-xs">payment_request</code>, neueste zuerst. Geladen
                    werden die letzten 200.
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex flex-col gap-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <Label htmlFor="payment-status" className="text-muted-foreground">
                      Status
                    </Label>
                    <Select
                      value={status === "" ? "ALLE" : status}
                      onValueChange={(value) => setStatus(value === "ALLE" ? "" : value)}
                    >
                      <SelectTrigger id="payment-status" className="w-56">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ALLE">alle</SelectItem>
                        {present.map((value) => (
                          <SelectItem key={value} value={value}>
                            {PAYMENT_STATES[value]?.label ?? value}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {overdue.length > 0 ? (
                      <span className="flex items-center gap-2 text-xs text-warning">
                        <CircleAlert className="size-4 shrink-0" aria-hidden />
                        {count(overdue.length)} offene Anfrage(n) sind über ihre Frist hinaus – die
                        zahlt niemand mehr, sie warten nur auf den Aufräumlauf des Bots.
                      </span>
                    ) : null}
                  </div>

                  {shown.length === 0 ? (
                    <Empty
                      title="Keine Anfrage mit diesem Status"
                      note="Unter den geladenen Anfragen trägt keine diesen Status."
                    />
                  ) : (
                    <Table className="steward-table">
                      <TableHeader>
                        <TableRow>
                          <TableHead className="w-[9rem]">Referenz</TableHead>
                          <TableHead className="w-[13rem]">Person</TableHead>
                          <TableHead className="w-[5rem] text-right">Tage</TableHead>
                          <TableHead className="w-[7rem] text-right">Betrag</TableHead>
                          <TableHead className="w-[7rem] text-right">Spende</TableHead>
                          <TableHead className="w-[9rem]">Status</TableHead>
                          <TableHead className="w-[11rem]">Erstellt</TableHead>
                          <TableHead className="w-[11rem]">Frist</TableHead>
                          <TableHead className="w-[11rem]">Bezahlt</TableHead>
                          <TableHead className="w-[6rem]" />
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {shown.map((payment) => {
                          const state = PAYMENT_STATES[payment.status]
                          const late = isOverdue(payment, now)
                          return (
                            <TableRow key={payment.id}>
                              <TableCell className="font-mono font-medium">
                                {payment.reference}
                              </TableCell>
                              <TableCell className="font-mono text-muted-foreground">
                                {payment.discordId}
                              </TableCell>
                              <TableCell className="text-right tnum">{payment.days}</TableCell>
                              <TableCell className="text-right tnum">
                                {euros(payment.amountCents)}
                              </TableCell>
                              <TableCell className="text-right tnum text-muted-foreground">
                                {payment.donationCents > 0 ? euros(payment.donationCents) : "–"}
                              </TableCell>
                              <TableCell>
                                <div className="flex items-center gap-1">
                                  <StatusBadge
                                    tone={late ? "warn" : (state?.tone ?? "idle")}
                                    title={
                                      state?.title ??
                                      "Diesen Status kennt diese Oberfläche nicht."
                                    }
                                  >
                                    {state?.label ?? payment.status}
                                  </StatusBadge>
                                  {late ? (
                                    <StatusBadge
                                      tone="warn"
                                      title="Die Frist ist vorbei, der Status steht aber noch auf OPEN – der Aufräumlauf des Bots hat sie noch nicht angefasst."
                                    >
                                      überfällig
                                    </StatusBadge>
                                  ) : null}
                                </div>
                              </TableCell>
                              <TableCell className="text-muted-foreground tnum">
                                {dateTime(payment.created)}
                              </TableCell>
                              <TableCell className="text-muted-foreground tnum">
                                {dateTime(payment.expires)}
                              </TableCell>
                              <TableCell className="text-muted-foreground tnum">
                                {dateTime(payment.settled)}
                              </TableCell>
                              <TableCell>
                                {payment.shareUrl ? (
                                  <Button asChild variant="ghost" size="sm">
                                    <a
                                      href={payment.shareUrl}
                                      target="_blank"
                                      rel="noreferrer"
                                      title={payment.shareUrl}
                                    >
                                      <ExternalLink aria-hidden />
                                      Tab
                                    </a>
                                  </Button>
                                ) : (
                                  <span
                                    className="text-xs text-muted-foreground"
                                    title="Für diese Anfrage steht keine bunq.me-Adresse in der Zeile."
                                  >
                                    –
                                  </span>
                                )}
                              </TableCell>
                            </TableRow>
                          )
                        })}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
            </>
          )
        }}
      </QueryState>
    </div>
  )
}

// --- 3. /konten -----------------------------------------------------------------------------------

/**
 * The identities: one Discord account, at most one Minecraft account, and the session you are
 * reading this in.
 *
 * There is no third, Steward-owned identity - and the card at the top says so rather than leaving
 * the reader to assume one exists. §10a wants a security key after Discord; this alpha does not
 * have one, `/api/me` says so in its own words, and those words are printed here verbatim.
 */
export function KontenPage() {
  const people = usePeople()
  const [needle, setNeedle] = useState("")
  const [onlyLinked, setOnlyLinked] = useState(false)
  const now = Date.now()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Konten"
        note="Welches Minecraft-Konto zu welchem Discord-Konto gehört – und womit sich diese Oberfläche selbst ausweist."
      />

      <AuthenticationCard />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Verknüpfungen</CardTitle>
          <CardDescription>
            Aus <code className="text-xs">account_link</code>. Die Datenbank erzwingt 1:1 – eine
            Discord-ID kommt einmal vor, eine Minecraft-UUID auch.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-4">
            <div className="flex min-w-64 flex-1 items-center gap-2">
              <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={needle}
                onChange={(event) => setNeedle(event.target.value)}
                placeholder="Discord-ID oder UUID filtern…"
                aria-label="Discord-ID oder UUID filtern"
                autoComplete="off"
              />
            </div>
            <div className="flex items-center gap-2">
              <Switch id="only-linked" checked={onlyLinked} onCheckedChange={setOnlyLinked} />
              <Label htmlFor="only-linked">nur verknüpfte</Label>
            </div>
          </div>

          <QueryState
            query={people}
            rows={8}
            empty={{
              title: "Noch niemand",
              note: "Der Bot hat noch kein Discord-Konto gesehen – oder er läuft nicht.",
            }}
            isEmpty={(list: Person[]) => list.length === 0}
          >
            {(list) => {
              const trimmed = needle.trim().toLowerCase()
              const rows = list.filter(
                (person) =>
                  (!onlyLinked || person.minecraftUuid !== null) &&
                  (trimmed === "" ||
                    person.discordId.toLowerCase().includes(trimmed) ||
                    (person.minecraftUuid ?? "").toLowerCase().includes(trimmed)),
              )
              if (rows.length === 0) {
                return (
                  <Empty
                    title="Kein Konto passt"
                    note="Unter den geladenen Konten enthält keines diese Zeichenfolge."
                  />
                )
              }
              return (
                <>
                  <Table className="steward-table">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-[14rem]">Discord-ID</TableHead>
                        <TableHead className="w-[22rem]">Minecraft-UUID</TableHead>
                        <TableHead className="w-[13rem]">Verknüpft</TableHead>
                        <TableHead className="w-[8rem]">Gilde</TableHead>
                        <TableHead>Zugang</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {rows.map((person) => (
                        <TableRow key={person.discordId}>
                          <TableCell className="font-mono font-medium">
                            {person.discordId}
                          </TableCell>
                          <TableCell className="font-mono text-muted-foreground">
                            {person.minecraftUuid ?? (
                              <span className="font-sans text-xs">nicht verknüpft</span>
                            )}
                          </TableCell>
                          <TableCell className="text-muted-foreground tnum">
                            {person.linked ? dateTime(person.linked) : "–"}
                          </TableCell>
                          <TableCell>
                            <MemberBadge state={person.memberState} />
                          </TableCell>
                          <TableCell>
                            <AccessBadge person={person} now={now} />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  <p className="text-xs text-muted-foreground">
                    {count(rows.length)} von {count(list.length)} geladenen Konten. Ein Konto ohne
                    Verknüpfung kommt nicht auf den Server, auch mit bezahltem Zugang nicht: der
                    Proxy kennt nur Minecraft-UUIDs.
                  </p>
                </>
              )
            }}
          </QueryState>
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * What this interface accepts as proof of who you are - written down where somebody reads about
 * identities, because a gap here reads as "there is more, you just cannot see it".
 */
function AuthenticationCard() {
  const me = useMe()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <ShieldCheck className="size-4 text-muted-foreground" aria-hidden />
          Anmeldung an dieser Oberfläche
        </CardTitle>
        <CardDescription>
          Eine Discord-Sitzung ist zurzeit die <span className="text-foreground">ganze</span>{" "}
          Authentifizierung dieser Oberfläche.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge tone="warn" title="§10a sieht einen Sicherheitsschlüssel nach Discord vor.">
            Sicherheitsschlüssel: noch nicht gebaut
          </StatusBadge>
          {me.data?.name ? (
            <span className="text-sm text-muted-foreground">
              angemeldet als <span className="text-foreground">{me.data.name}</span>
            </span>
          ) : null}
        </div>
        <p className="max-w-prose text-sm text-muted-foreground">
          Es gibt keine dritte, Steward-eigene Identität – kein eigenes Passwort, kein zweiter
          Faktor. Wer die Discord-Sitzung eines Admins hat, hat diese Oberfläche, und damit auch die
          beiden Schreibvorgänge auf der Seite{" "}
          <Link to="/zugaenge" className="text-primary underline-offset-4 hover:underline">
            Zugänge
          </Link>
          . Das ist bewusst so, solange der Schlüssel fehlt, und es ist der Grund, warum jede
          Änderung im Journal landet.
        </p>
        {me.error ? (
          <Failure error={me.error} onRetry={me.refetch} />
        ) : me.data ? (
          // The backend's own sentence, verbatim and in English: this is the API's answer and not
          // a claim this page makes on its behalf.
          <pre className="overflow-auto rounded-sm bg-muted px-2 py-1 text-xs text-muted-foreground">
            /api/me · webauthn: {me.data.webauthn}
          </pre>
        ) : (
          <Loading rows={1} label="Anmeldung wird gelesen…" />
        )}
      </CardContent>
    </Card>
  )
}

// --- 4. /journal ----------------------------------------------------------------------------------

/**
 * The audit log.
 *
 * **Both filters are exact matches.** The backend compares the whole string, so this page offers
 * the actions as a list to choose from and asks for a subject explicitly rather than searching
 * while you type - a field that filters on every keystroke promises a substring search that does
 * not exist.
 *
 * The list of actions is built from the rows that are here. `audit_log.action` has no CHECK
 * constraint, and the writers are three: this interface (`GRANT_ACCESS`, `REVOKE_ACCESS`), the bot
 * (links, settlements) and the phase switch. A hardcoded list would be a page that cannot draw an
 * action somebody adds next week.
 */
export function JournalPage() {
  // The unfiltered query, for the options. With both filters empty it *is* the filtered query -
  // same key, one request - so this costs nothing until somebody actually filters.
  const all = useJournal("", "")
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [typed, setTyped] = useState("")
  const entries = useJournal(action, subject)
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].sort()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Journal"
        note="Jede Änderung, wer sie ausgelöst hat und wen sie betraf – die letzten 200 Einträge."
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Einträge</CardTitle>
          <CardDescription>
            Aus <code className="text-xs">audit_log</code>, neueste zuerst. Beide Filter vergleichen
            <span className="text-foreground"> die ganze Zeichenfolge</span> – Teiltreffer kann diese
            Abfrage nicht.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="journal-action">Aktion</Label>
              <Select
                value={action === "" ? "ALLE" : action}
                onValueChange={(value) => setAction(value === "ALLE" ? "" : value)}
              >
                <SelectTrigger id="journal-action" className="w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALLE">alle</SelectItem>
                  {actions.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <form
              className="flex items-end gap-2"
              onSubmit={(event) => {
                event.preventDefault()
                setSubject(typed.trim())
              }}
            >
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="journal-subject">Betroffene Discord-ID</Label>
                <Input
                  id="journal-subject"
                  value={typed}
                  onChange={(event) => setTyped(event.target.value)}
                  placeholder="genaue ID…"
                  className="w-64 font-mono"
                  autoComplete="off"
                  spellCheck={false}
                />
              </div>
              <Button type="submit" variant="outline">
                <Search aria-hidden />
                Filtern
              </Button>
              {subject ? (
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    setSubject("")
                    setTyped("")
                  }}
                >
                  Zurücksetzen
                </Button>
              ) : null}
            </form>
          </div>

          {actions.length === 0 && !all.isPending && !all.error ? (
            <p className="text-xs text-muted-foreground">
              Die Auswahl oben listet nur Aktionen, die in den geladenen Einträgen vorkommen – noch
              kommt keine vor.
            </p>
          ) : null}

          <QueryState
            query={entries}
            rows={10}
            empty={{
              title: "Kein Eintrag",
              note: "Mit diesen Filtern steht nichts im Protokoll. Beide vergleichen genau, nicht teilweise – ein Tippfehler in der ID sieht genauso aus wie „nichts passiert“.",
            }}
            isEmpty={(list: JournalEntry[]) => list.length === 0}
          >
            {(list) => (
              <>
                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[13rem]">Zeitpunkt</TableHead>
                      <TableHead className="w-[12rem]">Aktion</TableHead>
                      <TableHead className="w-[16rem]">Ausgelöst von</TableHead>
                      <TableHead className="w-[14rem]">Betrifft</TableHead>
                      <TableHead>Detail</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {list.map((entry) => (
                      <TableRow key={entry.id}>
                        <TableCell className="text-muted-foreground tnum" title={entry.occurred}>
                          {dateTime(entry.occurred)}
                        </TableCell>
                        {/* The action is printed raw. Translating it would mean a table of German
                         * words that silently falls back to the enum name for anything new - and
                         * this column is also what somebody greps the bot's log for. */}
                        <TableCell className="font-medium">{entry.action}</TableCell>
                        <TableCell className="text-muted-foreground">
                          {entry.actor ?? (
                            <span title="Kein Admin – der Bot hat von sich aus gehandelt.">
                              Bot
                            </span>
                          )}
                        </TableCell>
                        <TableCell className="font-mono text-muted-foreground">
                          {entry.subject ?? "–"}
                          {entry.mcUuid ? (
                            <span className="block text-xs" title={entry.mcUuid}>
                              {shortId(entry.mcUuid)}
                            </span>
                          ) : null}
                        </TableCell>
                        <TableCell className="text-muted-foreground">{entry.detail ?? "–"}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                <p className="text-xs text-muted-foreground">
                  {count(list.length)} Einträge. Mehr als 200 gibt diese Abfrage nicht heraus – ein
                  Blättern durch das ganze Protokoll kennt die API noch nicht.
                </p>
              </>
            )}
          </QueryState>
        </CardContent>
      </Card>
    </div>
  )
}
