import {
  FingerprintIcon,
  KeyIcon,
  PencilIcon,
  PlusIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { useState } from "react"

import type { Me, SecurityKey } from "@/lib/api"
import { relative } from "@/lib/format"
import { useRegisterKey, useRemoveKey, useRenameKey } from "@/lib/queries"
import { Button } from "@/components/ui/button"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

/**
 * The keys registered on this account: add, rename, remove.
 *
 * **This is `pages/settings.tsx`'s section, moved rather than rewritten** (steward/89). The
 * mutations, the wording and the question before a removal are the ones that were already there.
 * Three things changed, and all three are ordered:
 *
 * - **The list is never behind a click.** Till, 2026-09-17: the keys stand in the user popover as a
 *   list. A dialog opens where one belongs - registering a key, renaming one, and the question
 *   before a removal - and nowhere else. Registering used to be an inline form; it is a dialog now,
 *   because a form unfolding inside a popover grows the popover under the reader's thumb.
 * - **Nothing in here is bordered.** The popover is already a bordered surface and the settings
 *   page draws this inside a card, so every framed box this section used - the form, each key row,
 *   the two alerts - is a plain row now. The rule is Till's, 2026-09-17, and it is general.
 * - **The state lives above the list**, in {@link useSecurityKeyActions}, so the dialogs can be
 *   mounted outside the popover. A dialog rendered inside it dies the moment the popover closes -
 *   and the popover closes on the first outside click, which is the dialog's own overlay.
 */
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
    /** Whether any of the three questions is on screen - what tells a popover to get out of the way. */
    get asking() {
      return adding || renaming !== null || removing !== null
    },
  }
}

/**
 * The list itself, and the one line under each key.
 *
 * ABSENT IS NOT THE SAME AS FALSE. An authenticator that did not answer the backup question is not
 * one that answered "no", and only a definite no is a reason to press for a second key - a passkey
 * synced through iCloud already exists in two places.
 */
export function SecurityKeyList({ state }: { state: SecurityKeyActions }) {
  const { keys } = state
  const onlyOneAndItIsPhysical = keys.length === 1 && keys[0].backedUp === false
  const failure = state.add.error ?? state.rename.error ?? state.remove.error

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">Security keys</span>
        <Button type="button" variant="ghost" size="sm" onClick={state.startAdding}>
          <PlusIcon aria-hidden />
          Add
        </Button>
      </div>

      {keys.length === 0 ? (
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
          This one key is not backed up anywhere. A second one means nobody has to run a command on
          the host if it is lost.
        </p>
      ) : null}
    </div>
  )
}

/**
 * The three questions, mounted where a closing popover cannot take them with it.
 *
 * Adding an authenticator is exactly as powerful as having one, so a stolen session that could add
 * a key would be a stolen session that had made itself permanent. The server enforces that; this
 * side never has to know.
 */
export function SecurityKeyDialogs({ state }: { state: SecurityKeyActions }) {
  const { add, rename, remove } = state

  return (
    <>
      <Dialog open={state.adding} onOpenChange={(open) => open || state.closeAdding()}>
        <DialogContent className="sm:max-w-md">
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
            <DialogHeader>
              <DialogTitle>Add a security key</DialogTitle>
              <DialogDescription>
                Registered to {state.relyingPartyId}, so it keeps working when Steward moves to its
                production address.
              </DialogDescription>
            </DialogHeader>

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

            <DialogFooter className="gap-2 sm:gap-2">
              <Button type="button" variant="ghost" onClick={state.closeAdding}>
                Cancel
              </Button>
              <Button type="submit" disabled={add.isPending}>
                <FingerprintIcon aria-hidden />
                {add.isPending ? "Waiting for the key…" : "Register"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={state.renaming !== null} onOpenChange={(open) => open || state.closeRenaming()}>
        <DialogContent className="sm:max-w-md">
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              const key = state.renaming
              if (!key) return
              rename.mutate(
                { id: key.id, label: state.newLabel.trim() },
                { onSuccess: () => state.closeRenaming() },
              )
            }}
          >
            <DialogHeader>
              <DialogTitle>Rename this key</DialogTitle>
              <DialogDescription>
                The name is how you tell two identical keys apart before removing one.
              </DialogDescription>
            </DialogHeader>

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

            <DialogFooter className="gap-2 sm:gap-2">
              <Button type="button" variant="ghost" onClick={state.closeRenaming}>
                Cancel
              </Button>
              <Button type="submit" disabled={rename.isPending || !state.newLabel.trim()}>
                Save
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/*
        A question rather than a button that just does it. Removing the LAST key is allowed - it
        puts this account back at the setup page, which is recoverable - but removing the wrong one
        of two is not, and the two look identical until the name is read.
      */}
      <AlertDialog
        open={state.removing !== null}
        onOpenChange={(open) => open || state.closeRemoving()}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remove “{state.removing?.label}”?</AlertDialogTitle>
            <AlertDialogDescription>
              {state.keys.length === 1
                ? "It is the only key on this account. Removing it sends you back to the setup"
                  + " page, where you register a new one - you are not locked out, but you will"
                  + " need an authenticator to hand before you can use Steward again."
                : "The other keys on this account keep working. This one stops."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep it</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const key = state.removing
                if (key) remove.mutate(key.id)
                state.closeRemoving()
              }}
            >
              Remove it
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

/**
 * List and questions in one piece, for a page rather than a popover.
 *
 * `pages/settings.tsx` uses this. That page keeps its security keys until Till has picked a shell
 * and the thresholds have a home (steward/50) - two copies of one list is the cost of not deleting
 * a page before the decision that replaces it, and they are the same component, not two.
 */
export function SecurityKeys({ me }: { me?: Me }) {
  const state = useSecurityKeyActions(me)
  return (
    <>
      <SecurityKeyList state={state} />
      <SecurityKeyDialogs state={state} />
    </>
  )
}
