import { useState } from "react"
import { toast } from "sonner"

import { errorMessage, type PackStatus } from "@/lib/api"
import { bytes, dateTime, time } from "@/lib/format"
import { useAskForRun, useBuildPackAgain, usePackStatus } from "@/lib/queries"
import { t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Pill } from "@/components/steward/milestones/parts"
import { QueryState } from "@/components/steward/query-state"
import { useRecreateGate } from "@/components/steward/recreate"
import { Hint, runKind } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { SpinnerIcon } from "@/components/ui/icons"

/**
 * The state of the resource pack the prizes are drawn into: the newest build, a way to build again after a failure,
 * and the live swap that sends the active pack to the players on the SMP.
 */
export function PackState() {
  const status = usePackStatus()
  return (
    <QueryState query={status} rows={1}>
      {(data) => <PackRow status={data} />}
    </QueryState>
  )
}

function PackRow({ status }: { status: PackStatus }) {
  const again = useBuildPackAgain()
  const ask = useAskForRun()
  const gate = useRecreateGate("smp")
  const [asking, setAsking] = useState(false)
  const { latest, active } = status
  const building = latest?.state === "BUILDING"
  const failed = latest?.state === "FAILED"
  const swapTitle = building ? t("steward.game.pack-swap-wait") : gate.title
  const swapOff = active === undefined || building || gate.unavailable

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Pill tip={t("steward.game.pack")}>
        <TERM_ICON.pack aria-label={t("steward.game.pack")} className="size-3.5 shrink-0" />
        {building ? (
          <>
            <SpinnerIcon aria-hidden className="size-3.5 animate-spin" />
            {t("steward.game.pack-building", { at: time(latest.started) })}
          </>
        ) : active ? (
          t("steward.game.pack-active", { size: bytes(active.sizeBytes), at: dateTime(active.finished) })
        ) : (
          t("steward.game.pack-none")
        )}
      </Pill>
      {failed ? (
        <>
          <Pill className="bg-destructive/10 text-destructive">
            <TERM_ICON.warning aria-hidden className="size-3.5 shrink-0" />
            {t("steward.game.pack-failed", { reason: latest.error ?? "" })}
          </Pill>
          <Button
            variant="outline"
            size="xs"
            disabled={again.isPending}
            onClick={() =>
              again.mutate(undefined, {
                onSuccess: () => toast.success(t("steward.game.pack-again-sent")),
                onError: (error) =>
                  toast.error(t("steward.game.pack-again-failed"), { description: errorMessage(error) }),
              })
            }
          >
            <TERM_ICON.retry aria-hidden className="size-3.5" />
            {t("steward.game.pack-again")}
          </Button>
        </>
      ) : null}
      <Hint tip={swapTitle}>
        <Button variant="outline" size="xs" disabled={swapOff} onClick={() => setAsking(true)}>
          <TERM_ICON.pack aria-hidden className="size-3.5" />
          {t("steward.game.pack-swap")}
        </Button>
      </Hint>
      <AskThenAct
        open={asking}
        onOpenChange={setAsking}
        title={t("steward.game.pack-swap-ask")}
        description={t("steward.game.pack-swap-note")}
        action={t("steward.game.pack-swap")}
        act={() =>
          ask
            .mutateAsync({ kind: "LIVE_SWAP" })
            .then((run) => toast.success(t("steward.operations.entered", { kind: runKind("LIVE_SWAP"), run: run.id })))
        }
      />
    </div>
  )
}
