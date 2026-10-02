import { ArrowsClockwiseIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { toast } from "sonner"

import { useAgent, useAskForRun } from "@/lib/queries"
import { lockTitle, touches, useRunLock } from "@/lib/run-lock"
import { Button } from "@/components/ui/button"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

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
    <ResponsiveDialog open={open} onOpenChange={setOpen}>
      {!trigger ? null : compact ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <ResponsiveDialogTrigger asChild>
              <Button variant={variant} size="icon-xs" disabled={unavailable} aria-label={`Recreate ${service}`}>
                <ArrowsClockwiseIcon aria-hidden />
              </Button>
            </ResponsiveDialogTrigger>
          </TooltipTrigger>
          {/* A disabled button shows no `title` on hover, so here the tooltip carries the reason. */}
          <TooltipContent>{title}</TooltipContent>
        </Tooltip>
      ) : (
        <ResponsiveDialogTrigger asChild>
          <Button
            variant={variant}
            size={size}
            className={className}
            disabled={unavailable}
            title={title}
            aria-label={labelClassName ? "Recreate" : undefined}
          >
            <ArrowsClockwiseIcon className="size-3.5" aria-hidden />
            <span className={labelClassName}>Recreate</span>
          </Button>
        </ResponsiveDialogTrigger>
      )}

      <ResponsiveDialogContent className="sm:max-w-xl">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Recreate {service}?</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>
            A run from the image already on this host. Players are warned and moved first.
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        <ResponsiveDialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            disabled={ask.isPending}
            onClick={() =>
              ask.mutate(
                { kind: "RECREATE", services: [service] },
                {
                  onSuccess: (run) => {
                    toast.success(`Recreate entered as run #${run.id}`)
                    setOpen(false)
                  },
                  onError: (error) => toast.error("Recreate was not entered", { description: error.message }),
                },
              )
            }
          >
            Recreate
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
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
      ? agent.data.reason
      : unreachable
        ? "steward-agent is configured but not answering."
        : agent.isError
          ? "The state of steward-agent is unknown: /api/agent did not answer."
          : agent.data?.available === true
            ? `Recreate the container for ${service} from the image already on this host.`
            : "The state of steward-agent is not known yet.")
  return { unavailable, title }
}
