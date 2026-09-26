import { CheckIcon, FlagBannerIcon, SwordIcon } from "@phosphor-icons/react"
import { useEffect, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"

import type { CommandRun, SmpTrack } from "@/lib/api"
import { useCommandRun, useGameAction, useHungerGamesRound, useSmpTrack } from "@/lib/queries"
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

/**
 * The SMP's and the hunger games' admin actions, on their own service pages.
 *
 * Decided 2026-09-20: these actions must never look like commands in any way. So nothing here names a command, draws a terminal or offers an argument field: an
 * objective and a milestone are picked from the SMP's own track, and a round is one button.
 *
 * **Each press is still a row a Paper server has to pick up**, so the answer arrives late and in
 * one of four states. EXPIRED is its own sentence: nobody claimed the row, which says the server is
 * not listening - not that the action failed.
 */

type Ask = {
  path: string
  body: unknown
  title: string
  description: string
  confirm: string
}

/** A key as the track file spells it, readable: `ancient-debris` becomes "Ancient debris". */
export function keyName(key: string): string {
  const spaced = key.replace(/[-_]+/g, " ").trim()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

export function SmpActions() {
  const track = useSmpTrack()
  const [ask, setAsk] = useState<Ask | null>(null)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FlagBannerIcon className="size-4 text-muted-foreground" aria-hidden />
          Milestone track
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <QueryState
          query={track}
          isEmpty={(data: SmpTrack) => data.active.length === 0}
          empty={{ title: "No milestone is active." }}
        >
          {(data) =>
            data ? (
              data.active.map((milestone) => <Milestone key={milestone.key} milestone={milestone} onAsk={setAsk} />)
            ) : (
              <SkeletonText width="long" />
            )
          }
        </QueryState>
      </CardContent>
      <ActionDialog ask={ask} onClose={() => setAsk(null)} refresh="smp-track" />
    </Card>
  )
}

function Milestone({ milestone, onAsk }: { milestone: SmpTrack["active"][number]; onAsk: (ask: Ask) => void }) {
  const name = keyName(milestone.key)
  return (
    <section className="flex flex-col gap-2" aria-label={name}>
      <div className="flex items-center justify-between gap-2">
        <h3 className="min-w-0 truncate font-medium">{name}</h3>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() =>
            onAsk({
              path: "/api/smp/milestone",
              body: { key: milestone.key },
              title: `Unlock ${name}?`,
              description: "The track moves on and aura is paid out to everybody who qualified. There is no way back.",
              confirm: "Unlock",
            })
          }
        >
          Unlock
        </Button>
      </div>
      <ul className="flex flex-col divide-y divide-border rounded-md border">
        {milestone.objectives.map((objective) => {
          const objectiveName = keyName(objective.key)
          return (
            <li key={objective.key} className="flex min-h-11 items-center justify-between gap-3 px-3 py-1.5">
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-sm">{objectiveName}</span>
                <span className="text-xs tabular-nums text-muted-foreground">
                  {objective.amount.toLocaleString("en")} / {objective.target.toLocaleString("en")}
                </span>
              </div>
              {objective.completed ? (
                <Badge variant="secondary" className="gap-1">
                  <CheckIcon aria-hidden />
                  done
                </Badge>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={`Complete ${objectiveName}`}
                  onClick={() =>
                    onAsk({
                      path: "/api/smp/objective",
                      body: { key: objective.key },
                      title: `Complete ${objectiveName}?`,
                      description: `It closes at ${objective.amount.toLocaleString("en")} of ${objective.target.toLocaleString("en")} and pays out that share of its aura. There is no way back.`,
                      confirm: "Complete",
                    })
                  }
                >
                  Complete
                </Button>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

export function HungerGamesActions() {
  const round = useHungerGamesRound()
  const [ask, setAsk] = useState<Ask | null>(null)
  const state = round.data?.state

  const start = (confirm: boolean) =>
    setAsk({
      path: "/api/hunger-games/start",
      body: confirm ? { confirm: true } : {},
      title: confirm ? "Start anyway?" : "Start the round?",
      description: "Everybody registered is sent into the arena and the countdown begins. There is no way back.",
      confirm: confirm ? "Start anyway" : "Start",
    })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <SwordIcon className="size-4 text-muted-foreground" aria-hidden />
          Round
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center justify-between gap-3">
        <QueryState query={round}>
          {(data) =>
            data ? (
              <span className="text-sm text-muted-foreground">
                {data.state === undefined
                  ? "No round is open."
                  : data.state === "REGISTRATION"
                    ? `${data.registered ?? 0} registered`
                    : data.state === "COUNTDOWN"
                      ? "Counting down"
                      : "Running"}
              </span>
            ) : (
              <SkeletonText width="medium" />
            )
          }
        </QueryState>
        <Button type="button" size="sm" disabled={state !== "REGISTRATION"} onClick={() => start(false)}>
          Start round
        </Button>
      </CardContent>
      <ActionDialog
        ask={ask}
        onClose={() => setAsk(null)}
        refresh="hunger-games-round"
        // The server asks again when fewer than the recommended number are registered, and says
        // so in its answer. A round still open for registration after a start is one that did not
        // start - so that is when the second step is offered, and never before the first answer.
        after={(run) =>
          run.status === "DONE" && ask?.confirm === "Start" ? <StartAnyway onStart={() => start(true)} /> : null
        }
      />
    </Card>
  )
}

function StartAnyway({ onStart }: { onStart: () => void }) {
  const round = useHungerGamesRound()
  if (round.data?.state !== "REGISTRATION") return null
  return (
    <Button type="button" variant="destructive" onClick={onStart}>
      Start anyway
    </Button>
  )
}

/**
 * Asks, sends, and then stays open with what became of it - the row's four states in words.
 */
function ActionDialog({
  ask,
  onClose,
  refresh,
  after,
}: {
  ask: Ask | null
  onClose: () => void
  /** The query this action changes, re-read once the server has answered. */
  refresh: string
  after?: (run: CommandRun) => ReactNode
}) {
  const client = useQueryClient()
  const action = useGameAction()
  const [id, setId] = useState<string | null>(null)
  const run = useCommandRun(id)
  const settled = run.data && run.data.status !== "PENDING" && run.data.status !== "RUNNING"

  useEffect(() => {
    if (settled) void client.invalidateQueries({ queryKey: [refresh] })
  }, [settled, client, refresh])

  // A new question starts clean: the answer to the last one is not the answer to this.
  const reset = action.reset
  useEffect(() => {
    setId(null)
    reset()
  }, [ask, reset])

  const waiting = action.isPending || (id !== null && !settled && !run.error)

  return (
    <ResponsiveAlertDialog
      open={ask !== null}
      onOpenChange={(open) => {
        if (!open && !waiting) onClose()
      }}
    >
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>{ask?.title}</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>{ask?.description}</ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>
        {action.error ? <Failure error={action.error} /> : null}
        {run.error ? <Failure error={run.error} onRetry={run.refetch} /> : null}
        {run.data ? <RequestOutcome run={run.data} /> : null}
        <ResponsiveAlertDialogFooter>
          {id === null ? (
            <>
              <ResponsiveAlertDialogCancel disabled={waiting}>Cancel</ResponsiveAlertDialogCancel>
              <ResponsiveAlertDialogAction
                variant="destructive"
                disabled={waiting}
                onClick={(event) => {
                  event.preventDefault()
                  if (!ask) return
                  action.mutate({ path: ask.path, body: ask.body }, { onSuccess: (answer) => setId(answer.id) })
                }}
              >
                {waiting ? "Sending…" : ask?.confirm}
              </ResponsiveAlertDialogAction>
            </>
          ) : (
            <>
              {run.data && settled && after ? after(run.data) : null}
              <ResponsiveAlertDialogCancel disabled={waiting}>
                {waiting ? "Waiting…" : "Close"}
              </ResponsiveAlertDialogCancel>
            </>
          )}
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** The four states a request can be in, each said in words. */
export function RequestOutcome({ run }: { run: CommandRun }) {
  const text: Record<CommandRun["status"], string> = {
    PENDING: "Sent. The server has not picked it up yet.",
    RUNNING: "The server is carrying it out.",
    DONE: "The server answered:",
    FAILED: "The server picked it up and failed at it.",
    EXPIRED: "Nobody picked this up within two minutes: the server is not listening. Nothing was changed.",
  }
  const tone =
    run.status === "FAILED" || run.status === "EXPIRED"
      ? "border-destructive/40 bg-destructive/5 text-destructive"
      : "border-border bg-muted text-foreground"

  return (
    <div role="status" className={`flex flex-col gap-1 rounded-md border px-3 py-2 text-sm ${tone}`}>
      <span>{text[run.status]}</span>
      {run.result ? <span className="whitespace-pre-wrap">{run.result}</span> : null}
    </div>
  )
}
