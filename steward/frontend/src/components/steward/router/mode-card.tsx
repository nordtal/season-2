import { useState } from "react"
import { Link } from "@tanstack/react-router"

import type { Router, SeasonMode, Service } from "@/lib/api"
import { time } from "@/lib/format"
import { usePeople } from "@/lib/queries-access"
import { useRunLock } from "@/lib/run-lock"
import { cn } from "@/lib/utils"
import { choice, t } from "@/lib/texts"
import { AskButton } from "@/pages/operations"
import { ServiceIcon } from "@/components/steward/service-name"
import { Hint, StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { SwitchDialog } from "@/components/steward/router/router-dialogs"
import { KeyedButton, RouterCard } from "@/components/steward/router/router-parts"
import {
  OTHER,
  SERVER_TONE,
  type ServerState,
  modeName,
  serverOf,
  serverState,
  switchHolds,
  switchState,
} from "@/components/steward/router/router-state"

/** The two game modes: the active one, and the other with what an admin may do to its server and the switch. */
export function ModeCard({ router, services, className }: { router: Router; services: Service[]; className?: string }) {
  return (
    <RouterCard icon={TERM_ICON.gameMode} title={t("steward.router.game-mode")} className={className}>
      <div className="flex flex-col divide-y">
        {[router.mode, OTHER[router.mode]].map((mode) => (
          <ModeRow
            key={mode}
            router={router}
            mode={mode}
            service={services.find((row) => row.service === serverOf(mode))}
          />
        ))}
      </div>
    </RouterCard>
  )
}

function ModeRow({ router, mode, service }: { router: Router; mode: SeasonMode; service: Service | undefined }) {
  const active = mode === router.mode
  const state = serverState(router, mode, service)
  const players = service?.players ?? 0
  const name = serverOf(mode)
  const free = !active && !switchHolds(switchState(router))

  return (
    <div className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-2 py-3 first:pt-0 last:pb-0 md:grid-cols-[auto_minmax(0,1fr)_auto]">
      <ServiceIcon service={name} className={cn("size-5", !active && "text-muted-foreground")} />
      <div className="flex min-w-0 flex-col gap-1">
        <div className="flex items-center gap-2">
          <Link to="/services/$name" params={{ name }} className="font-medium hover:underline">
            {modeName(mode)}
          </Link>
          {active ? <Badge variant="secondary">{t("steward.router.active")}</Badge> : null}
        </div>
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground">
          <ServerBadge router={router} state={state} />
          <span>{mode === "SMP" ? t("steward.router.access-needed") : t("steward.router.every-member")}</span>
          {!active && state === "by-hand" && players > 0 ? (
            <span className="tnum">{t("steward.router.admins-on-it", { count: players })}</span>
          ) : null}
        </div>
      </div>
      {active ? (
        <span
          className="col-start-2 flex items-center gap-1 text-sm tnum md:col-start-3"
          aria-label={t("steward.router.players-on", { count: players })}
        >
          <TERM_ICON.people aria-hidden className="size-3.5 text-muted-foreground" />
          {players}
        </span>
      ) : free ? (
        <RowActions router={router} mode={mode} state={state} />
      ) : null}
    </div>
  )
}

function ServerBadge({ router, state }: { router: Router; state: ServerState }) {
  const word = t("steward.router.server", { state: choice(state) })
  const hand = router.handStart
  const people = usePeople(state === "by-hand" && hand?.byKind === "PERSON")
  if (state === "by-hand" && hand) {
    const person = people.data?.find((row) => row.discordId === hand.byId)
    const by =
      hand.byKind === "HOST"
        ? t("steward.identity.host")
        : (person?.discordDisplayName ?? person?.discordUsername ?? hand.byId)
    return (
      <StatusBadge tone={SERVER_TONE[state]} tipContent={t("steward.router.by-hand-tip", { by, at: hand.at })}>
        <TERM_ICON.byHand aria-hidden />
        {word}
        <span className="tnum md:hidden">{time(hand.at)}</span>
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone={SERVER_TONE[state]}>
      {state === "resting" ? <TERM_ICON.resting aria-hidden /> : null}
      {state === "by-hand" ? <TERM_ICON.byHand aria-hidden /> : null}
      {word}
    </StatusBadge>
  )
}

/**
 * The inactive mode's actions: its server started or stopped for admins alone, and the switch. Each waits for an
 * open run to finish.
 */
function RowActions({ router, mode, state }: { router: Router; mode: SeasonMode; state: ServerState }) {
  const lock = useRunLock()
  const [ask, setAsk] = useState(false)
  const [switching, setSwitching] = useState(false)
  const server = modeName(mode)
  const stays = modeName(router.mode)
  const quiet = state === "resting" ? "START" : state === "by-hand" ? "DOWN" : null

  return (
    <div className="col-span-full flex flex-wrap items-center justify-end gap-1.5 md:col-span-1">
      {quiet ? (
        <>
          <Hint
            tip={
              lock.title ??
              (quiet === "START"
                ? t("steward.router.start-tip", { server, mode: stays })
                : t("steward.router.stop-tip", { server, mode: stays }))
            }
          >
            <Button type="button" variant="ghost" size="sm" disabled={lock.locked} onClick={() => setAsk(true)}>
              {quiet === "START" ? <TERM_ICON.start aria-hidden /> : <TERM_ICON.down aria-hidden />}
              {quiet === "START" ? t("steward.router.start") : t("steward.router.stop")}
            </Button>
          </Hint>
          <AskButton kind={quiet} services={[serverOf(mode)]} trigger={false} open={ask} onOpenChange={setAsk} />
        </>
      ) : null}
      <KeyedButton
        variant="outline"
        size="sm"
        disabled={lock.locked}
        tip={lock.locked ? t("steward.router.switch-busy") : undefined}
        onClick={() => setSwitching(true)}
      >
        <TERM_ICON.gameMode aria-hidden />
        {t("steward.router.switch-mode")}
      </KeyedButton>
      <SwitchDialog router={router} target={mode} open={switching} onOpenChange={setSwitching} />
    </div>
  )
}
