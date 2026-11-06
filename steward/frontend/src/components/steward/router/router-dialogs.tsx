import { useState } from "react"
import { toast } from "sonner"
import { CalendarPlusIcon, PencilSimpleIcon } from "@/components/ui/icons"

import type { Router, SeasonMode } from "@/lib/api"
import { NO_VALUE, dateTime, relative } from "@/lib/format"
import {
  useCloseSeason,
  useLaunchNow,
  useSeasonPlayers,
  useSetLaunch,
  useSetMaintenance,
  useSwitchMode,
} from "@/lib/queries-season"
import { t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ServiceIcon } from "@/components/steward/service-name"
import { TERM_ICON } from "@/components/steward/term-icons"
import {
  DFact,
  DFacts,
  KeyNext,
  Note,
  Pair,
  Parts,
  ReasonField,
  WithHint,
} from "@/components/steward/router/router-parts"
import { LIMBO, modeName, serverOf, switchHolds, switchState } from "@/components/steward/router/router-state"

type DialogProps = { router: Router; open: boolean; onOpenChange: (open: boolean) => void }

/** The players a dialog's change moves, read only while it is open. */
function usePlayers(open: boolean) {
  return { players: useSeasonPlayers(open).data }
}

/** A count not read yet shows the en dash. */
function shown(count: number | undefined): string {
  return count === undefined ? NO_VALUE : String(count)
}

/** The browser's time zone, which the date field reads and writes in. */
export function zone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

function done(change: string) {
  toast.success(t("steward.router.done", { change }))
}

/** A reason typed into a dialog, emptied each time it opens. */
function useReason(open: boolean) {
  const [reason, setReason] = useState("")
  const [seen, setSeen] = useState(open)
  if (seen !== open) {
    setSeen(open)
    if (open) setReason("")
  }
  return [reason, setReason] as const
}

function withReason(reason: string) {
  return reason.trim() ? { reason: reason.trim() } : {}
}

export function LaunchNowDialog({ router, open, onOpenChange, now }: DialogProps & { now: number }) {
  const launch = useLaunchNow()
  const [reason, setReason] = useReason(open)
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={t("steward.router.launch-now-title")}
      action={
        <>
          <TERM_ICON.launchNow aria-hidden />
          {t("steward.router.launch-now")}
        </>
      }
      act={() => launch.mutateAsync(withReason(reason)).then(() => done("launch-now"))}
    >
      <DFacts>
        <DFact term={t("steward.router.launch-date")}>
          <Pair steps={[dateTime(router.launch), t("steward.router.now-at", { at: new Date(now) })]} />
        </DFact>
        <DFact term={t("steward.router.periods")}>
          <WithHint hint={t("steward.router.bought-in-advance")}>
            {t("steward.router.periods-start-now", { count: router.anchoredGrants })}
          </WithHint>
        </DFact>
        <DFact term={t("steward.router.land-in")}>
          <span className="inline-flex items-center gap-1.5">
            <ServiceIcon service={serverOf(router.mode)} className="size-4 text-muted-foreground" />
            {modeName(router.mode)}
          </span>
        </DFact>
      </DFacts>
      <ReasonField value={reason} onChange={setReason} />
      <KeyNext />
    </AskThenAct>
  )
}

export function CloseDialog({ router, open, onOpenChange }: DialogProps) {
  const close = useCloseSeason()
  const [reason, setReason] = useReason(open)
  const { players } = usePlayers(open)
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={t("steward.router.close-title")}
      action={
        <>
          <TERM_ICON.closed aria-hidden />
          {t("steward.router.close-season")}
        </>
      }
      act={() => close.mutateAsync(withReason(reason)).then(() => done("close"))}
    >
      <Note tone="warn">{t("steward.router.to-closed-screen", { count: players?.members ?? 0 })}</Note>
      <DFacts>
        <DFact term={t("steward.router.launch-date")}>
          <Pair steps={[dateTime(router.launch), t("steward.router.none")]} />
        </DFact>
        <DFact term={t("steward.router.admins")}>
          {players ? t("steward.router.admins-stay", { count: players.admins }) : shown(undefined)}
        </DFact>
        <DFact term={t("steward.router.lost")}>
          <WithHint hint={t("steward.router.open-again")}>{t("steward.router.nothing")}</WithHint>
        </DFact>
      </DFacts>
      <ReasonField value={reason} onChange={setReason} />
      <KeyNext />
    </AskThenAct>
  )
}

/**
 * The switch to `target`. A switch to the active mode is the try again after a failed switch, which stops nothing.
 */
export function SwitchDialog({ router, target, open, onOpenChange }: DialogProps & { target: SeasonMode }) {
  const ask = useSwitchMode()
  const [reason, setReason] = useReason(open)
  const { players } = usePlayers(open)
  const again = target === router.mode
  const leaving = again ? null : router.mode
  const everyone = players ? players.members + players.admins : undefined
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={t("steward.router.switch-title", { mode: modeName(target) })}
      action={
        <>
          {again ? <TERM_ICON.retry aria-hidden /> : <TERM_ICON.gameMode aria-hidden />}
          {again ? t("steward.router.try-again") : t("steward.router.switch-mode")}
        </>
      }
      act={() => ask.mutateAsync({ target, ...withReason(reason) }).then(() => done("switch"))}
    >
      {leaving === "HUNGER_GAMES" ? <Note tone="warn">{t("steward.router.round-ends")}</Note> : null}
      <DFacts>
        <DFact term={t("steward.router.mode")}>
          <Pair steps={leaving ? [modeName(leaving), modeName(target)] : [modeName(target)]} />
        </DFact>
        <DFact term={t("steward.router.players-heading")}>
          <Pair steps={[shown(everyone), t("steward.router.waiting-room"), modeName(target)]} />
        </DFact>
        <DFact term={t("steward.router.admins")}>
          <Parts>
            <span>{t("steward.router.admins-wait")}</span>
            <span>{t("steward.router.server-works")}</span>
          </Parts>
        </DFact>
        {target === "SMP" ? (
          <DFact term={t("steward.router.without-access")}>
            <WithHint hint={t("steward.router.no-access-after")}>{shown(players?.withoutAccess)}</WithHint>
          </DFact>
        ) : null}
      </DFacts>
      <ReasonField value={reason} onChange={setReason} />
      <KeyNext />
    </AskThenAct>
  )
}

/** Starts or ends maintenance. `waiting` counts the players in the waiting room now. */
export function MaintenanceDialog({ router, open, onOpenChange, waiting }: DialogProps & { waiting: number }) {
  const set = useSetMaintenance()
  const [reason, setReason] = useReason(open)
  // The direction is the one the dialog opened with, so a change that lands meanwhile turns no button around.
  const [on, setOn] = useState(!router.maintenance)
  const [seen, setSeen] = useState(open)
  if (seen !== open) {
    setSeen(open)
    if (open) setOn(!router.maintenance)
  }
  const { players } = usePlayers(open && on)
  const sw = switchState(router)
  const switching = switchHolds(sw) && sw !== "failed"
  const target = router.lastSwitch?.target ?? router.mode
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={on ? t("steward.router.maintenance-title") : t("steward.router.end-maintenance-title")}
      action={
        <>
          <TERM_ICON.maintenance aria-hidden />
          {on ? t("steward.router.start-maintenance") : t("steward.router.end-maintenance")}
        </>
      }
      act={() =>
        set.mutateAsync({ on, ...withReason(reason) }).then(() => done(on ? "maintenance-on" : "maintenance-off"))
      }
    >
      {on && switching ? (
        <DFacts>
          <DFact term={t("steward.router.now-heading")}>{t("steward.router.waiting-switch", { count: waiting })}</DFact>
          <DFact term={t("steward.router.after-switch")}>
            <span className="inline-flex items-center gap-1.5">
              <ServiceIcon service={LIMBO} className="size-4 text-muted-foreground" />
              {t("steward.router.waiting-room")}
            </span>
          </DFact>
          <DFact term={t("steward.router.admins")}>{t("steward.router.admins-to", { server: modeName(target) })}</DFact>
        </DFacts>
      ) : on ? (
        <Note icon={TERM_ICON.maintenance}>
          {t("steward.router.to-waiting-room", { count: players?.members ?? 0 })}
        </Note>
      ) : (
        <Note icon={TERM_ICON.maintenance}>{t("steward.router.let-in", { count: waiting })}</Note>
      )}
      <ReasonField value={reason} onChange={setReason} />
    </AskThenAct>
  )
}

/**
 * The launch date in two steps: what the change moves, then when the season opens by itself. Only the second asks
 * for the security key.
 */
export function DateDialog({
  router,
  at,
  open,
  onOpenChange,
  onDone,
  now,
}: DialogProps & { at: Date; onDone: () => void; now: number }) {
  const setLaunch = useSetLaunch()
  const [step, setStep] = useState(1)
  const [seen, setSeen] = useState(open)
  if (seen !== open) {
    setSeen(open)
    if (open) setStep(1)
  }
  const [reason, setReason] = useReason(open)
  const { players } = usePlayers(open)
  const launched = router.opening === "LAUNCHED"
  const closed = router.opening === "CLOSED"
  const change = onOpenChange
  const days = router.launch ? Math.round(Math.abs(at.getTime() - new Date(router.launch).getTime()) / 86_400_000) : 0

  if (step === 2) {
    return (
      <AskThenAct
        open={open}
        onOpenChange={change}
        step={2}
        back={() => setStep(1)}
        title={t("steward.router.opens-title")}
        action={
          <>
            {launched ? (
              <TERM_ICON.closed aria-hidden />
            ) : closed ? (
              <CalendarPlusIcon aria-hidden />
            ) : (
              <PencilSimpleIcon aria-hidden />
            )}
            {launched
              ? t("steward.router.close-until", { at })
              : closed
                ? t("steward.router.open-on", { at })
                : t("steward.router.change-to", { at })}
          </>
        }
        act={() =>
          setLaunch.mutateAsync({ at: at.toISOString(), ...withReason(reason) }).then(() => {
            done("launch")
            setStep(1)
            onDone()
          })
        }
      >
        <DFacts>
          <DFact term={t("steward.router.opens")}>
            <span className="flex flex-col">
              <span>{dateTime(at)}</span>
              <Parts className="text-xs text-muted-foreground">
                <span>{relative(at, now)}</span>
                <span>{zone()}</span>
              </Parts>
            </span>
          </DFact>
          {launched ? <DFact term={t("steward.router.closed")}>{t("steward.router.until-then")}</DFact> : null}
        </DFacts>
        <KeyNext />
      </AskThenAct>
    )
  }

  return (
    <AskThenAct
      open={open}
      onOpenChange={change}
      step={1}
      title={
        launched
          ? t("steward.router.close-until-title", { at })
          : closed
            ? t("steward.router.set-date-title")
            : t("steward.router.date-title")
      }
      action={t("steward.router.next")}
      next={() => setStep(2)}
    >
      {launched ? <Note tone="warn">{t("steward.router.to-countdown", { count: players?.members ?? 0 })}</Note> : null}
      <DFacts>
        <DFact term={t("steward.router.launch-date")}>
          <Pair steps={[closed ? t("steward.router.not-set") : dateTime(router.launch), dateTime(at)]} />
        </DFact>
        {launched ? (
          <DFact term={t("steward.router.closed-for")}>
            <WithHint hint={t("steward.router.from-now")}>
              {t("steward.router.closed-for-length", { length: Math.max(0, (at.getTime() - now) / 1000) })}
            </WithHint>
          </DFact>
        ) : null}
        <DFact term={t("steward.router.periods")}>
          {closed
            ? t("steward.router.periods-start-then", { count: router.anchoredGrants })
            : t("steward.router.periods-move", { count: router.anchoredGrants, days })}
        </DFact>
        {launched ? (
          <DFact term={t("steward.router.admins")}>
            {players ? t("steward.router.admins-stay", { count: players.admins }) : shown(undefined)}
          </DFact>
        ) : null}
      </DFacts>
      <ReasonField value={reason} onChange={setReason} />
    </AskThenAct>
  )
}
