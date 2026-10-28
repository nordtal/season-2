import { BellSlashIcon, PaperPlaneTiltIcon, TrashIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Link } from "@tanstack/react-router"
import { useQueryClient } from "@tanstack/react-query"
import { useState } from "react"

import type { AlertChannel, AlertType, ConfigEntry, PushDevice } from "@/lib/api"
import { pushSupported } from "@/lib/push"
import {
  useConfig,
  useConfigs,
  useAlertPreferences,
  useForgetWebPushDevice,
  useSaveConfig,
  useSetAlertPreference,
  useSubscribeWebPush,
  useTestWebPush,
  useUnsubscribeWebPush,
  useWebPushDevices,
  useWebPushPublicKey,
  useWebPushSubscription,
} from "@/lib/queries"
import { keys } from "@/lib/query-keys"
import { Failure, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { ChangedCount } from "@/components/steward/group-form"
import { announceSave } from "@/lib/announce-save"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  ResponsiveDialog,
  ResponsiveDialogClose,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Switch } from "@/components/ui/switch"
import { runs, t } from "@/lib/texts"
import { NO_VALUE } from "@/lib/format"

/**
 * The dialog is mounted outside the popover, like `SecurityKeyDialogs`, because a popover unmounts its dialog on the first outside click.
 */
export type NotificationActions = ReturnType<typeof useNotificationActions>

/**
 * `steward.notifications.type` names what a switch governs, and `steward.notifications.sample` holds the test's words.
 */
const TYPES: ReadonlyArray<{ key: AlertType; tone: "warn" | "down" }> = [
  { key: "service", tone: "down" },
  { key: "backup", tone: "down" },
  { key: "disk", tone: "warn" },
  { key: "memory", tone: "warn" },
  { key: "drift", tone: "warn" },
  { key: "pack", tone: "down" },
  { key: "run", tone: "down" },
  { key: "payment", tone: "down" },
  { key: "bot", tone: "warn" },
]

const CHANNELS: ReadonlyArray<{ key: AlertChannel; label: () => string }> = [
  { key: "push", label: () => t("steward.notifications.push") },
  { key: "discord", label: () => t("steward.notifications.discord") },
]

function typeName(type: AlertType): string {
  return t("steward.notifications.type", { type })
}

export function useNotificationActions() {
  const supported = pushSupported()
  const [open, setOpen] = useState(false)
  /**
   * Nothing is fetched until the dialog opens, except the public key, which must be cached before the tap.
   */
  const publicKey = useWebPushPublicKey(supported)
  const subscription = useWebPushSubscription(supported)
  const devices = useWebPushDevices(open)
  const preferences = useAlertPreferences(open)
  const subscribe = useSubscribeWebPush()
  const unsubscribe = useUnsubscribeWebPush()
  const forget = useForgetWebPushDevice()
  const choose = useSetAlertPreference()
  const test = useTestWebPush()

  return {
    supported,
    open,
    setOpen,
    publicKey,
    subscription,
    devices,
    preferences,
    subscribe,
    unsubscribe,
    forget,
    choose,
    test,
  }
}

export function NotificationsDialog({ state }: { state: NotificationActions }) {
  const thresholds = useThresholds(state)

  return (
    <ResponsiveDialog
      open={state.open}
      onOpenChange={(open) => {
        if (thresholds.save.isPending) return
        if (!open) thresholds.reset()
        state.setOpen(open)
      }}
    >
      {/* Only `sm:max-w-md`: below it a sheet is as wide as the phone, and no row may widen it. */}
      <ResponsiveDialogContent className="sm:max-w-md">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("steward.notifications.title")}</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        <div className="flex min-h-0 flex-col gap-4 overflow-x-hidden overflow-y-auto">
          {state.supported ? (
            <ThisDevice state={state} />
          ) : (
            <div className="flex flex-col">
              <span className="text-sm font-medium">{t("steward.notifications.this-device")}</span>
              <span className="text-xs text-muted-foreground">{t("steward.notifications.no-push")}</span>
            </div>
          )}
          <Types state={state} />
          <Thresholds state={state} thresholds={thresholds} />
          {state.supported ? <Devices state={state} /> : null}
        </div>

        {thresholds.writable ? (
          <ResponsiveDialogFooter>
            <ChangedCount count={thresholds.count} />
            <ResponsiveDialogClose asChild>
              <Button type="button" variant="outline" disabled={thresholds.save.isPending}>
                {t("steward.form.cancel")}
              </Button>
            </ResponsiveDialogClose>
            <Button
              type="button"
              disabled={thresholds.count === 0 || thresholds.save.isPending}
              onClick={() => void thresholds.submit()}
            >
              {thresholds.save.isPending ? t("steward.form.saving") : t("steward.form.save")}
            </Button>
          </ResponsiveDialogFooter>
        ) : null}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/**
 * `onClick` calls `.mutate` without awaiting, because iOS prompts only while the tap is on the stack.
 */
function ThisDevice({ state }: { state: NotificationActions }) {
  const subscribed = Boolean(state.subscription.data)
  const busy = state.subscribe.isPending || state.unsubscribe.isPending
  const failure = state.subscribe.error ?? state.unsubscribe.error

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 flex-col">
          <span className="text-sm font-medium">{t("steward.notifications.this-device")}</span>
          <span className="text-xs text-muted-foreground">
            {subscribed ? t("steward.notifications.on") : t("steward.notifications.off")}
          </span>
        </div>
        <Button
          type="button"
          variant={subscribed ? "outline" : "default"}
          size="sm"
          className="shrink-0"
          disabled={busy || (!subscribed && !state.publicKey.data?.publicKey)}
          onClick={() => {
            if (subscribed) {
              state.unsubscribe.mutate()
            } else if (state.publicKey.data?.publicKey) {
              state.subscribe.mutate(state.publicKey.data.publicKey)
            }
          }}
        >
          {subscribed ? <BellSlashIcon aria-hidden /> : <TERM_ICON.alert aria-hidden />}
          {subscribed ? t("steward.notifications.turn-off") : t("steward.notifications.turn-on")}
        </Button>
      </div>
      {failure ? (
        <p role="alert" className="text-xs break-words text-destructive">
          {failure.message}
        </p>
      ) : null}
    </div>
  )
}

function Types({ state }: { state: NotificationActions }) {
  const chosen = state.preferences.data

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 text-xs text-muted-foreground">{t("steward.notifications.notify-about")}</span>
        {CHANNELS.map((channel) => (
          <span key={channel.key} className="w-14 shrink-0 text-center text-xs text-muted-foreground">
            {channel.label()}
          </span>
        ))}
      </div>
      <ul className="flex flex-col">
        {TYPES.map((type) => (
          <li key={type.key} className="flex items-center gap-2 py-1">
            <span className="min-w-0 flex-1 truncate text-sm">{typeName(type.key)}</span>
            {CHANNELS.map((channel) => (
              <span key={channel.key} className="flex w-14 shrink-0 justify-center">
                <Switch
                  aria-label={t("steward.notifications.by-channel", {
                    type: typeName(type.key),
                    channel: channel.label(),
                  })}
                  checked={chosen?.[type.key]?.[channel.key] ?? false}
                  disabled={!chosen || (channel.key === "push" && !state.supported)}
                  onCheckedChange={(enabled) => state.choose.mutate({ type: type.key, channel: channel.key, enabled })}
                />
              </span>
            ))}
          </li>
        ))}
      </ul>
      {state.choose.error ? (
        <p role="alert" className="text-xs break-words text-destructive">
          {state.choose.error.message}
        </p>
      ) : null}
    </div>
  )
}

const ALERTS_SERVICE = "steward"
const ALERTS_FILE = "alerts"

/** Steward applies the numbers at its next reading. A stale revision is refused with a 409. */
function useThresholds(state: NotificationActions) {
  const client = useQueryClient()
  const configs = useConfigs(state.open)
  const file = configs.data?.find((location) => location.service === ALERTS_SERVICE && location.name === ALERTS_FILE)
  const document = useConfig(file?.path ?? "", Boolean(file))
  const save = useSaveConfig(file?.path ?? "")
  const [edited, setEdited] = useState<Record<string, string>>({})

  const parsed = document.data ?? null
  const entries: ConfigEntry[] = parsed?.entries ?? []
  const rows = THRESHOLDS.map((threshold) => ({
    ...threshold,
    entry: entries.find((entry) => entry.path === threshold.path),
  })).filter((row) => row.entry)

  const writable = Boolean(file?.writable) && rows.length === THRESHOLDS.length
  const changes: Record<string, string> = {}
  for (const row of rows) {
    const typed = edited[row.path]
    if (typed !== undefined && typed.trim() !== "" && typed !== row.entry?.value) {
      changes[row.path] = typed.trim()
    }
  }
  const count = Object.keys(changes).length

  return {
    rows,
    writable,
    waiting: configs.isPending || document.isPending,
    edited,
    setEdited,
    count,
    save,
    reset: () => {
      setEdited({})
      save.reset()
    },
    submit: async () => {
      if (!parsed) return
      try {
        const saved = await save.mutateAsync({ revision: parsed.revision, changes })
        setEdited({})
        /** The alerts query is a different cache entry from the group that was just saved. */
        void client.invalidateQueries({ queryKey: keys.alerts })
        announceSave(t("steward.settings.settings-saved", { count }), saved.reload)
        state.setOpen(false)
      } catch {
        /** The failed mutation's error is drawn in the dialog. */
      }
    },
  }
}

function Thresholds({
  state,
  thresholds,
}: {
  state: NotificationActions
  thresholds: ReturnType<typeof useThresholds>
}) {
  const { rows, edited, setEdited, save } = thresholds

  if (!thresholds.writable) {
    return <ReadOnlyThresholds rows={rows} waiting={thresholds.waiting} onLeave={() => state.setOpen(false)} />
  }

  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{t("steward.notifications.tell-me-when")}</span>
      <ul className="flex flex-col">
        {rows.map((row) => (
          <li key={row.path} className="flex items-center justify-between gap-2 py-1">
            <label htmlFor={`threshold-${row.key}`} className="min-w-0 truncate text-sm">
              {row.label()}
            </label>
            <div className="flex shrink-0 items-center gap-1.5">
              <Input
                id={`threshold-${row.key}`}
                type="number"
                min={1}
                inputMode="numeric"
                /** Three digits wide, so the row fits a 390px sheet. */
                className="h-8 w-16 text-right"
                value={edited[row.path] ?? row.entry?.value ?? ""}
                disabled={save.isPending}
                onChange={(event) => setEdited((before) => ({ ...before, [row.path]: event.target.value }))}
              />
              <span className="w-6 text-xs text-muted-foreground">{row.unit}</span>
            </div>
          </li>
        ))}
      </ul>
      {save.error ? <Failure error={save.error} write /> : null}
    </div>
  )
}

const THRESHOLDS: ReadonlyArray<{ key: string; path: string; label: () => string; unit: string }> = [
  { key: "disk", path: "disk-percent", label: () => t("steward.notifications.disk-in-use"), unit: "%" },
  { key: "memory", path: "memory-percent", label: () => t("steward.notifications.memory-in-use"), unit: "%" },
  { key: "backup", path: "backup-age-hours", label: () => t("steward.notifications.newest-backup"), unit: "h" },
]

function ReadOnlyThresholds({
  rows,
  waiting,
  onLeave,
}: {
  rows: Array<{ key: string; label: () => string; unit: string; entry?: ConfigEntry }>
  waiting: boolean
  onLeave: () => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{t("steward.notifications.tell-me-when")}</span>
      {waiting ? (
        <ul className="flex flex-col">
          {THRESHOLDS.map((threshold) => (
            <li key={threshold.key} className="flex items-center justify-between gap-2 py-1">
              <SkeletonText width="medium" className="text-sm" />
              <Skeleton className="h-5 w-12 shrink-0 rounded-md" />
            </li>
          ))}
        </ul>
      ) : rows.length > 0 ? (
        <ul className="flex flex-col">
          {rows.map((row) => (
            <li key={row.key} className="flex items-center justify-between gap-2 py-1">
              <span className="min-w-0 truncate text-sm">{row.label()}</span>
              <span className="shrink-0 text-sm text-muted-foreground">
                {row.entry?.value ?? NO_VALUE} {row.unit}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="text-xs break-words text-muted-foreground">
        {runs("steward.notifications.read-only", { group: ALERTS_FILE, service: ALERTS_SERVICE }).map((run, index) =>
          run.name === "service" ? (
            <Link
              key={index}
              to="/services/$name"
              params={{ name: ALERTS_SERVICE }}
              onClick={onLeave}
              className="underline underline-offset-2 tap-target"
            >
              {run.text}
            </Link>
          ) : run.name === "group" ? (
            <span key={index} className="font-mono">
              {run.text}
            </span>
          ) : (
            run.text
          ),
        )}
      </p>
    </div>
  )
}

function Devices({ state }: { state: NotificationActions }) {
  const mine = state.subscription.data
  const devices = state.devices.data ?? []
  const failure = state.devices.error ?? state.test.error ?? state.forget.error

  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs text-muted-foreground">{t("steward.notifications.devices")}</span>

      {state.devices.isPending ? (
        <ul className="flex flex-col">
          {WAITING_DEVICES.map((index) => (
            <li key={index} className="flex items-center gap-2 py-1">
              <div className="flex min-w-0 flex-1 flex-col">
                <SkeletonText width="long" className="max-w-48 text-sm" />
                <SkeletonText width="medium" className="max-w-36 text-xs" />
              </div>
              <Skeleton className="size-8 shrink-0 rounded-md" />
              <Skeleton className="size-8 shrink-0 rounded-md" />
            </li>
          ))}
        </ul>
      ) : devices.length === 0 ? (
        <p className="text-xs text-muted-foreground">{t("steward.notifications.no-device")}</p>
      ) : (
        <ul className="flex flex-col">
          {devices.map((device) => (
            <DeviceRow key={device.endpoint} device={device} isThisOne={device.endpoint === mine} state={state} />
          ))}
        </ul>
      )}

      {failure ? (
        <p role="alert" className="text-xs break-words text-destructive">
          {failure.message}
        </p>
      ) : null}
    </div>
  )
}

const WAITING_DEVICES = [0, 1]

function DeviceRow({
  device,
  isThisOne,
  state,
}: {
  device: PushDevice
  isThisOne: boolean
  state: NotificationActions
}) {
  const name = [device.system, device.browser].filter(Boolean).join(", ") || t("steward.notifications.unnamed")
  const busy = state.test.isPending || state.forget.isPending
  const [testing, setTesting] = useState(false)

  return (
    <li className="flex items-center gap-2 py-1">
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm">{name}</span>
        <span className="truncate text-xs text-muted-foreground">
          {isThisOne
            ? t("steward.notifications.this-one")
            : device.lastSentAt
              ? t("steward.notifications.last-notified", { at: device.lastSentAt })
              : t("steward.notifications.added", { at: device.subscribedAt })}
        </span>
      </div>
      <Popover open={testing} onOpenChange={setTesting}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="shrink-0"
            disabled={busy}
            aria-label={t("steward.notifications.send-test", { name })}
          >
            <PaperPlaneTiltIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-60 p-1">
          <p className="px-2 py-1.5 text-xs text-muted-foreground">{t("steward.notifications.test-notifications")}</p>
          <ul className="flex flex-col">
            {TYPES.map((type) => (
              <li key={type.key}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="w-full justify-start gap-2 font-normal"
                  onClick={() => {
                    setTesting(false)
                    state.test.mutate({ endpoint: device.endpoint, type: type.key })
                  }}
                >
                  {type.tone === "down" ? (
                    <TERM_ICON.failed aria-hidden className="text-destructive" />
                  ) : (
                    <TERM_ICON.warning aria-hidden className="text-warning" />
                  )}
                  <span className="min-w-0 truncate">{t("steward.notifications.sample", { type: type.key })}</span>
                </Button>
              </li>
            ))}
          </ul>
        </PopoverContent>
      </Popover>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className="shrink-0"
        disabled={busy}
        aria-label={t("steward.notifications.remove-device", { name })}
        onClick={() => state.forget.mutate(device.endpoint)}
      >
        <TrashIcon aria-hidden />
      </Button>
    </li>
  )
}
