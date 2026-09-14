import { useState } from "react"
import { RefreshCw } from "lucide-react"
import { toast } from "sonner"

import { ApiError } from "@/lib/api"
import { useDeployer, useDeployerJob, useRecreate } from "@/lib/queries"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { ScrollArea } from "@/components/ui/scroll-area"
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
}: {
  service: string
  size?: "sm" | "default"
}) {
  const deployer = useDeployer()
  const [open, setOpen] = useState(false)
  const [jobId, setJobId] = useState<string | null>(null)
  const recreate = useRecreate()
  const job = useDeployerJob(open ? jobId : null)

  // The deployer refuses to recreate itself - it is the container the request travels through - so
  // the button for it is not drawn at all rather than drawn and then refused.
  if (service === "steward-deployer") return null

  const unavailable = deployer.data?.available === false

  // `job.data` survives a failed poll, so without the error guard this stayed true forever once one
  // answer had said RUNNING - and `running` is what disables the close button AND what makes
  // onOpenChange swallow Escape and the overlay. The body would say "steward-deployer is not
  // answering" while the footer said "Running…" and the dialog refused to close: shut in a window that
  // has just announced nothing more is coming. Same guard `refetchInterval` uses in queries.ts.
  const running = recreate.isPending || (!job.error && job.data?.state === "RUNNING")

  return (
    <Dialog
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
      <DialogTrigger asChild>
        <Button
          variant="outline"
          size={size}
          disabled={unavailable}
          title={
            unavailable
              ? deployer.data?.reason
              : `Recreate the container for ${service} from the image already on this host.`
          }
        >
          <RefreshCw className="size-3.5" aria-hidden />
          Recreate
        </Button>
      </DialogTrigger>

      <DialogContent className="max-w-[calc(100%-2rem)] sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Recreate {service}?</DialogTitle>
          <DialogDescription asChild>
            <div className="flex flex-col gap-2 text-left">
              <p>
                The container is stopped and created again from the image already on this host.
                <strong>Nothing is downloaded and no version is moved</strong> - that is what an
                update is for.
              </p>
              <p>
                The volumes stay: world, configuration and jars are unchanged afterwards. What is
                gone is this container's log history - Docker starts the new one at zero.
              </p>
              <p>
                <strong>There is no countdown and no announcement in game.</strong> Anyone on this
                service right now is thrown out. An update does not do that; it warns first.
              </p>
            </div>
          </DialogDescription>
        </DialogHeader>

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

        <DialogFooter>
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
        </DialogFooter>
      </DialogContent>
    </Dialog>
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
