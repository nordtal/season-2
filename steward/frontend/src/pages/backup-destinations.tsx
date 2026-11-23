import { useEffect, useRef } from "react"
import { useNavigate, useSearch } from "@tanstack/react-router"
import { toast } from "sonner"

import { errorMessage, type BackupAccount, type BackupDestinations, type BackupSeries } from "@/lib/api"
import { bytes } from "@/lib/format"
import {
  useBackupDestinations,
  useChooseBackupAccount,
  useConnectGoogle,
  useDisconnectBackupAccount,
  useRecoveryFile,
} from "@/lib/queries"
import { t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Panel } from "@/components/steward/panel"
import { Empty, QueryState, SkeletonText } from "@/components/steward/query-state"
import { When } from "@/components/steward/when"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/** The select's value for no account, because a select item cannot have an empty value. */
const NONE = "none"

const RESULTS = ["connected", "declined", "expired", "unconfigured", "failed"] as const

/**
 * The Google accounts the backups copy to, and where each series copies. A secret is never drawn.
 */
export function BackupDestinationsPanel() {
  const destinations = useBackupDestinations()
  useGoogleResult()

  return (
    <Panel title={t("steward.backups.copies")}>
      <QueryState query={destinations}>
        {(answer) =>
          !answer ? (
            <SkeletonText width="long" />
          ) : !answer.offered ? (
            <Empty
              title={t("steward.backups.copies-unconfigured")}
              note={t("steward.backups.copies-unconfigured-note")}
            />
          ) : (
            <Destinations destinations={answer} />
          )
        }
      </QueryState>
    </Panel>
  )
}

/** Names the outcome of a consent the callback sent back, once, and drops it from the address. */
function useGoogleResult() {
  const search: { google?: unknown } = useSearch({ strict: false })
  const navigate = useNavigate()
  const shown = useRef(false)
  const result = RESULTS.find((known) => known === search.google)

  useEffect(() => {
    if (!result || shown.current) return
    shown.current = true
    const text = t("steward.backups.google-result", { result })
    if (result === "connected") toast.success(text)
    else toast.error(text)
    void navigate({ to: "/operations/backups", replace: true })
  }, [result, navigate])
}

function Destinations({ destinations }: { destinations: BackupDestinations }) {
  const connected = destinations.accounts.filter((account) => account.connected)
  return (
    <div className="flex flex-col gap-6">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {destinations.series.map((series) => (
          <SeriesCopy key={series.series} series={series} accounts={connected} />
        ))}
      </div>
      <Accounts destinations={destinations} />
    </div>
  )
}

function SeriesCopy({ series, accounts }: { series: BackupSeries; accounts: BackupAccount[] }) {
  const choose = useChooseBackupAccount()
  const id = `copy-${series.series}`

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{t("steward.backups.series", { series: series.series })}</Label>
      <Select
        value={series.accountId ?? NONE}
        disabled={choose.isPending}
        onValueChange={(value) =>
          choose
            .mutateAsync({ series: series.series, accountId: value === NONE ? null : value })
            .then(() => toast.success(t("steward.backups.destination-saved")))
            .catch((failure: unknown) => toast.error(errorMessage(failure)))
        }
      >
        <SelectTrigger id={id} className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={NONE}>{t("steward.backups.no-account")}</SelectItem>
          {accounts.map((account) => (
            <SelectItem key={account.id} value={account.id}>
              {account.email}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <CopyState series={series} />
    </div>
  )
}

function CopyState({ series }: { series: BackupSeries }) {
  if (!series.accountId) return null
  return (
    <div className="flex flex-col gap-1 text-xs text-muted-foreground">
      <p>
        {series.lastCopiedAt ? (
          <>
            {t("steward.backups.copied")} <When at={series.lastCopiedAt} />
            {series.lastBytes != null ? `, ${bytes(series.lastBytes)}` : ""}
          </>
        ) : (
          t("steward.backups.not-copied")
        )}
      </p>
      {series.lastError ? (
        <p className="text-warning">
          {t("steward.backups.copy-failed")} {series.lastFailedAt ? <When at={series.lastFailedAt} /> : null}:{" "}
          {series.lastError}
        </p>
      ) : null}
    </div>
  )
}

function Accounts({ destinations }: { destinations: BackupDestinations }) {
  const connect = useConnectGoogle()

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium">{t("steward.backups.accounts")}</h3>
        <Button
          variant="outline"
          size="sm"
          disabled={connect.isPending}
          onClick={() =>
            connect
              .mutateAsync()
              .then((start) => window.location.assign(start.authorizeUrl))
              .catch((failure: unknown) => toast.error(errorMessage(failure)))
          }
        >
          {t("steward.backups.connect")}
        </Button>
      </div>
      {destinations.accounts.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("steward.backups.no-accounts")}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border">
          {destinations.accounts.map((account) => (
            <Account key={account.id} account={account} destinations={destinations} />
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">{t("steward.backups.recovery-note")}</p>
    </div>
  )
}

function Account({ account, destinations }: { account: BackupAccount; destinations: BackupDestinations }) {
  const repositories = destinations.repositories.filter((repository) => repository.accountId === account.id)

  return (
    <li className="flex flex-col gap-2 py-3 sm:flex-row sm:items-start sm:justify-between">
      <div className="flex min-w-0 flex-col gap-1">
        <p className="text-sm font-medium break-all">{account.email}</p>
        <p className="text-xs text-muted-foreground">
          {account.connected
            ? t("steward.backups.account-folder", { folder: account.folderName })
            : t("steward.backups.account-disconnected")}
        </p>
        {repositories.map((repository) =>
          repository.recoveryDownloadedAt ? (
            <p key={repository.id} className="text-xs text-muted-foreground">
              {t("steward.backups.recovery-taken", { series: repository.series })}{" "}
              <When at={repository.recoveryDownloadedAt} />
            </p>
          ) : (
            <RecoveryButton key={repository.id} accountId={account.id} series={repository.series} />
          ),
        )}
      </div>
      {account.connected ? <Disconnect account={account} /> : null}
    </li>
  )
}

function RecoveryButton({ accountId, series }: { accountId: string; series: string }) {
  const recovery = useRecoveryFile()

  return (
    <Button
      variant="link"
      size="sm"
      className="h-auto justify-start p-0 text-xs"
      disabled={recovery.isPending}
      onClick={() =>
        recovery
          .mutateAsync({ accountId, series })
          .then((file) => save(file.name, file.text))
          .catch((failure: unknown) => toast.error(errorMessage(failure)))
      }
    >
      {t("steward.backups.recovery", { series })}
    </Button>
  )
}

function Disconnect({ account }: { account: BackupAccount }) {
  const disconnect = useDisconnectBackupAccount()

  return (
    <AskThenAct
      trigger={
        <Button variant="outline" size="sm">
          {t("steward.backups.disconnect")}
        </Button>
      }
      title={t("steward.backups.disconnect-title", { email: account.email })}
      description={t("steward.backups.disconnect-note")}
      action={t("steward.backups.disconnect")}
      destructive
      act={() => disconnect.mutateAsync(account.id).then(() => toast.success(t("steward.backups.disconnected")))}
    />
  )
}

/** Saves the text as a file. Nothing keeps it after the download starts. */
function save(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }))
  const link = document.createElement("a")
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
