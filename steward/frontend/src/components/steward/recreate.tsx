import { TERM_ICON } from "@/components/steward/term-icons"
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
 * Asks for a RECREATE run of one service. The run makes the container again from the image that is already on this host.
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
  /**
   * `outline` on a page. `ghost` in the network picture, where ten outlined buttons would compete with the lines.
   */
  variant?: "outline" | "ghost"
  /**
   * The icon alone, for a network card too narrow for the word. `aria-label` still says "Recreate <service>".
   */
  compact?: boolean
  size?: "default" | "sm"
  /**
   * Lets a page hide the word below a breakpoint. The button keeps the word as its accessible name.
   */
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

  /** The agent refuses to recreate itself, so its button is not drawn. */
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
        <Button
          variant={variant}
          size={size}
          className={className}
          disabled={unavailable}
          title={title}
          aria-label={labelClassName ? runKind("RECREATE") : undefined}
          onClick={() => setOpen(true)}
        >
          <TERM_ICON.recreate className="size-3.5" aria-hidden />
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
 * Returns whether Recreate can be offered, and the sentence that says why not.
 *
 * It is a hook of its own, so the service page's menu disables the same item for the same reason.
 */
export function useRecreateGate(service: string): { unavailable: boolean; title: string | undefined } {
  const agent = useAgent()
  /**
   * A run owns the containers in its scope, and recreating a container that the run is about to stop would race the run.
   */
  const run = useRunLock().run
  const lock =
    run && touches(run, service) ? { locked: true, title: lockTitle(run) } : { locked: false, title: undefined }
  /**
   * An answer that the agent is unavailable or unreachable locks the button. The first load leaves it open.
   */
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
