import { BellIcon, BellSlashIcon, PaperPlaneTiltIcon, TrashIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { AlertTypeKey, PushDevice } from "@/lib/api"
import { relative } from "@/lib/format"
import { pushSupported } from "@/lib/push"
import {
  useForgetWebPushDevice,
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
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
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
 * **What is in it is four things and no prose.** Which kinds of alert this account wants, every
 * browser it has subscribed, a test send to one of them, and whether the browser being read from is
 * one of them. An admin either knows what "images" means here or asks; a paragraph explaining it
 * would be a paragraph in front of a switch.
 */
export type NotificationActions = ReturnType<typeof useNotificationActions>

/**
 * The words for {@link AlertTypeKey}, and the one place they exist.
 *
 * Plural nouns, not sentences: this is a list of things to be told about. "Images" rather than
 * "image drift" because the drift is the only thing anybody would ever be told about an image, and
 * the word an admin uses for the errand is "the images are old".
 */
const TYPES: ReadonlyArray<{ key: AlertTypeKey; label: string }> = [
  { key: "service", label: "Services" },
  { key: "backup", label: "Backups" },
  { key: "disk", label: "Disk" },
  { key: "memory", label: "Memory" },
  { key: "drift", label: "Images" },
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
  const [testType, setTestType] = useState<AlertTypeKey>("service")

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
    testType,
    setTestType,
  }
}

/** The entry in the user popover. The dialog itself is mounted beside it, never inside it. */
export function NotificationsDialog({ state }: { state: NotificationActions }) {
  return (
    <ResponsiveDialog open={state.open} onOpenChange={state.setOpen}>
      <ResponsiveDialogContent className="sm:max-w-md">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Notifications</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        {state.supported ? (
          <div className="flex min-h-0 flex-col gap-4 overflow-y-auto">
            <ThisDevice state={state} />
            <Types state={state} />
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
        <p role="alert" className="text-xs text-destructive">
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
            <label htmlFor={`notify-${type.key}`} className="truncate text-sm">
              {type.label}
            </label>
            <Switch
              id={`notify-${type.key}`}
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
        <p role="alert" className="text-xs text-destructive">
          {state.choose.error.message}
        </p>
      ) : null}
    </div>
  )
}

/**
 * Every browser on this account, and a test send to one of them.
 *
 * **The choice of WHICH notification is tested stands once, above the list, not once per row.** A
 * select beside every device would be the same control three times, on the narrowest screen this
 * interface has to fit - and the question "which kind" is asked once no matter how many devices
 * there are.
 */
function Devices({ state }: { state: NotificationActions }) {
  const mine = state.subscription.data
  const devices = state.devices.data ?? []
  const failure = state.devices.error ?? state.test.error ?? state.forget.error

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">Devices</span>
        <Select
          value={state.testType}
          onValueChange={(next) => state.setTestType(next as AlertTypeKey)}
        >
          <SelectTrigger size="sm" className="w-36" aria-label="Which notification to test">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {TYPES.map((type) => (
              <SelectItem key={type.key} value={type.key}>
                {type.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

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
        <p role="alert" className="text-xs text-destructive">
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
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        disabled={busy}
        aria-label={`Send a test notification to ${name}`}
        onClick={() =>
          state.test.mutate({ endpoint: device.endpoint, type: state.testType })
        }
      >
        <PaperPlaneTiltIcon aria-hidden />
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        disabled={busy}
        aria-label={`Remove ${name}`}
        onClick={() => state.forget.mutate(device.endpoint)}
      >
        <TrashIcon aria-hidden />
      </Button>
    </li>
  )
}
