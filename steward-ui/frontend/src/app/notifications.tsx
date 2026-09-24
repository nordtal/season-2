import {
  BellIcon,
  BellSlashIcon,
  PaperPlaneTiltIcon,
  TrashIcon,
  WarningIcon,
  XCircleIcon,
} from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useQueryClient } from "@tanstack/react-query"
import { useState } from "react"

import type { AlertTypeKey, ConfigEntry, PushDevice } from "@/lib/api"
import { relative } from "@/lib/format"
import { pushSupported } from "@/lib/push"
import {
  keys,
  useConfig,
  useConfigs,
  useForgetWebPushDevice,
  useSaveConfig,
  useSetWebPushPreference,
  useSubscribeWebPush,
  useTestWebPush,
  useUnsubscribeWebPush,
  useWebPushDevices,
  useWebPushPreferences,
  useWebPushPublicKey,
  useWebPushSubscription,
} from "@/lib/queries"
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
 * Everything about notifications, in the one place somebody would look for it (steward/98, Till's
 * review of 2026-09-18).
 *
 * **Why this exists at all:** the switch that turns push on used to be a card at the bottom of
 * `/settings`, and Till did not find it. That is the whole of the review's occasion, and it is why
 * this hangs off the round picture that already carries the security keys rather than off a page in
 * the navigation.
 *
 * **Mounted outside the popover, like `SecurityKeyDialogs`** - and for the identical reason, which
 * that file states: a dialog rendered inside a popover is unmounted by the first outside click, and
 * a dialog's own overlay is an outside click. The state therefore lives above both, in
 * {@link useNotificationActions}.
 *
 * <h2>Five sections, and the two new ones are steward/129</h2>
 * This browser, which kinds this account wants, the numbers those kinds fire on, every browser
 * subscribed, and a test send. The thresholds came in because deciding *whether* to be told and
 * deciding *when* are the same sitting - Till, 2026-09-20, reading a disk warning on a phone and
 * finding nowhere to move the number from. The test send moved onto the paper plane because a
 * select standing beside the word "Devices" is a control that looks like a filter of the list
 * under it.
 *
 * **Nothing here scrolls sideways.** On a phone that is always a layout fault and never a property
 * of the content, so every row is `min-w-0` with something in it allowed to truncate, and no fixed
 * width survives below `sm`. The measurement is `/home/dev/ui-shots/tool/notify.mjs`.
 */
export type NotificationActions = ReturnType<typeof useNotificationActions>

/**
 * The words for {@link AlertTypeKey}, and the one place they exist.
 *
 * <h2>Two labels per type, because they answer two different questions</h2>
 * `label` is what a switch governs and stays a plural noun: a list of things to be told about.
 * `test` is what pressing it will actually put on a lock screen in a moment - Till, 2026-09-20:
 * *not just "Service" but "Service down"*. The words come from `AlertWatch#sample`, which is what
 * the backend really sends for a test of that type, and `tone` is the level it sends with. A row
 * that promised something the push does not say would be the one lie this dialog could tell.
 */
const TYPES: ReadonlyArray<{
  key: AlertTypeKey
  label: string
  test: string
  tone: "warn" | "down"
}> = [
  { key: "service", label: "Services", test: "Service down", tone: "down" },
  { key: "backup", label: "Backups", test: "Backup missing", tone: "down" },
  { key: "disk", label: "Disk", test: "Disk filling up", tone: "warn" },
  { key: "memory", label: "Memory", test: "Memory filling up", tone: "warn" },
  { key: "drift", label: "Images", test: "Image out of date", tone: "warn" },
]

export function useNotificationActions() {
  const supported = pushSupported()
  const [open, setOpen] = useState(false)
  // Nothing is fetched until the dialog has been opened once: an account that never opens it should
  // not cost two queries on every page load, and the public key below is the one exception - it has
  // to be in cache BEFORE the button is tapped (see useWebPushPublicKey).
  const publicKey = useWebPushPublicKey(supported)
  const subscription = useWebPushSubscription(supported)
  const devices = useWebPushDevices(open)
  const preferences = useWebPushPreferences(open)
  const subscribe = useSubscribeWebPush()
  const unsubscribe = useUnsubscribeWebPush()
  const forget = useForgetWebPushDevice()
  const choose = useSetWebPushPreference()
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
      {/*
        `sm:max-w-md` and nothing below it: a sheet is as wide as the phone, and `overflow-x-hidden`
        on the scroller is the belt to the braces of every row being `min-w-0` - a popover that
        opens near the right edge must not be able to widen the sheet it hangs in.
      */}
      <ResponsiveDialogContent className="sm:max-w-md">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Notifications</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        {state.supported ? (
          <div className="flex min-h-0 flex-col gap-4 overflow-x-hidden overflow-y-auto">
            <ThisDevice state={state} />
            <Types state={state} />
            <Thresholds state={state} />
            <Devices state={state} />
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            This browser cannot receive push notifications.
          </p>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/**
 * Whether the browser being read from is subscribed, and the one button that changes it.
 *
 * The `onClick` calls `.mutate` directly and never `await`s anything first: iOS only opens the
 * permission prompt `pushManager.subscribe()` asks for while the tap is still on the call stack.
 * The public key is already in cache - see `lib/push.ts`'s module note for the whole of it.
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

/** One switch per {@link AlertTypeKey}, for this account and all of its browsers at once. */
function Types({ state }: { state: NotificationActions }) {
  const chosen = state.preferences.data

  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">Notify me about</span>
      <ul className="flex flex-col">
        {TYPES.map((type) => (
          <li key={type.key} className="flex items-center justify-between gap-2 py-1">
            <label htmlFor={`notify-${type.key}`} className="min-w-0 truncate text-sm">
              {type.label}
            </label>
            <Switch
              id={`notify-${type.key}`}
              className="shrink-0"
              // Undefined until the query has answered, and disabled until then: a switch drawn
              // off before anything was read would show every type as off for a moment, which is
              // the one reading this dialog must never give by accident.
              checked={chosen?.[type.key] ?? false}
              disabled={!chosen}
              onCheckedChange={(enabled) =>
                state.choose.mutate({ type: type.key, enabled })
              }
            />
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

/** The service and the file the three numbers live in - `UiSpec.AlertSpec`, keyed under `alerts`. */
const ALERTS_SERVICE = "steward-ui"
const ALERTS_FILE = "steward-ui.yml"

/**
 * The three numbers the light - and therefore every push - fires on.
 *
 * Till, 2026-09-20, asked for the threshold to be settable inside this dialog rather than only
 * readable somewhere else. The ticket's own fallback was to show them and point at the
 * configuration page, and that is not what is built here: they are **server-side
 * rather than per-account**, which is the condition the fallback names, but server-side does not
 * mean unreachable. `alerts.disk-percent` and its two neighbours are ordinary scalars in
 * `steward-ui/steward-ui.yml`, and the same PUT the configuration form uses writes them, revision
 * and all.
 *
 * <h2>Not a second form over one value</h2>
 * The configuration page still owns editing every other key in that file. What this is, is the three keys a notification is about, at the moment somebody
 * is reading a notification. The revision guard is what makes two ways in safe: a save from here
 * against a stale revision is refused with a 409 exactly as one from the form would be.
 *
 * <h2>When the file is not there</h2>
 * A deployment whose worker does not list `steward-ui/steward-ui.yml` - or lists it unwritable -
 * gets the read-only shape the ticket describes, with the link. A field that cannot write is worse
 * than no field, so there is no third state in which one is drawn hopefully.
 */
function Thresholds({ state }: { state: NotificationActions }) {
  const client = useQueryClient()
  const configs = useConfigs(state.open)
  const file = configs.data?.find(
    (location) => location.service === ALERTS_SERVICE && location.name === ALERTS_FILE,
  )
  const document = useConfig(file?.path ?? "", Boolean(file))
  const save = useSaveConfig(file?.path ?? "")
  const [edited, setEdited] = useState<Record<string, string>>({})

  const parsed = document.data && !document.data.raw ? document.data : null
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
                // Wide enough for three digits and no wider: this sits at the right edge of a
                // 390px sheet, and an input that keeps its desktop width there is exactly the box
                // that pushes the row past the screen.
                className="h-8 w-16 text-right"
                value={edited[row.path] ?? row.entry?.value ?? ""}
                onChange={(event) =>
                  setEdited((before) => ({ ...before, [row.path]: event.target.value }))
                }
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
                  // The traffic light reads the effective numbers from
                  // `/api/settings`, which is a different cache entry from the file this just
                  // wrote. Without this it keeps the old thresholds until something else
                  // refetches them, and the dialog would look like it had not saved.
                  void client.invalidateQueries({ queryKey: keys.settings })
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

/** The three keys, in the order the light reads them. `unit` is a word, never a second column. */
const THRESHOLDS: ReadonlyArray<{ key: string; path: string; label: string; unit: string }> = [
  { key: "disk", path: "alerts.disk-percent", label: "Disk in use", unit: "%" },
  { key: "memory", path: "alerts.memory-percent", label: "Memory in use", unit: "%" },
  { key: "backup", path: "alerts.backup-age-hours", label: "Newest backup", unit: "h" },
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
                {row.entry?.value ?? "—"} {row.unit}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="text-xs break-words text-muted-foreground">
        This deployment does not let Steward write its own{" "}
        <span className="font-mono">{ALERTS_FILE}</span>. They are changed on the{" "}
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

/**
 * Every browser on this account, and a test send to one of them.
 *
 * **The choice of WHICH notification is tested hangs off the paper plane** (steward/129, Till,
 * 2026-09-20). It was a select beside the word "Devices", which is the position a filter of the
 * list beneath it would occupy - and it made the header row of a 390px sheet carry a 144px control
 * it could not shrink. A popover on the button that does the sending says what it is for by being
 * where it is, and it costs the header nothing.
 */
function Devices({ state }: { state: NotificationActions }) {
  const mine = state.subscription.data
  const devices = state.devices.data ?? []
  const failure = state.devices.error ?? state.test.error ?? state.forget.error

  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs text-muted-foreground">Devices</span>

      {/*
        steward/120. This list drew nothing at all while it was read, so the dialog opened one
        height and grew a moment later - under a dropdown somebody had just aimed at. Two rows of
        the right height is what it reserves now: two is the ordinary number of browsers, and the
        list is the last thing in the dialog, so being wrong by one costs nothing.
      */}
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
            <DeviceRow
              key={device.endpoint}
              device={device}
              isThisOne={device.endpoint === mine}
              state={state}
            />
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

/** Two rows while the subscriptions are read. Most accounts have one browser, some have two. */
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
        {/*
          One line under the name, and "this device" wins it: which of several similar-looking
          browsers is the one in your hand is the only question this list is ever asked, and a
          second line saying both would be the separator this interface does not use.
        */}
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
        {/*
          `align="end"` and a width in `rem`, not a fraction: this opens against the right edge of a
          390px sheet, and an end-aligned popover grows leftwards into the sheet instead of past it.
        */}
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
