import { TERM_ICON } from "@/components/steward/term-icons"
import { useState } from "react"
import { toast } from "sonner"

import { useAgent, useAskForRun, useServices } from "@/lib/queries"
import { lockTitle, touches, useRunLock } from "@/lib/run-lock"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Hint, restingMode, runKind } from "@/components/steward/status"
import { message, t } from "@/lib/texts"

/** It is a run like any other, so players are warned and moved before the container goes. */
export function RecreateButton({
  service,
  variant = "outline",
  compact = false,
  size,
  labelClassName,
  className,
  open: openProp,
  onOpenChange,
  trigger = true,
}: {
  service: string
  /**
   * `outline` on a page. `ghost` in the network picture, where ten outlined buttons would compete with the lines.
   */
  variant?: "outline" | "ghost"
  /**
   * The icon alone, for a network card too narrow for the word. `aria-label` still says "Recreate <service>".
   */
  compact?: boolean
  size?: "default" | "sm"
  labelClassName?: string
  className?: string
  /** Lets the caller open and close the dialog, for the service page's ⋯ menu. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** `false` draws the dialog alone, for a caller that opens it from somewhere else. */
  trigger?: boolean
}) {
  const [ownOpen, setOwnOpen] = useState(false)
  const open = openProp ?? ownOpen
  const setOpen = (next: boolean) => {
    setOwnOpen(next)
    onOpenChange?.(next)
  }
  const ask = useAskForRun()
  const { unavailable, title } = useRecreateGate(service)

  if (service === "steward-agent") return null

  return (
    <>
      {!trigger ? null : compact ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant={variant}
              size="icon-xs"
              disabled={unavailable}
              aria-label={t("steward.service-page.recreate-service", { service })}
              onClick={() => setOpen(true)}
            >
              <TERM_ICON.recreate aria-hidden />
            </Button>
          </TooltipTrigger>
          {/* A disabled button shows no `title` on hover, so the tooltip carries the reason. */}
          <TooltipContent>{title}</TooltipContent>
        </Tooltip>
      ) : (
        <Hint tip={title}>
          <Button
            variant={variant}
            size={size}
            className={className}
            disabled={unavailable}
            aria-label={labelClassName ? runKind("RECREATE") : undefined}
            onClick={() => setOpen(true)}
          >
            <TERM_ICON.recreate className="size-3.5" aria-hidden />
            <span className={labelClassName}>{runKind("RECREATE")}</span>
          </Button>
        </Hint>
      )}
      <AskThenAct
        open={open}
        onOpenChange={setOpen}
        title={t("steward.service-page.recreate-title", { service })}
        action={runKind("RECREATE")}
        act={() =>
          ask
            .mutateAsync({ kind: "RECREATE", services: [service] })
            .then((run) => toast.success(t("steward.operations.entered", { kind: runKind("RECREATE"), run: run.id })))
        }
      />
    </>
  )
}

/**
 * It is a hook of its own, so the service page's menu disables the same item for the same reason.
 */
export function useRecreateGate(service: string): { unavailable: boolean; title: string | undefined } {
  const agent = useAgent()
  /**
   * A run owns the containers in its scope, and recreating a container that the run is about to stop would race the run.
   */
  const run = useRunLock().run
  /** The inactive mode's server only starts, so nothing replaces a container that is meant to rest. */
  const row = useServices().data?.services.find((each) => each.service === service)
  const rests = row !== undefined && restingMode(row)
  const lock =
    run && touches(run, service) ? { locked: true, title: lockTitle(run) } : { locked: false, title: undefined }
  const unreachable = agent.data?.available === true && agent.data.reachable === false
  const unavailable = agent.data?.available === false || unreachable || agent.isError || lock.locked || rests
  const title =
    lock.title ??
    (rests ? t("steward.service-page.resting-first") : undefined) ??
    (agent.data?.available === false
      ? agent.data.reason && message(agent.data.reason)
      : unreachable
        ? t("steward.service-page.agent-silent")
        : agent.isError
          ? t("steward.service-page.agent-unknown")
          : agent.data?.available === true
            ? undefined
            : t("steward.service-page.agent-not-yet"))
  return { unavailable, title }
}
