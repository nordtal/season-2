import { SwordIcon } from "@phosphor-icons/react"
import { useEffect, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"

import type { CommandRun } from "@/lib/api"
import { useCommandRun, useGameAction, useHungerGamesRound } from "@/lib/queries"
import { choice, message, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Failure, QueryState, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

export type Ask = {
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

/** The hunger games' round, started with one button. */
export function HungerGamesActions() {
  const round = useHungerGamesRound()
  const [ask, setAsk] = useState<Ask | null>(null)
  const state = round.data?.state

  const start = (anyway: boolean) =>
    setAsk({
      path: "/api/hunger-games/start",
      body: anyway ? { confirm: true } : {},
      title: t("steward.game.start-ask", { anyway }),
      description: t("steward.game.start-note"),
      confirm: t("steward.game.start", { anyway }),
    })

  /** A start refused below the recommended minimum is the one refusal a second, confirmed step can overrule. */
  const after = (run: CommandRun) =>
    run.reason === "BELOW_SOFT_MINIMUM" && ask?.confirm === t("steward.game.start", { anyway: false }) ? (
      <StartAnyway onStart={() => start(true)} />
    ) : null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <SwordIcon className="size-4 text-muted-foreground" aria-hidden />
          {t("steward.game.round")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center justify-between gap-3">
        <QueryState query={round}>
          {(data) =>
            data ? (
              <span className="text-sm text-muted-foreground">
                {data.state === undefined
                  ? t("steward.game.no-round")
                  : data.state === "REGISTRATION"
                    ? t("steward.game.registered", { count: data.registered ?? 0 })
                    : data.state === "COUNTDOWN"
                      ? t("steward.game.counting-down")
                      : t("steward.game.running")}
              </span>
            ) : (
              <SkeletonText width="medium" />
            )
          }
        </QueryState>
        <Button type="button" size="sm" disabled={state !== "REGISTRATION"} onClick={() => start(false)}>
          {t("steward.game.start-round")}
        </Button>
      </CardContent>
      <ActionDialog ask={ask} onClose={() => setAsk(null)} refresh="hunger-games-round" after={after} />
    </Card>
  )
}

function StartAnyway({ onStart }: { onStart: () => void }) {
  const round = useHungerGamesRound()
  if (round.data?.state !== "REGISTRATION") return null
  return (
    <Button type="button" variant="destructive" onClick={onStart}>
      {t("steward.game.start", { anyway: true })}
    </Button>
  )
}

/** Asks, sends, and then stays open with what became of it. */
export function ActionDialog({
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
  const [lastAsk, setLastAsk] = useState(ask)
  if (lastAsk !== ask) {
    setLastAsk(ask)
    setId(null)
    reset()
  }

  const waiting = action.isPending || (id !== null && !settled && !run.error)

  return (
    <AskThenAct
      open={ask !== null}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={ask?.title}
      description={ask?.description}
      action={ask?.confirm}
      acting={t("steward.form.sending")}
      destructive
      act={() => {
        if (!ask) return Promise.resolve()
        return action.mutateAsync({ path: ask.path, body: ask.body }).then((answer) => setId(answer.id))
      }}
      answered={
        id === null ? null : (
          <>
            {run.error ? <Failure error={run.error} onRetry={run.refetch} /> : null}
            {run.data ? <RequestOutcome run={run.data} /> : null}
            {run.data && settled && after ? after(run.data) : null}
          </>
        )
      }
      busy={waiting}
      closeLabel={waiting ? t("steward.form.waiting") : t("steward.form.close")}
    />
  )
}

/**
 * The states a request can be in, each said in words.
 *
 * EXPIRED means no server claimed the row, so it is not listening; the action did not fail.
 */
export function RequestOutcome({ run }: { run: CommandRun }) {
  const tone =
    run.status === "FAILED" || run.status === "EXPIRED"
      ? "border-destructive/40 bg-destructive/5 text-destructive"
      : "border-border bg-muted text-foreground"

  return (
    <div role="status" className={`flex flex-col gap-1 rounded-md border px-3 py-2 text-sm ${tone}`}>
      <span>{t("steward.game.outcome", { status: choice(run.status) })}</span>
      {run.result ? <span className="whitespace-pre-wrap">{message(run.result)}</span> : null}
    </div>
  )
}
