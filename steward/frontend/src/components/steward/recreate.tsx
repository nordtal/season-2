import { ArrowsClockwiseIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { toast } from "sonner"

import { useAgent, useAskForRun } from "@/lib/queries"
import { lockTitle, touches, useRunLock } from "@/lib/run-lock"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { runKind } from "@/components/steward/status"
import { message, t } from "@/lib/texts"

/**
 * Asks for a RECREATE run of one service: its container is made again from the image already on this host.
 *
 * It is a run like any other, so players are warned and moved before the container goes.
 */
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
  /** `outline` on a page, `ghost` in the network picture, where ten outlined buttons would compete with the lines. */
  variant?: "outline" | "ghost"
  /** The icon alone, for a network card too narrow for the word; `aria-label` still says "Recreate <service>". */
  compact?: boolean
  size?: "default" | "sm"
  /** Lets a page hide the word below a breakpoint; the button keeps it as its accessible name. */
  labelClassName?: string
  className?: string
  /** Steerable from outside, for the service page's ⋯ menu. */
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

  /** The agent refuses to recreate itself, so its button is not drawn at all. */
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
              <ArrowsClockwiseIcon aria-hidden />
            </Button>
          </TooltipTrigger>
          {/* A disabled button shows no `title` on hover, so here the tooltip carries the reason. */}
          <TooltipContent>{title}</TooltipContent>
        </Tooltip>
      ) : (
        <Button
          variant={variant}
          size={size}
          className={className}
          disabled={unavailable}
          title={title}
          aria-label={labelClassName ? runKind("RECREATE") : undefined}
          onClick={() => setOpen(true)}
        >
          <ArrowsClockwiseIcon className="size-3.5" aria-hidden />
          <span className={labelClassName}>{runKind("RECREATE")}</span>
        </Button>
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
 * Whether Recreate can be offered, and the sentence that says why not.
 *
 * A hook of its own, so the service page's menu greys out the same item for the same reason.
 */
export function useRecreateGate(service: string): { unavailable: boolean; title: string | undefined } {
  const agent = useAgent()
  /** A run owns the containers in its scope, and recreating one it is about to stop would race it. */
  const run = useRunLock().run
  const lock =
    run && touches(run, service) ? { locked: true, title: lockTitle(run) } : { locked: false, title: undefined }
  /** An answer that the agent is unavailable or unreachable locks the button; a first load leaves it open. */
  const unreachable = agent.data?.available === true && agent.data.reachable === false
  const unavailable = agent.data?.available === false || unreachable || agent.isError || lock.locked
  const title =
    lock.title ??
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
