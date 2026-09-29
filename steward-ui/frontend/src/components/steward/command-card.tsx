import { PlayIcon, TerminalIcon } from "@phosphor-icons/react"
import { useState, type ReactNode } from "react"

import type { AdminCommand, CommandArgument, CommandRun, Person } from "@/lib/api"
import { euros } from "@/lib/format"
import { useAdminCommand, useCommandRun, useCommands, useOpenPayments, usePeople } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
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
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/**
 * The admin commands with `Surface.WEB`, a button each, drawn from `/api/commands` without knowing what they do.
 *
 * A press writes a `command_request` row; EXPIRED means nobody claimed it, a different fault from FAILED.
 */
export function CommandCard({
  title = "Commands",
  only,
}: {
  title?: string
  /** Which declared commands belong on this page, all when absent; it decides only where a command is drawn. */
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
          query={{ ...commands, data: shown }}
          isEmpty={(list) => list.length === 0}
          empty={{
            title: "No command is released to the interface.",
            note: "A command appears here once its declaration carries Surface.WEB.",
          }}
        >
          {/* Four rows while waiting, roughly what every page carrying this card has. */}
          {(list) =>
            (list ?? PLACEHOLDERS).map((command, index) => (
              <CommandRow key={command?.name ?? index} command={command} />
            ))
          }
        </QueryState>
      </CardContent>
    </Card>
  )
}

/** The `/access` commands, one predicate used from both sides so no command is claimed or disowned twice. */
export function isAccessCommand(command: AdminCommand): boolean {
  return command.path[0] === "access"
}

/** The smp and hunger-games commands, which are designed controls on their service pages instead. */
export function isServiceCommand(command: AdminCommand): boolean {
  return command.path[0] === "smp" || command.path[0] === "hg"
}

/** Four absent commands, so the card waits at about the height it will have. */
const PLACEHOLDERS: (AdminCommand | undefined)[] = [undefined, undefined, undefined, undefined]

/** One command, known or not yet; the rows and the button are the same either way, so nothing moves. */
function CommandRow({ command }: { command?: AdminCommand }) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState(false)
  const [running, setRunning] = useState<string | null>(null)
  const ask = useAdminCommand()
  const run = useCommandRun(running)

  const missing = (command?.arguments ?? []).filter(
    (argument) => argument.required && !(values[argument.name] ?? "").trim(),
  )

  function send() {
    if (!command) return
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
            {command ? (
              <span className="font-mono text-sm">{command.name}</span>
            ) : (
              <SkeletonText className="w-32 text-sm" />
            )}
            {command?.irreversible ? <Badge variant="destructive">irreversible</Badge> : null}
          </div>
          {command ? (
            <span className="text-sm text-muted-foreground">
              Runs on <span className="font-mono">{command.target.toLowerCase()}</span>.
            </span>
          ) : (
            <SkeletonText className="w-24 text-sm" />
          )}
        </div>
        <Button
          type="button"
          size="sm"
          variant={command?.irreversible ? "outline" : "default"}
          disabled={!command || missing.length > 0 || ask.isPending}
          onClick={() => (command?.irreversible ? setConfirming(true) : send())}
        >
          <PlayIcon aria-hidden />
          Run
        </Button>
      </div>

      {command && command.arguments.length > 0 ? (
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

      {/* While the confirmation is open the failure is shown inside it, since a modal would cover this. */}
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
              Run <span className="font-mono">{command?.name}</span>?
            </ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription>
              This command is declared irreversible - chat and Discord ask for the same confirmation. What it does, it
              does at once and with no way back.
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
 * The roster as a picker by name, valued by Discord id, since nobody types an id from memory.
 *
 * Exported for the test, since the Radix `Select` holding the options does not open in jsdom.
 */
export function accountOptions(
  people: Pick<Person, "discordId" | "minecraftUuid">[] | undefined,
): { value: string; label: ReactNode }[] {
  return (people ?? []).map((person) => ({
    value: person.discordId,
    label: (
      <span className="inline-flex min-w-0 items-center gap-1.5">
        <Entity id={person.discordId} kind="discord" interactive={false} />
        <span className="text-xs text-muted-foreground">{person.minecraftUuid ? "linked" : "not linked"}</span>
      </span>
    ),
  }))
}

/** One argument; an ACCOUNT or REFERENCE is a list, and its hooks are switched off by `enabled` otherwise. */
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
    /** One `Select` for three sources, saying whether the list is empty or still loading. */
    const options: { value: string; label: ReactNode }[] = argument.choices
      ? argument.choices.map((choice) => ({ value: choice, label: choice }))
      : argument.kind === "ACCOUNT"
        ? accountOptions(people.data)
        : (open.data ?? []).map((payment) => ({
            value: payment.reference,
            label: (
              <span className="inline-flex min-w-0 items-center gap-1.5">
                {payment.reference}
                <span className="text-xs text-muted-foreground">
                  {payment.days} days, {euros(payment.amountCents + payment.donationCents)}
                </span>
                <Entity id={payment.discordId} kind="discord" interactive={false} />
              </span>
            ),
          }))

    const loading =
      (argument.kind === "ACCOUNT" && people.isPending) || (argument.kind === "REFERENCE" && open.isPending)

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
                    : "\u2014"
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
        className="font-mono text-sm max-md:text-base"
        spellCheck={false}
        inputMode={argument.kind === "INTEGER" ? "numeric" : undefined}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  )
}

/** The four states a request can be in, each said in words rather than coloured. */
function Outcome({ run }: { run: CommandRun }) {
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
