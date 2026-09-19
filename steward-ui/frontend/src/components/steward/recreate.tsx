import { ArrowsClockwiseIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { toast } from "sonner"

import { ApiError } from "@/lib/api"
import { useDeployer, useDeployerJob, useRecreate } from "@/lib/queries"
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
 * "Recreate" - the one thing the interface asks steward-deployer for (concept §10a.4).
 *
 * **It is not an update, and the dialog says so before anything happens.** An update is a row in
 * `update_request`: steward-worker claims it, warns every player in the game, counts down and
 * writes a report. This is one `docker compose up --force-recreate` on one container, from the
 * image that is already on this host - no countdown, no announcement, and the service is gone for
 * as long as it takes to come back. On a Minecraft server that is a disconnect for whoever is
 * online. Putting that sentence in front of the button is cheaper than explaining it afterwards.
 *
 * **The output is compose's own.** The job is polled while it runs and its lines are shown
 * verbatim, because "Container nordtal-s2-smp-1  Recreated" is the answer and any summary of it
 * would be this interface's opinion about the answer.
 */
export function RecreateButton({
  service,
  size = "sm",
  variant = "outline",
  compact = false,
}: {
  service: string
  size?: "sm" | "default"
  /**
   * `outline` on a page, `ghost` on a picture.
   *
   * Till, 2026-09-17 on the network view: the thick recreate button may become a ghost. It is not
   * made ghost everywhere, and the reason is what the two places are: on
   * `/services/<name>` recreating **is** the action of the page and a filled-enough button is
   * honest about that; inside a node of a topology picture it is one of ten identical toolbars,
   * and ten outlined buttons are ten little rectangles competing with the lines that are the
   * actual subject. Same component, same dialog, same warning - only the weight differs.
   */
  variant?: "outline" | "ghost"
  /**
   * The icon alone, named rather than labelled.
   *
   * A node in the network view is 144px wide (steward/81, 2026-09-17: every card the same size,
   * and smaller), and the word "Recreate" is most of that. The
   * accessible name keeps the word - `aria-label` still says "Recreate <service>" - so a screen
   * reader, a test and the tooltip all read the same thing a sighted user reads on the page
   * version. It is never the default: a button whose whole meaning is a warning does not lose its
   * word anywhere there is room for it.
   */
  compact?: boolean
}) {
  const deployer = useDeployer()
  const [open, setOpen] = useState(false)
  const [jobId, setJobId] = useState<string | null>(null)
  const recreate = useRecreate()
  const job = useDeployerJob(open ? jobId : null)

  // The deployer refuses to recreate itself - it is the container the request travels through - so
  // the button for it is not drawn at all rather than drawn and then refused.
  if (service === "steward-deployer") return null

  // Five states, not two (steward/97): `available === false` with a reason steward-deployer gave,
  // `available: true` but `reachable: false`, an error on `/api/deployer` itself (404, network,
  // steward-ui down), the ordinary first load before anything has answered, and finally the one
  // state the confident title belongs to. Only the last may be claimed with confidence.
  //
  // What separates the states that lock the button from the one that does not is whether an answer
  // exists, not whether it is good news: `available: false` and `reachable: false` are both
  // *measurements* - the second is a live GET /api/health the endpoint performed on our behalf
  // (`InternalClient#isReachable`) - and an error is the measurement failing. A first load is none
  // of those, so it stays open: a slow query is not a broken one, and going grey for it would be
  // the same dishonesty in the other direction.
  //
  // `reachable` was carried in the payload and read by nobody until now, which is the same defect
  // one layer down from the one this ticket is about: a configured deployer whose container is not
  // answering looked exactly like a healthy one, down to the sentence promising the image is
  // already on this host.
  const unreachable = deployer.data?.available === true && deployer.data.reachable === false
  const unavailable = deployer.data?.available === false || unreachable || deployer.isError
  const title = deployer.data?.available === false
    ? deployer.data.reason
    : unreachable
      ? "steward-deployer is configured but not answering."
      : deployer.isError
        ? "The state of steward-deployer is unknown: /api/deployer did not answer."
        : deployer.data?.available === true
          ? `Recreate the container for ${service} from the image already on this host.`
          : "The state of steward-deployer is not known yet."

  // `job.data` survives a failed poll, so without the error guard this stayed true forever once one
  // answer had said RUNNING - and `running` is what disables the close button AND what makes
  // onOpenChange swallow Escape and the overlay. The body would say "steward-deployer is not
  // answering" while the footer said "Running…" and the dialog refused to close: shut in a window that
  // has just announced nothing more is coming. Same guard `refetchInterval` uses in queries.ts.
  const running = recreate.isPending || (!job.error && job.data?.state === "RUNNING")

  return (
    <ResponsiveDialog
      open={open}
      onOpenChange={(next) => {
        // A running compose operation is not cancelled by closing the window - nothing here could
        // cancel it - so the job is only forgotten once it has ended, and reopening shows it again.
        if (!next && running) return
        setOpen(next)
        if (!next) {
          setJobId(null)
          recreate.reset()
        }
      }}
    >
      {compact ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <ResponsiveDialogTrigger asChild>
              <Button
                variant={variant}
                size="icon-xs"
                disabled={unavailable}
                aria-label={`Recreate ${service}`}
              >
                <ArrowsClockwiseIcon aria-hidden />
              </Button>
            </ResponsiveDialogTrigger>
          </TooltipTrigger>
          {/* The `title` attribute is what carries the deployer's own reason on the page version,
              and a disabled button never shows one on hover in any browser. In the picture the
              reason is the tooltip, which a disabled trigger still opens. */}
          <TooltipContent>{title}</TooltipContent>
        </Tooltip>
      ) : (
        <ResponsiveDialogTrigger asChild>
          <Button variant={variant} size={size} disabled={unavailable} title={title}>
            <ArrowsClockwiseIcon className="size-3.5" aria-hidden />
            Recreate
          </Button>
        </ResponsiveDialogTrigger>
      )}

      <ResponsiveDialogContent className="max-w-[calc(100%-2rem)] sm:max-w-xl">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Recreate {service}?</ResponsiveDialogTitle>
          <ResponsiveDialogDescription asChild>
            {/*
              ONE SENTENCE, AND IT IS THE DANGEROUS ONE (2026-09-14). What the dialog used to also
              say, and what is true: the container is stopped and created again from the image
              already on this host, so nothing is downloaded and no version is moved - that is what
              an update is for. The volumes stay, so world, configuration and jars are unchanged
              afterwards; what is gone is this container's log history, because Docker starts the
              new one at zero. None of that is what somebody about to press the button needs to be
              warned about, so none of it is on the screen any more.
            */}
            <div className="flex flex-col gap-2 text-left">
              <p>
                <strong>There is no countdown and no announcement in game.</strong> Anyone on this
                service right now is thrown out.
              </p>
            </div>
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {/*
          A job that cannot be read is not a job that is running. Defaulting to "Running" and three
          dots said exactly the same thing as a compose run in progress, which is the one situation
          where an operator most needs to know the difference: the container may already be down.
        */}
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
                    // Named, not stringified: String(apiError) is "ApiError: …" and says nothing
                    // about which of the three services refused, which is the whole reason
                    // ApiError carries `where`.
                    onError: (failure) =>
                      toast.error(
                        failure instanceof ApiError
                          ? `${failure.where} refused: ${failure.message}`
                          : String(failure),
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

function Output({ job }: { job: ReturnType<typeof useDeployerJob>["data"] }) {
  const state = job?.state ?? "RUNNING"
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <StatusBadge
          tone={state === "DONE" ? "ok" : state === "FAILED" ? "down" : "idle"}
          title={
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
