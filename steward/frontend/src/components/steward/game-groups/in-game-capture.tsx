import { CursorClickIcon, XIcon } from "@/components/ui/icons"
import { useQuery, type UseQueryResult } from "@tanstack/react-query"
import type { ReactNode } from "react"

import { api, type CaptureReadiness } from "@/lib/api"
import { RECONCILE } from "@/lib/live"
import { keys } from "@/lib/query-keys"
import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"

/** Shared readiness query across all four Steward-to-SMP in-game capture modes. */
export function useCaptureReadiness(): UseQueryResult<CaptureReadiness> {
  return useQuery({
    queryKey: keys.captureReadiness,
    queryFn: () => api<CaptureReadiness>("/api/smp/capture"),
    // No topic tells when a player joins or leaves, so it is read again whenever the admin returns from the game.
    refetchInterval: RECONCILE,
    refetchOnMount: "always",
    refetchOnWindowFocus: "always",
  })
}

/** Why the admin's player cannot run an in-game capture now, or null when ready or still loading. */
export function captureBlockedBy(readiness: CaptureReadiness | undefined): string | null {
  if (readiness === undefined || readiness.taker === "READY") return null
  return readiness.taker === "UNLINKED" ? t("steward.answer.preview-unlinked") : t("steward.answer.take-in-offline")
}

/** Shared start button for any in-game capture mode. */
export function CaptureStartButton({
  label,
  disabled,
  icon,
  onStart,
}: {
  label: string
  disabled: boolean
  icon?: ReactNode
  onStart: () => void
}) {
  return (
    <Button type="button" variant="outline" disabled={disabled} className="max-sm:w-full" onClick={onStart}>
      {icon ?? <CursorClickIcon aria-hidden />}
      {label}
    </Button>
  )
}

/** Shared waiting / cancel / done controls while an in-game capture session is active. */
export function CaptureSessionControls({
  notice = t("steward.game.capture-waiting"),
  disabled,
  cancelLabel,
  doneLabel,
  onCancel,
  onDone,
}: {
  notice?: string
  disabled?: boolean
  cancelLabel: string
  doneLabel?: string
  onCancel: () => void
  onDone?: () => void
}) {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted-foreground">{notice}</p>
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant={onDone ? "secondary" : "destructive"}
          className="max-sm:w-full"
          disabled={disabled}
          onClick={onCancel}
        >
          {onDone ? null : <XIcon aria-hidden />}
          {cancelLabel}
        </Button>
        {onDone && doneLabel ? (
          <Button type="button" className="max-sm:w-full" disabled={disabled} onClick={onDone}>
            {doneLabel}
          </Button>
        ) : null}
      </div>
    </div>
  )
}

/** Shows why the admin's player cannot capture right now, omitted when ready or disabled. */
export function CaptureBlockedNote({ blocked, disabled }: { blocked: string | null; disabled: boolean }) {
  return blocked !== null && !disabled ? <p className="text-xs text-muted-foreground">{blocked}</p> : null
}
