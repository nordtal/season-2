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
 * "Neu erzeugen" - the one thing the interface asks steward-deployer for (concept §10a.4).
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
  // onOpenChange swallow Escape and the overlay. The body would say "steward-deployer antwortet
  // nicht" while the footer said "Läuft…" and the dialog refused to close: shut in a window that
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
              : `Den Container von ${service} aus dem vorhandenen Image neu erzeugen.`
          }
        >
          <RefreshCw className="size-3.5" aria-hidden />
          Neu erzeugen
        </Button>
      </DialogTrigger>

      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{service} neu erzeugen?</DialogTitle>
          <DialogDescription asChild>
            <div className="flex flex-col gap-2 text-left">
              <p>
                Der Container wird gestoppt und aus dem Image neu angelegt, das schon auf diesem
                Host liegt. Es wird dabei <strong>nichts geladen und keine Version bewegt</strong> –
                dafür ist ein Update da.
              </p>
              <p>
                Die Volumes bleiben: Welt, Konfiguration und Jars stehen danach unverändert da.
                Weg ist der Log-Vorrat dieses Containers – Docker beginnt für den neuen bei null.
              </p>
              <p>
                <strong>Es gibt keinen Countdown und keine Ansage im Spiel.</strong> Wer gerade auf
                diesem Dienst ist, fliegt heraus. Ein Update tut das nicht; es warnt vorher.
              </p>
            </div>
          </DialogDescription>
        </DialogHeader>

        {/*
          A job that cannot be read is not a job that is running. Defaulting to "Läuft" and three
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
              {running ? "Läuft…" : "Schliessen"}
            </Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => setOpen(false)}>
                Abbrechen
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
                          ? `${failure.where} hat abgelehnt: ${failure.message}`
                          : String(failure),
                      ),
                  })
                }}
              >
                Neu erzeugen
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
              ? "compose ist mit 0 zurückgekommen."
              : state === "FAILED"
                ? `compose ist mit ${job?.exitCode ?? "?"} zurückgekommen.`
                : "compose arbeitet noch."
          }
        >
          {state === "DONE" ? "Fertig" : state === "FAILED" ? "Fehlgeschlagen" : "Läuft"}
        </StatusBadge>
        {job?.exitCode !== undefined ? (
          <span className="text-xs text-muted-foreground">Exit-Code {job.exitCode}</span>
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
