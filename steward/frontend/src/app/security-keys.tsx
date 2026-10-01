import { FingerprintIcon, KeyIcon, PencilIcon, PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { Me, SecurityKey } from "@/lib/api"
import { relative } from "@/lib/format"
import { useRegisterKey, useRemoveKey, useRenameKey } from "@/lib/queries"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

/** The key list's state and its three dialogs, kept above the popover so an open dialog outlives it. */
export type SecurityKeyActions = ReturnType<typeof useSecurityKeyActions>

export function useSecurityKeyActions(me: Me | undefined) {
  const keys = me?.keys ?? []
  const add = useRegisterKey()
  const rename = useRenameKey()
  const remove = useRemoveKey()
  const [adding, setAdding] = useState(false)
  const [label, setLabel] = useState("")
  const [renaming, setRenaming] = useState<SecurityKey | null>(null)
  const [newLabel, setNewLabel] = useState("")
  const [removing, setRemoving] = useState<SecurityKey | null>(null)

  return {
    keys,
    /** Whether `/api/me` has answered, since `keys` is empty both before that and for an account with no key. */
    waiting: me === undefined,
    add,
    rename,
    remove,
    adding,
    label,
    setLabel,
    renaming,
    newLabel,
    setNewLabel,
    removing,
    relyingPartyId: me?.relyingPartyId ?? "nordtal.eu",
    startAdding() {
      add.reset()
      setLabel("")
      setAdding(true)
    },
    startRenaming(key: SecurityKey) {
      rename.reset()
      setNewLabel(key.label)
      setRenaming(key)
    },
    startRemoving(key: SecurityKey) {
      remove.reset()
      setRemoving(key)
    },
    closeAdding: () => setAdding(false),
    closeRenaming: () => setRenaming(null),
    closeRemoving: () => setRemoving(null),
    /** Whether any of the three questions is on screen, which tells a popover to get out of the way. */
    get asking() {
      return adding || renaming !== null || removing !== null
    },
  }
}

/** Two rows while `/api/me` is read. One key is the common case, two the recommended one. */
const WAITING_KEYS = [0, 1]

/** The registered keys; an absent backup answer is not a no, so only a definite no asks for a second key. */
export function SecurityKeyList({ state }: { state: SecurityKeyActions }) {
  const { keys } = state
  const onlyOneAndItIsPhysical = keys.length === 1 && keys[0].backedUp === false
  const failure = state.add.error ?? state.rename.error ?? state.remove.error

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">Security keys</span>
        <Button type="button" variant="ghost" size="sm" onClick={() => state.startAdding()}>
          <PlusIcon aria-hidden />
          Add
        </Button>
      </div>

      {state.waiting ? (
        <ul className="flex flex-col">
          {WAITING_KEYS.map((index) => (
            <li key={index} className="flex items-center gap-2 py-1">
              <Skeleton className="size-4 shrink-0 rounded-sm" />
              <div className="flex min-w-0 flex-1 flex-col">
                <SkeletonText width="long" className="max-w-[10rem] text-sm" />
                <SkeletonText width="medium" className="max-w-[8rem] text-xs" />
              </div>
            </li>
          ))}
        </ul>
      ) : keys.length === 0 ? (
        <p className="px-1 py-2 text-sm text-muted-foreground">No key is registered.</p>
      ) : (
        <ul className="flex flex-col">
          {keys.map((key) => (
            <li key={key.id} className="flex items-center gap-2 py-1">
              <KeyIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-sm">{key.label}</span>
                <span className="truncate text-xs text-muted-foreground">
                  {key.lastUsedAt
                    ? `last used ${relative(key.lastUsedAt)}`
                    : `registered ${relative(key.registeredAt)}`}
                </span>
              </div>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`Rename ${key.label}`}
                onClick={() => state.startRenaming(key)}
              >
                <PencilIcon aria-hidden />
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`Remove ${key.label}`}
                onClick={() => state.startRemoving(key)}
              >
                <TrashIcon aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {failure ? (
        <p role="alert" className="px-1 text-xs text-destructive">
          {failure.message}
        </p>
      ) : null}

      {onlyOneAndItIsPhysical ? (
        <p className="px-1 text-xs text-muted-foreground">
          This one key is not backed up anywhere. A second one means nobody has to run a command on the host if it is
          lost.
        </p>
      ) : null}
    </div>
  )
}

/** The three dialogs, mounted where a closing popover cannot take them with it. */
export function SecurityKeyDialogs({ state }: { state: SecurityKeyActions }) {
  const { add, rename, remove } = state

  return (
    <>
      <ResponsiveDialog open={state.adding} onOpenChange={(open) => open || state.closeAdding()}>
        <ResponsiveDialogContent className="sm:max-w-md">
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              add.mutate(state.label.trim() || "My second key", {
                onSuccess: () => {
                  state.closeAdding()
                  state.setLabel("")
                },
              })
            }}
          >
            <ResponsiveDialogHeader>
              <ResponsiveDialogTitle>Add a security key</ResponsiveDialogTitle>
              <ResponsiveDialogDescription>
                Registered to {state.relyingPartyId}, so it keeps working when Steward moves to its production address.
              </ResponsiveDialogDescription>
            </ResponsiveDialogHeader>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="new-key-label">What do you call this one?</Label>
              <Input
                id="new-key-label"
                value={state.label}
                onChange={(event) => state.setLabel(event.target.value)}
                placeholder="My second key"
                maxLength={64}
                autoComplete="off"
                autoFocus
              />
            </div>

            {add.error ? (
              <p role="alert" className="text-sm text-destructive">
                {add.error.message}
              </p>
            ) : null}

            <ResponsiveDialogFooter className="gap-2 sm:gap-2">
              <Button type="button" variant="ghost" onClick={state.closeAdding}>
                Cancel
              </Button>
              <Button type="submit" disabled={add.isPending}>
                <FingerprintIcon aria-hidden />
                {add.isPending ? "Waiting for the key…" : "Register"}
              </Button>
            </ResponsiveDialogFooter>
          </form>
        </ResponsiveDialogContent>
      </ResponsiveDialog>

      <ResponsiveDialog open={state.renaming !== null} onOpenChange={(open) => open || state.closeRenaming()}>
        <ResponsiveDialogContent className="sm:max-w-md">
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              const key = state.renaming
              if (!key) return
              rename.mutate({ id: key.id, label: state.newLabel.trim() }, { onSuccess: () => state.closeRenaming() })
            }}
          >
            <ResponsiveDialogHeader>
              <ResponsiveDialogTitle>Rename this key</ResponsiveDialogTitle>
              <ResponsiveDialogDescription>
                The name is how you tell two identical keys apart before removing one.
              </ResponsiveDialogDescription>
            </ResponsiveDialogHeader>

            <Input
              aria-label={`New name for ${state.renaming?.label ?? ""}`}
              value={state.newLabel}
              onChange={(event) => state.setNewLabel(event.target.value)}
              maxLength={64}
              autoComplete="off"
              autoFocus
            />

            {rename.error ? (
              <p role="alert" className="text-sm text-destructive">
                {rename.error.message}
              </p>
            ) : null}

            <ResponsiveDialogFooter className="gap-2 sm:gap-2">
              <Button type="button" variant="ghost" onClick={state.closeRenaming}>
                Cancel
              </Button>
              <Button type="submit" disabled={rename.isPending || !state.newLabel.trim()}>
                Save
              </Button>
            </ResponsiveDialogFooter>
          </form>
        </ResponsiveDialogContent>
      </ResponsiveDialog>

      {/* A question first, since the wrong one of two keys looks like the right one until the name is read. */}
      <ResponsiveAlertDialog open={state.removing !== null} onOpenChange={(open) => open || state.closeRemoving()}>
        <ResponsiveAlertDialogContent>
          <ResponsiveAlertDialogHeader>
            <ResponsiveAlertDialogTitle>Remove “{state.removing?.label}”?</ResponsiveAlertDialogTitle>
            <ResponsiveAlertDialogDescription>
              {state.keys.length === 1
                ? "It is the only key on this account. Removing it sends you back to the setup" +
                  " page, where you register a new one - you are not locked out, but you will" +
                  " need an authenticator to hand before you can use Steward again."
                : "The other keys on this account keep working. This one stops."}
            </ResponsiveAlertDialogDescription>
          </ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogFooter>
            <ResponsiveAlertDialogCancel>Keep it</ResponsiveAlertDialogCancel>
            <ResponsiveAlertDialogAction
              onClick={() => {
                const key = state.removing
                if (key) remove.mutate(key.id)
                state.closeRemoving()
              }}
            >
              Remove it
            </ResponsiveAlertDialogAction>
          </ResponsiveAlertDialogFooter>
        </ResponsiveAlertDialogContent>
      </ResponsiveAlertDialog>
    </>
  )
}
