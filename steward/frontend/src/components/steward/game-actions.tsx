import { TERM_ICON } from "@/components/steward/term-icons"
import { useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { CheckIcon, SpinnerIcon, type Icon } from "@phosphor-icons/react"

import type { CommandRun } from "@/lib/api"
import { RunNotDone, useGameAction, useHungerGamesRound } from "@/lib/queries"
import { t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { QueryState, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

export type Ask = {
  path: string
  body: unknown
  title: string
  description: string
  confirm: string
}

export function keyName(key: string): string {
  const spaced = key.replace(/[-_]+/g, " ").trim()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

export function HungerGamesActions() {
  const round = useHungerGamesRound()
  const [ask, setAsk] = useState<Ask | null>(null)
  const state = round.data?.state

  const start = (anyway: boolean) =>
    setAsk({
      path: "/api/hunger-games/start",
      body: anyway ? { confirm: true } : {},
      title: t("steward.game.start-ask", { anyway }),
      description: t("steward.game.no-way-back"),
      confirm: t("steward.game.start", { anyway }),
    })

  /**
   * A start refused for being below the recommended minimum is the one refusal that a second, confirmed step can overrule.
   */
  const after = (run: CommandRun) =>
    run.reason === "BELOW_SOFT_MINIMUM" && ask?.confirm === t("steward.game.start", { anyway: false }) ? (
      <StartAnyway onStart={() => start(true)} />
    ) : null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <TERM_ICON.round className="size-4 text-muted-foreground" aria-hidden />
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

export function ActionDialog({
  ask,
  onClose,
  refresh,
  after,
}: {
  ask: Ask | null
  onClose: () => void
  refresh: string
  after?: (run: CommandRun) => ReactNode
}) {
  const client = useQueryClient()
  const action = useGameAction()

  // A new question starts clean, because the answer to the last one does not answer it.
  const reset = action.reset
  const [lastAsk, setLastAsk] = useState(ask)
  if (lastAsk !== ask) {
    setLastAsk(ask)
    reset()
  }

  return (
    <AskThenAct
      open={ask !== null}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={ask?.title}
      description={ask?.description}
      action={ask?.confirm}
      acting={t("steward.form.waiting")}
      destructive
      act={() => {
        if (!ask) return Promise.resolve()
        return action
          .mutateAsync({ path: ask.path, body: ask.body })
          .finally(() => void client.invalidateQueries({ queryKey: [refresh] }))
      }}
    >
      {after && action.error instanceof RunNotDone ? after(action.error.run) : null}
    </AskThenAct>
  )
}

/**
 * The icon of a button that sends a request to a server: its own icon, if any, at rest, a spinner while the server has not done
 * it, and a check once it has. A failure shows on the button's `aria-invalid` and in words beside it. The button
 * carries an `aria-label`, so the status inside it does not change its name.
 */
export function RequestIcon({
  request,
  icon: Idle,
}: {
  request: { isPending: boolean; isSuccess: boolean }
  icon?: Icon
}) {
  return (
    <>
      {request.isPending ? (
        <SpinnerIcon aria-hidden className="animate-spin" />
      ) : request.isSuccess ? (
        <CheckIcon aria-hidden />
      ) : Idle ? (
        <Idle aria-hidden />
      ) : null}
      <span role="status" className="sr-only">
        {request.isSuccess ? t("steward.form.done") : ""}
      </span>
    </>
  )
}
