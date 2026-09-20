import { PlayIcon, TerminalIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { AdminCommand, CommandArgument, CommandRun, Person } from "@/lib/api"
import { euros } from "@/lib/format"
import {
  useAdminCommand,
  useCommandRun,
  useCommands,
  useOpenPayments,
  usePeople,
} from "@/lib/queries"
import { personLabel } from "@/components/steward/identity"
import { Failure, QueryState } from "@/components/steward/query-state"
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
import {
  Card,
  CardContent,
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
export function CommandCard({
  title = "Commands",
  only,
}: {
  title?: string
  /**
   * Which of the declared commands belong on this page. Absent means all of them.
   *
   * A filter and not a second list: the card still draws whatever `/api/commands` returns, so a
   * command added to a declaration still appears somewhere without this file being edited. What
   * the predicate decides is only *where* - `access settle` under Access rather than under Season,
   * which is where it would otherwise have landed for no better reason than that the card was
   * already there.
   */
  only?: (command: AdminCommand) => boolean
} = {}) {
  const commands = useCommands()
  const shown = only ? (commands.data ?? []).filter(only) : commands.data

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <TerminalIcon className="size-4 text-muted-foreground" aria-hidden />
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <QueryState
          query={{ ...commands, data: shown } as typeof commands}
          rows={4}
          isEmpty={(list) => list.length === 0}
          empty={{ title: "No command is released to the interface." }}
        >
          {(list) => list.map((command) => <CommandRow key={command.name} command={command} />)}
        </QueryState>
      </CardContent>
    </Card>
  )
}

/**
 * The `/access` commands, which belong on the Access page and not on the Season one.
 *
 * One predicate used from both sides, so the two cards cannot both claim a command or both
 * disown it - which is exactly what two independently written filters do the first time a command
 * is added.
 */
export function isAccessCommand(command: AdminCommand): boolean {
  return command.path[0] === "access"
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
            {command.irreversible ? <Badge variant="destructive">irreversible</Badge> : null}
          </div>
          <span className="text-sm text-muted-foreground">
            Runs on <span className="font-mono">{command.target.toLowerCase()}</span>.
          </span>
        </div>
        <Button
          type="button"
          size="sm"
          variant={command.irreversible ? "outline" : "default"}
          disabled={missing.length > 0 || ask.isPending}
          onClick={() => (command.irreversible ? setConfirming(true) : send())}
        >
          <PlayIcon aria-hidden />
          Run
        </Button>
      </div>

      {command.arguments.length > 0 ? (
        <div className="flex flex-wrap gap-3">
          {command.arguments.map((argument) => (
            <ArgumentField
              key={argument.name}
              id={`${command.name}-${argument.name}`}
              argument={argument}
              value={values[argument.name] ?? ""}
              onChange={(next) => setValues((old) => ({ ...old, [argument.name]: next }))}
            />
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

      <ResponsiveAlertDialog open={confirming} onOpenChange={setConfirming}>
        <ResponsiveAlertDialogContent>
          <ResponsiveAlertDialogHeader>
            <ResponsiveAlertDialogTitle>
              Run <span className="font-mono">{command.name}</span>?
            </ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription>
              This command is declared irreversible - chat and Discord ask for the same
              confirmation. What it does, it does at once and with no way back.
            </ResponsiveAlertDialogDescription>
          </ResponsiveAlertDialogHeader>
          {ask.error ? <Failure error={ask.error} /> : null}
          <ResponsiveAlertDialogFooter>
            <ResponsiveAlertDialogCancel disabled={ask.isPending}>Cancel</ResponsiveAlertDialogCancel>
            <ResponsiveAlertDialogAction
              variant="destructive"
              disabled={ask.isPending}
              onClick={(event) => {
                event.preventDefault()
                send()
              }}
            >
              Run
            </ResponsiveAlertDialogAction>
          </ResponsiveAlertDialogFooter>
        </ResponsiveAlertDialogContent>
      </ResponsiveAlertDialog>
    </div>
  )
}


/**
 * One argument, drawn the way its kind asks to be drawn.
 *
 * **Two of the kinds are lists and not fields**, and that is the whole of package H's second
 * sentence: a REFERENCE is six characters with no meaning, and an ACCOUNT is a Discord snowflake.
 * Typing either from memory is a mistake nobody needs, on the two commands that book money and
 * break a link. Discord has autocompleted the reference since the command existed; this is the
 * browser's half of the same decision.
 *
 * The hooks are called unconditionally and switched off with `enabled`, because they are hooks -
 * and because a command with no ACCOUNT argument must not make the interface fetch the roster.
 */
/**
 * The roster as a picker, by NAME (steward/124).
 *
 * The comment that stood inline here said the roster cannot answer a display name - which was
 * wrong: it carries `discordDisplayName` and `discordUsername`, and {@link personLabel} falls back
 * to the id for anybody it has neither for, so nobody becomes an unpickable blank row.
 *
 * The **value** is still the Discord id, because that is what the command takes. This is only what
 * a human reads while choosing - which is also why this is a picker and not a field: an id is not
 * something anybody types correctly from memory.
 *
 * Exported for the test: the options live inside a Radix `Select`, which does not open in jsdom,
 * and what is worth holding here is the labelling rule rather than the popup's behaviour.
 */
export function accountOptions(
  people: Person[] | undefined,
): { value: string; label: string }[] {
  return (people ?? []).map((person) => ({
    value: person.discordId,
    label: person.minecraftUuid
      ? `${personLabel(person)} (linked)`
      : `${personLabel(person)} (not linked)`,
  }))
}

function ArgumentField({
  id,
  argument,
  value,
  onChange,
}: {
  id: string
  argument: CommandArgument
  value: string
  onChange: (next: string) => void
}) {
  const people = usePeople(argument.kind === "ACCOUNT")
  const open = useOpenPayments(argument.kind === "REFERENCE")

  const label = (
    <Label htmlFor={id} className="text-xs">
      {argument.name}
      {argument.required ? null : <span className="text-muted-foreground"> (optional)</span>}
    </Label>
  )

  if (argument.choices || argument.kind === "ACCOUNT" || argument.kind === "REFERENCE") {
    // One `Select` for three sources. The empty case is spelled out rather than left as a silent
    // dropdown with nothing in it: "nothing is open" and "the list has not loaded" are different
    // answers and the difference decides whether somebody waits or goes and looks.
    const options: { value: string; label: string }[] = argument.choices
      ? argument.choices.map((choice) => ({ value: choice, label: choice }))
      : argument.kind === "ACCOUNT"
        ? accountOptions(people.data)
        : (open.data ?? []).map((payment) => ({
            value: payment.reference,
            label: `${payment.reference} (${payment.days} days, ${euros(
              payment.amountCents + payment.donationCents,
            )}, ${personLabel(payment)})`,
          }))

    const loading =
      (argument.kind === "ACCOUNT" && people.isPending) ||
      (argument.kind === "REFERENCE" && open.isPending)

    return (
      <div className="flex min-w-48 flex-col gap-1.5">
        {label}
        <Select value={value} onValueChange={onChange} disabled={options.length === 0}>
          <SelectTrigger id={id} className="min-w-72">
            <SelectValue
              placeholder={
                loading
                  ? "Loading…"
                  : options.length === 0
                    ? argument.kind === "REFERENCE"
                      ? "Nothing is open"
                      : "Nobody to pick"
                    : "—"
              }
            />
          </SelectTrigger>
          <SelectContent>
            {options.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    )
  }

  return (
    <div className="flex min-w-48 flex-col gap-1.5">
      {label}
      <Input
        id={id}
        className="font-mono text-sm"
        spellCheck={false}
        inputMode={argument.kind === "INTEGER" ? "numeric" : undefined}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  )
}

/**
 * The four states a request can be in, each said in words rather than coloured.
 *
 * Exported for `inline-command.tsx`: `unlink` and `settle` (steward/47) went from a form on this
 * card to a button on the row that already names their one argument, but the request is still a
 * `command_request` claimed by another process, so the four words a poll can come back with are
 * exactly these four - no reason for a second copy of the sentences.
 */
export function Outcome({ run }: { run: CommandRun }) {
  const text: Record<CommandRun["status"], string> = {
    PENDING: "Written. The service responsible has not picked it up yet.",
    RUNNING: "Being carried out.",
    DONE: "Carried out.",
    FAILED: "The service picked it up and failed at it.",
    EXPIRED:
      "Nobody picked the row up. That means the service owning the command is not listening - not that the command failed.",
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
