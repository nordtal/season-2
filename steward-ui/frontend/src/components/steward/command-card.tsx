import { useState } from "react"
import { Play, Terminal } from "lucide-react"

import type { AdminCommand, CommandRun } from "@/lib/api"
import { useAdminCommand, useCommandRun, useCommands } from "@/lib/queries"
import { Failure, QueryState } from "@/components/steward/query-state"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

/**
 * The admin commands that stayed in the game, with a button each (concept §10a, §10b).
 *
 * **Nothing here knows what a command does.** The list, the arguments, which ones need a
 * confirmation - all of it comes from the declarations in `:commands`, over `/api/commands`. A
 * button appears here because a `Declaration` carries `Surface.WEB`, and for no other reason; that
 * is what keeps this from becoming a second, quietly diverging copy of the command catalogue.
 *
 * **A command is a row, not a call.** Pressing one writes `command_request` and the process that
 * owns the command claims it. So the answer arrives late, and the four states it can arrive in are
 * all shown: EXPIRED means nothing ever claimed the row, which is a different fault from FAILED and
 * wants a different errand.
 */
export function CommandCard() {
  const commands = useCommands()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Terminal className="size-4 text-muted-foreground" aria-hidden />
          Befehle
        </CardTitle>
        <CardDescription>
          Dieselben Befehle wie im Spiel und in Discord, dieselbe Umsetzung. Ausgeführt werden sie
          von dem Dienst, dem sie gehören – die Oberfläche schreibt nur die Zeile und wartet auf die
          Antwort.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <QueryState
          query={commands}
          rows={4}
          isEmpty={(list) => list.length === 0}
          empty={{ title: "Kein Befehl ist für die Oberfläche freigegeben." }}
        >
          {(list) => list.map((command) => <CommandRow key={command.name} command={command} />)}
        </QueryState>
      </CardContent>
    </Card>
  )
}

function CommandRow({ command }: { command: AdminCommand }) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState(false)
  const [running, setRunning] = useState<string | null>(null)
  const ask = useAdminCommand()
  const run = useCommandRun(running)

  const missing = command.arguments.filter(
    (argument) => argument.required && !(values[argument.name] ?? "").trim(),
  )

  function send() {
    ask.mutate(
      { name: command.name, arguments: values },
      {
        onSuccess: (started) => {
          setRunning(started.id)
          setConfirming(false)
        },
      },
    )
  }

  return (
    <div className="flex flex-col gap-3 rounded-md border border-border px-3 py-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex items-center gap-2">
            <span className="font-mono text-sm">{command.name}</span>
            {command.irreversible ? <Badge variant="destructive">nicht umkehrbar</Badge> : null}
          </div>
          <span className="text-sm text-muted-foreground">
            Läuft auf <span className="font-mono">{command.target.toLowerCase()}</span>.
          </span>
        </div>
        <Button
          type="button"
          size="sm"
          variant={command.irreversible ? "outline" : "default"}
          disabled={missing.length > 0 || ask.isPending}
          onClick={() => (command.irreversible ? setConfirming(true) : send())}
        >
          <Play aria-hidden />
          Ausführen
        </Button>
      </div>

      {command.arguments.length > 0 ? (
        <div className="flex flex-wrap gap-3">
          {command.arguments.map((argument) => (
            <div key={argument.name} className="flex min-w-48 flex-col gap-1.5">
              <Label htmlFor={`${command.name}-${argument.name}`} className="text-xs">
                {argument.name}
                {argument.required ? null : (
                  <span className="text-muted-foreground"> (optional)</span>
                )}
              </Label>
              {argument.choices ? (
                <Select
                  value={values[argument.name] ?? ""}
                  onValueChange={(picked) =>
                    setValues((old) => ({ ...old, [argument.name]: picked }))
                  }
                >
                  <SelectTrigger id={`${command.name}-${argument.name}`}>
                    <SelectValue placeholder="—" />
                  </SelectTrigger>
                  <SelectContent>
                    {argument.choices.map((choice) => (
                      <SelectItem key={choice} value={choice}>
                        {choice}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <Input
                  id={`${command.name}-${argument.name}`}
                  className="font-mono text-sm"
                  spellCheck={false}
                  inputMode={argument.kind === "INTEGER" ? "numeric" : undefined}
                  value={values[argument.name] ?? ""}
                  onChange={(event) =>
                    setValues((old) => ({ ...old, [argument.name]: event.target.value }))
                  }
                />
              )}
            </div>
          ))}
        </div>
      ) : null}

      {/*
        While the confirmation is open it covers this, so the same failure is shown inside the
        dialog instead - see below. A refusal rendered behind a modal is a refusal nobody reads,
        and the button it belongs to is still sitting there looking like it did nothing.
      */}
      {ask.error && !confirming ? <Failure error={ask.error} /> : null}
      {run.error ? (
        <Failure error={run.error} onRetry={() => void run.refetch()} />
      ) : run.data ? (
        <Outcome run={run.data} />
      ) : null}

      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              <span className="font-mono">{command.name}</span> ausführen?
            </AlertDialogTitle>
            <AlertDialogDescription>
              Dieser Befehl ist als nicht umkehrbar deklariert – dieselbe Bestätigung verlangen auch
              Chat und Discord. Was er tut, tut er sofort und ohne Rückweg.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {ask.error ? <Failure error={ask.error} /> : null}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={ask.isPending}>Abbrechen</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={ask.isPending}
              onClick={(event) => {
                event.preventDefault()
                send()
              }}
            >
              Ausführen
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

/** The four states a request can be in, each said in words rather than coloured. */
function Outcome({ run }: { run: CommandRun }) {
  const text: Record<CommandRun["status"], string> = {
    PENDING: "Geschrieben. Der zuständige Dienst hat sie noch nicht abgeholt.",
    RUNNING: "Wird ausgeführt.",
    DONE: "Ausgeführt.",
    FAILED: "Der Dienst hat sie abgeholt und ist daran gescheitert.",
    EXPIRED:
      "Niemand hat die Zeile abgeholt. Das heißt: der Dienst, dem der Befehl gehört, hört nicht zu – nicht, dass der Befehl fehlgeschlagen ist.",
  }
  const tone =
    run.status === "DONE"
      ? "border-success/30 bg-success/8 text-success"
      : run.status === "FAILED" || run.status === "EXPIRED"
        ? "border-destructive/40 bg-destructive/5 text-destructive"
        : "border-border bg-muted text-muted-foreground"

  return (
    <div className={`flex flex-col gap-1 rounded-md border px-3 py-2 text-sm ${tone}`}>
      <span>{text[run.status]}</span>
      {run.result ? <span className="whitespace-pre-wrap">{run.result}</span> : null}
    </div>
  )
}
