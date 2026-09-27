import { ArrowsClockwiseIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { toast } from "sonner"

import { ApiError } from "@/lib/api"
import { useDeployer, useDeployerJob, useRecreate } from "@/lib/queries"
import { useRunLock } from "@/lib/run-lock"
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
import { ScrollArea } from "@/components/ui/scroll-area"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Failure } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"

/**
 * Recreates one container through steward-deployer from the image on this host, showing compose's output verbatim.
 *
 * It is no update: no countdown, no announcement, and the dialog says so before anything happens.
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
  const [jobId, setJobId] = useState<string | null>(null)
  const recreate = useRecreate()
  const job = useDeployerJob(open ? jobId : null)
  const { unavailable, title } = useRecreateGate(service)

  /** The deployer refuses to recreate itself, so its button is not drawn at all. */
  if (service === "steward-deployer") return null

  /** Guarded on `job.error`, since `job.data` survives a failed poll and would keep the dialog shut open forever. */
  const running = recreate.isPending || (!job.error && job.data?.state === "RUNNING")

  return (
    <ResponsiveDialog
      open={open}
      onOpenChange={(next) => {
        /** A running compose operation cannot be cancelled, so the job is only forgotten once it has ended. */
        if (!next && running) return
        setOpen(next)
        if (!next) {
          setJobId(null)
          recreate.reset()
        }
      }}
    >
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

      <ResponsiveDialogContent className="max-w-[calc(100%-2rem)] sm:max-w-xl">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Recreate {service}?</ResponsiveDialogTitle>
          <ResponsiveDialogDescription asChild>
            <div className="flex flex-col gap-2 text-left">
              <p>
                <strong>There is no countdown and no announcement in game.</strong> Anyone on this service right now is
                thrown out.
              </p>
            </div>
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {/* A job that cannot be read shows its failure, never "Running", since the container may be down. */}
        {jobId ? (
          job.error ? (
            <Failure error={job.error} onRetry={() => void job.refetch()} />
          ) : (
            <Output job={job.data} />
          )
        ) : null}

        <ResponsiveDialogFooter>
          {jobId ? (
            <Button variant="outline" onClick={() => setOpen(false)} disabled={running}>
              {running ? "Running…" : "Close"}
            </Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button
                disabled={recreate.isPending}
                onClick={() => {
                  recreate.mutate(service, {
                    onSuccess: (started) => setJobId(started.id),
                    /** Names the refusing service through `where`, which `String(apiError)` would drop. */
                    onError: (failure) =>
                      toast.error(
                        failure instanceof ApiError ? `${failure.where} refused: ${failure.message}` : String(failure),
                      ),
                  })
                }}
              >
                Recreate
              </Button>
            </>
          )}
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
  const deployer = useDeployer()
  /** A run under way owns the containers, and recreating one it is about to stop would race it. */
  const lock = useRunLock()
  /** An answer that the deployer is unavailable or unreachable locks the button; a first load leaves it open. */
  const unreachable = deployer.data?.available === true && deployer.data.reachable === false
  const unavailable = deployer.data?.available === false || unreachable || deployer.isError || lock.locked
  const title =
    lock.title ??
    (deployer.data?.available === false
      ? deployer.data.reason
      : unreachable
        ? "steward-deployer is configured but not answering."
        : deployer.isError
          ? "The state of steward-deployer is unknown: /api/deployer did not answer."
          : deployer.data?.available === true
            ? `Recreate the container for ${service} from the image already on this host.`
            : "The state of steward-deployer is not known yet.")
  return { unavailable, title }
}

function Output({ job }: { job: ReturnType<typeof useDeployerJob>["data"] }) {
  const state = job?.state ?? "RUNNING"
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <StatusBadge
          tone={state === "DONE" ? "ok" : state === "FAILED" ? "down" : "idle"}
          tipContent={
            state === "DONE"
              ? "compose came back with 0."
              : state === "FAILED"
                ? `compose came back with ${job?.exitCode ?? "?"}.`
                : "compose is still working."
          }
        >
          {state === "DONE" ? "Done" : state === "FAILED" ? "Failed" : "Running"}
        </StatusBadge>
        {job?.exitCode !== undefined ? (
          <span className="text-xs text-muted-foreground">Exit code {job.exitCode}</span>
        ) : null}
      </div>
      <ScrollArea className="h-48 rounded-md border border-border bg-muted/40">
        <pre className="px-3 py-2 font-mono text-xs whitespace-pre-wrap">
          {job?.lines?.length ? job.lines.join("\n") : "…"}
        </pre>
      </ScrollArea>
    </div>
  )
}
