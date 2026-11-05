import { useState } from "react"

import type { Router } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { t } from "@/lib/texts"
import { Actor } from "@/components/steward/entity"
import { StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { MaintenanceDialog } from "@/components/steward/router/router-dialogs"
import { Fact, Facts, Parts, RouterCard } from "@/components/steward/router/router-parts"
import { lengthOf, secondsBetween } from "@/components/steward/router/router-state"

/** Maintenance: whether the gate holds members in the waiting room, since when, and how many wait. */
export function MaintenanceCard({ router, waiting, now }: { router: Router; waiting: number; now: number }) {
  const [open, setOpen] = useState(false)
  const on = router.maintenance
  const last = router.lastMaintenance
  const launched = router.opening === "LAUNCHED"

  const badges = (
    <>
      {!on && !launched ? (
        <StatusBadge tone="idle" tipContent={t("steward.router.no-effect-tip")}>
          {t("steward.router.no-effect")}
        </StatusBadge>
      ) : null}
      <StatusBadge tone={on ? "warn" : "idle"}>{t("steward.router.maintenance-on", { on: String(on) })}</StatusBadge>
    </>
  )

  return (
    <RouterCard icon={TERM_ICON.maintenance} title={t("steward.router.maintenance")} badges={badges}>
      <Facts>
        {on ? (
          <>
            <Fact term={t("steward.router.since")} hint={last ? relative(last.on, now) : undefined}>
              <span className="tnum">{dateTime(last?.on)}</span>
            </Fact>
            <Fact term={t("steward.router.waiting")} hint={t("steward.router.players", { count: waiting })}>
              <span className="tnum">{waiting}</span>
            </Fact>
          </>
        ) : (
          <Fact term={t("steward.router.last-on")}>
            {last ? (
              <Parts className="tnum">
                <span className="whitespace-nowrap">{dateTime(last.on)}</span>
                <span className="whitespace-nowrap">{lengthOf(secondsBetween(last.on, last.off, now))}</span>
                <Actor kind={last.byKind} id={last.byId} />
              </Parts>
            ) : (
              <span className="text-muted-foreground">{t("steward.router.never")}</span>
            )}
          </Fact>
        )}
      </Facts>
      <Button
        type="button"
        variant={on ? "default" : "outline"}
        className="mt-auto w-full"
        onClick={() => setOpen(true)}
      >
        <TERM_ICON.maintenance aria-hidden />
        {on ? t("steward.router.end-maintenance") : t("steward.router.start-maintenance")}
      </Button>
      <MaintenanceDialog router={router} open={open} onOpenChange={setOpen} waiting={waiting} />
    </RouterCard>
  )
}
