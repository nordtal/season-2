import { BellIcon, BellSlashIcon, PaperPlaneTiltIcon, TrashIcon, WarningIcon, XCircleIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useQueryClient } from "@tanstack/react-query"
import { useState } from "react"

import type { AlertChannel, AlertType, ConfigEntry, PushDevice } from "@/lib/api"
import { relative } from "@/lib/format"
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
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Switch } from "@/components/ui/switch"

/**
 * Every notification setting, reached from the user popover so it is found.
 *
 * Mounted outside the popover like `SecurityKeyDialogs`: a popover unmounts a dialog on the first outside click.
 */
export type NotificationActions = ReturnType<typeof useNotificationActions>

/**
 * The words for {@link AlertType}.
 *
 * `label` names what a switch governs; `test` and `tone` are what `AlertRouter#sample` really sends.
 */
const TYPES: ReadonlyArray<{
  key: AlertType
  label: string
  test: string
  tone: "warn" | "down"
}> = [
  { key: "service", label: "Services", test: "Service down", tone: "down" },
  { key: "backup", label: "Backups", test: "Backup missing", tone: "down" },
  { key: "disk", label: "Disk", test: "Disk filling up", tone: "warn" },
  { key: "memory", label: "Memory", test: "Memory filling up", tone: "warn" },
  { key: "drift", label: "Images", test: "Image out of date", tone: "warn" },
  { key: "run", label: "Failed runs", test: "Run failed", tone: "down" },
  { key: "payment", label: "Payments", test: "Payment needs a look", tone: "down" },
  { key: "bot", label: "Discord actions", test: "Role not given", tone: "warn" },
]

/** The two places an alert can reach an admin besides Steward, as columns. */
const CHANNELS: ReadonlyArray<{ key: AlertChannel; label: string }> = [
  { key: "push", label: "Push" },
  { key: "discord", label: "Discord" },
]

export function useNotificationActions() {
  const supported = pushSupported()
  const [open, setOpen] = useState(false)
  /** Nothing is fetched until the dialog opens, except the public key, which must be cached before the tap. */
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

/** The entry in the user popover. The dialog itself is mounted beside it, never inside it. */
export function NotificationsDialog({ state }: { state: NotificationActions }) {
  return (
    <ResponsiveDialog open={state.open} onOpenChange={state.setOpen}>
      {/* `sm:max-w-md` only: below it a sheet is as wide as the phone, and no row may widen it. */}
      <ResponsiveDialogContent className="sm:max-w-md">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Notifications</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        <div className="flex min-h-0 flex-col gap-4 overflow-x-hidden overflow-y-auto">
          {state.supported ? (
            <ThisDevice state={state} />
          ) : (
            <p className="text-sm text-muted-foreground">This browser cannot receive push notifications.</p>
          )}
          <Types state={state} />
          <Thresholds state={state} />
          {state.supported ? <Devices state={state} /> : null}
        </div>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/**
 * Whether this browser is subscribed, and the button that changes it.
 *
 * `onClick` calls `.mutate` without awaiting first, since iOS only prompts while the tap is on the stack.
 */
function ThisDevice({ state }: { state: NotificationActions }) {
  const subscribed = Boolean(state.subscription.data)
  const busy = state.subscribe.isPending || state.unsubscribe.isPending
  const failure = state.subscribe.error ?? state.unsubscribe.error

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 flex-col">
          <span className="text-sm font-medium">This device</span>
          <span className="text-xs text-muted-foreground">{subscribed ? "On" : "Off"}</span>
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
          {subscribed ? <BellSlashIcon aria-hidden /> : <BellIcon aria-hidden />}
          {subscribed ? "Turn off" : "Turn on"}
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

/** One switch per {@link AlertType} and channel, for this account and all of its browsers at once. */
function Types({ state }: { state: NotificationActions }) {
  const chosen = state.preferences.data

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 text-xs text-muted-foreground">Notify me about</span>
        {CHANNELS.map((channel) => (
          <span key={channel.key} className="w-14 shrink-0 text-center text-xs text-muted-foreground">
            {channel.label}
          </span>
        ))}
      </div>
      <ul className="flex flex-col">
        {TYPES.map((type) => (
          <li key={type.key} className="flex items-center gap-2 py-1">
            <span className="min-w-0 flex-1 truncate text-sm">{type.label}</span>
            {CHANNELS.map((channel) => (
              <span key={channel.key} className="flex w-14 shrink-0 justify-center">
                <Switch
                  aria-label={`${type.label} by ${channel.label}`}
                  /** Off until the query answers, and disabled until then, so no type ever reads as off by accident. */
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

/** The service and group holding the three numbers: `AlertsSpec`. */
const ALERTS_SERVICE = "steward"
const ALERTS_FILE = "alerts"

/**
 * The three numbers steward's measured alerts fire on, editable here and applied at steward's next reading.
 *
 * It saves the `alerts` group with the configuration form's PUT, so a stale revision is refused with a 409.
 */
function Thresholds({ state }: { state: NotificationActions }) {
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
  const pending = Object.keys(changes).length > 0

  if (!writable) {
    return (
      <ReadOnlyThresholds
        rows={rows}
        waiting={configs.isPending || document.isPending}
        onLeave={() => state.setOpen(false)}
      />
    )
  }

  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">Tell me when</span>
      <ul className="flex flex-col">
        {rows.map((row) => (
          <li key={row.path} className="flex items-center justify-between gap-2 py-1">
            <label htmlFor={`threshold-${row.key}`} className="min-w-0 truncate text-sm">
              {row.label}
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
                onChange={(event) => setEdited((before) => ({ ...before, [row.path]: event.target.value }))}
              />
              <span className="w-6 text-xs text-muted-foreground">{row.unit}</span>
            </div>
          </li>
        ))}
      </ul>
      <div className="flex items-center justify-end gap-2">
        <Button
          type="button"
          size="sm"
          disabled={!pending || save.isPending || !parsed}
          onClick={() => {
            if (!parsed) return
            save.mutate(
              { revision: parsed.revision, changes },
              {
                onSuccess: () => {
                  setEdited({})
                  /** The alerts are a different cache entry from the group just saved. */
                  void client.invalidateQueries({ queryKey: keys.alerts })
                },
              },
            )
          }}
        >
          {save.isPending ? "Saving…" : "Save"}
        </Button>
      </div>
      {save.error ? (
        <p role="alert" className="text-xs break-words text-destructive">
          {save.error.message}
        </p>
      ) : null}
    </div>
  )
}

/** The three keys, in the order the alerts read them. */
const THRESHOLDS: ReadonlyArray<{ key: string; path: string; label: string; unit: string }> = [
  { key: "disk", path: "disk-percent", label: "Disk in use", unit: "%" },
  { key: "memory", path: "memory-percent", label: "Memory in use", unit: "%" },
  { key: "backup", path: "backup-age-hours", label: "Newest backup", unit: "h" },
]

/** What a deployment that cannot write the file gets: the numbers, and where they are set. */
function ReadOnlyThresholds({
  rows,
  waiting,
  onLeave,
}: {
  rows: Array<{ key: string; label: string; unit: string; entry?: ConfigEntry }>
  waiting: boolean
  onLeave: () => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">Tell me when</span>
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
              <span className="min-w-0 truncate text-sm">{row.label}</span>
              <span className="shrink-0 text-sm text-muted-foreground">
                {row.entry?.value ?? "\u2014"} {row.unit}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="text-xs break-words text-muted-foreground">
        This deployment does not let Steward write its own <span className="font-mono">{ALERTS_FILE}</span>. They are
        changed on the{" "}
        <Link
          to="/services/$name"
          params={{ name: ALERTS_SERVICE }}
          onClick={onLeave}
          className="underline underline-offset-2"
        >
          {ALERTS_SERVICE} page
        </Link>
        .
      </p>
    </div>
  )
}

/** Every browser on this account, and a test send to one of them, chosen on the paper plane's popover. */
function Devices({ state }: { state: NotificationActions }) {
  const mine = state.subscription.data
  const devices = state.devices.data ?? []
  const failure = state.devices.error ?? state.test.error ?? state.forget.error

  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs text-muted-foreground">Devices</span>

      {/* Two placeholder rows while loading, so the dialog does not grow under a pointer. */}
      {state.devices.isPending ? (
        <ul className="flex flex-col">
          {WAITING_DEVICES.map((index) => (
            <li key={index} className="flex items-center gap-2 py-1">
              <div className="flex min-w-0 flex-1 flex-col">
                <SkeletonText width="long" className="max-w-[12rem] text-sm" />
                <SkeletonText width="medium" className="max-w-[9rem] text-xs" />
              </div>
              <Skeleton className="size-8 shrink-0 rounded-md" />
              <Skeleton className="size-8 shrink-0 rounded-md" />
            </li>
          ))}
        </ul>
      ) : devices.length === 0 ? (
        <p className="text-sm text-muted-foreground">No device is subscribed.</p>
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

/** Two rows while the subscriptions are read. */
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
  const name = device.device ?? "Unnamed browser"
  const busy = state.test.isPending || state.forget.isPending
  const [testing, setTesting] = useState(false)

  return (
    <li className="flex items-center gap-2 py-1">
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm">{name}</span>
        {/* One line under the name, and "this device" wins it. */}
        <span className="truncate text-xs text-muted-foreground">
          {isThisOne
            ? "this device"
            : device.lastSentAt
              ? `last notified ${relative(device.lastSentAt)}`
              : `added ${relative(device.subscribedAt)}`}
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
            aria-label={`Send a test notification to ${name}`}
          >
            <PaperPlaneTiltIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        {/* End-aligned with a `rem` width, so it grows left into the sheet instead of past it. */}
        <PopoverContent align="end" className="w-60 max-w-[calc(100vw-2rem)] p-1">
          <p className="px-2 py-1.5 text-xs text-muted-foreground">Test notifications</p>
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
                    <XCircleIcon aria-hidden className="text-destructive" />
                  ) : (
                    <WarningIcon aria-hidden className="text-warning" />
                  )}
                  <span className="min-w-0 truncate">{type.test}</span>
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
        aria-label={`Remove ${name}`}
        onClick={() => state.forget.mutate(device.endpoint)}
      >
        <TrashIcon aria-hidden />
      </Button>
    </li>
  )
}
