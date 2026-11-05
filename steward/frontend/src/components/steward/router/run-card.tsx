import { useEffect, useState } from "react"
import { Link } from "@tanstack/react-router"

import type { Router, Run, SeasonMode } from "@/lib/api"
import { time } from "@/lib/format"
import { useRunLock } from "@/lib/run-lock"
import { runPath } from "@/lib/run-path"
import { t } from "@/lib/texts"
import { CancelButton, cancellable } from "@/pages/operations"
import { Actor } from "@/components/steward/entity"
import { RailStage, Steps } from "@/components/steward/run-rail"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { SwitchDialog } from "@/components/steward/router/router-dialogs"
import { Fact, Facts, KeyedButton, Pair } from "@/components/steward/router/router-parts"
import {
  OTHER,
  type SwitchState,
  modeName,
  secondsBetween,
  switchStages,
  switchState,
} from "@/components/steward/router/router-state"

const BADGE: Record<Exclude<SwitchState, "none">, { tone: Tone; state: string }> = {
  queued: { tone: "idle", state: "waiting" },
  running: { tone: "warn", state: "running" },
  done: { tone: "ok", state: "done" },
  failed: { tone: "down", state: "failed" },
}

/** Returns the time each second while `on`, so a running switch counts its length up. */
function useSecond(on: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!on) return undefined
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [on])
  return now
}

/** The last mode switch on a card of its own under the game mode, so a long run never stretches the row above. */
export function RunCard({ router, className }: { router: Router; className?: string }) {
  const state = switchState(router)
  const last = router.lastSwitch
  const now = useSecond(state === "running")
  if (state === "none" || !last) return null
  const run = last.run

  return (
    <Card className={className}>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <RunHead run={run} state={state} now={now} />
          {state === "queued" && cancellable(run, new Date(now)) ? <CancelButton run={run} /> : null}
        </div>
        {state === "failed" ? <Retry router={router} target={last.target} /> : null}
        {state === "done" ? (
          <Finished run={run} target={last.target} />
        ) : state === "running" || state === "failed" ? (
          <SwitchRail run={run} target={last.target} maintenance={router.maintenance} />
        ) : null}
      </CardContent>
    </Card>
  )
}

function RunHead({ run, state, now }: { run: Run; state: Exclude<SwitchState, "none">; now: number }) {
  const badge = BADGE[state]
  const seconds = secondsBetween(run.started, run.finished, now)
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="font-heading text-sm font-medium">
        {state === "done" ? t("steward.router.last-switch") : t("steward.router.mode-switch")}
      </span>
      <Link {...runPath(run)} className="text-sm tnum text-muted-foreground hover:text-foreground hover:underline">
        #{run.id}
      </Link>
      <StatusBadge tone={badge.tone}>{t("steward.router.run", { state: badge.state })}</StatusBadge>
      {state === "running" ? (
        <span className="text-xs tnum text-muted-foreground">
          {t("steward.router.running-for", { length: seconds })}
        </span>
      ) : null}
      {state === "failed" ? (
        <span className="text-xs tnum text-muted-foreground">
          {t("steward.router.failed-after", { length: seconds })}
        </span>
      ) : null}
    </div>
  )
}

/** After a failed switch: the same switch again, or the switch back to the mode it left. */
function Retry({ router, target }: { router: Router; target: SeasonMode }) {
  const lock = useRunLock()
  const [open, setOpen] = useState<SeasonMode | null>(null)
  const busy = lock.locked ? t("steward.router.switch-busy") : undefined
  return (
    <div className="flex flex-wrap items-center gap-2">
      <KeyedButton size="sm" disabled={lock.locked} tip={busy} onClick={() => setOpen(target)}>
        <TERM_ICON.retry aria-hidden />
        {t("steward.router.try-again")}
      </KeyedButton>
      <KeyedButton variant="outline" size="sm" disabled={lock.locked} tip={busy} onClick={() => setOpen(OTHER[target])}>
        <TERM_ICON.gameMode aria-hidden />
        {t("steward.router.back-to", { mode: modeName(OTHER[target]) })}
      </KeyedButton>
      <SwitchDialog
        router={router}
        target={open ?? target}
        open={open !== null}
        onOpenChange={(next) => setOpen(next ? (open ?? target) : null)}
      />
    </div>
  )
}

function SwitchRail({ run, target, maintenance }: { run: Run; target: SeasonMode; maintenance: boolean }) {
  const stages = switchStages(run, target, maintenance)
  return (
    <ol className="flex flex-col">
      {stages.map((stage, index) => (
        <RailStage
          key={stage.key}
          id={`switch-${run.id}-${stage.key}`}
          name={stage.name}
          verdict={stage.verdict}
          pills={stage.pills}
          last={index === stages.length - 1}
        >
          {stage.verdict === "failed" || stage.verdict === "warning" ? (
            <div className="flex flex-col gap-1">
              <Steps steps={stage.steps} />
              <Button asChild variant="link" size="sm" className="self-start px-0">
                <Link {...runPath(run)}>{t("steward.router.open-log")}</Link>
              </Button>
            </div>
          ) : undefined}
        </RailStage>
      ))}
    </ol>
  )
}

function Finished({ run, target }: { run: Run; target: SeasonMode }) {
  const moves = run.steps.flatMap((step) => step.players)
  const moved = moves.reduce((sum, move) => sum + move.moved, 0)
  const returned = moves.reduce((sum, move) => sum + move.returned, 0)
  const rest = moved - returned
  return (
    <Facts>
      <Fact
        term={t("steward.router.finished")}
        hint={t("steward.router.took", { length: secondsBetween(run.started, run.finished, 0) })}
      >
        <span className="tnum">{time(run.finished)}</span>
      </Fact>
      <Fact
        term={t("steward.router.moved")}
        hint={rest > 0 ? t("steward.router.without-access-count", { count: rest }) : undefined}
      >
        <span className="tnum">{t("steward.router.moved-to", { count: returned, server: modeName(target) })}</span>
      </Fact>
      <Fact term={t("steward.router.mode")}>
        <Pair steps={[modeName(OTHER[target]), modeName(target)]} />
      </Fact>
      <Fact term={t("steward.router.requested-by")} hint={t("steward.router.at-time", { at: run.requested })}>
        <Actor kind={run.actorKind} id={run.actorId} />
      </Fact>
    </Facts>
  )
}
