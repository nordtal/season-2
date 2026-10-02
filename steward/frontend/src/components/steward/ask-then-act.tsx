import { useState, type ReactNode } from "react"

import { Failure } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
  ResponsiveAlertDialogTrigger,
} from "@/components/ui/responsive-dialog"

/**
 * The one confirmation in front of a change: a question, then the change, then either closed or the failure inside.
 *
 * While the change is on its way nothing closes the dialog, so a slow answer is never lost behind a closed one.
 */
export function AskThenAct({
  open: openProp,
  onOpenChange,
  trigger,
  title,
  description,
  children,
  action,
  acting,
  cancel = "Cancel",
  destructive = false,
  disabled = false,
  act,
  answered,
  busy = false,
  closeLabel = "Close",
}: {
  /** Given, the caller decides when it is open; left off, {@link trigger} opens it. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** The button that opens it, wrapped as the trigger. */
  trigger?: ReactNode
  title: ReactNode
  description?: ReactNode
  /** What sits between the question and the buttons: fields, a warning. */
  children?: ReactNode
  /** The word on the button that acts. */
  action: ReactNode
  /** The word while it acts; the action's word when left off. */
  acting?: ReactNode
  cancel?: string
  destructive?: boolean
  /** The action cannot be taken yet, for a field still empty. */
  disabled?: boolean
  /** The change; a promise keeps the dialog open until it settles, and a rejection stays inside as the failure. */
  act: () => Promise<unknown> | void
  /**
   * What became of it, for a change whose answer is worth reading: passed at all, null until there is one, a success
   * keeps the dialog open, and once it is set only Close is offered.
   */
  answered?: ReactNode
  /** Something the caller still waits for, which holds the dialog open like the change itself. */
  busy?: boolean
  closeLabel?: string
}) {
  const [ownOpen, setOwnOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const open = openProp ?? ownOpen

  const setOpen = (next: boolean) => {
    if (!next && (pending || busy)) return
    if (!next) setFailure(null)
    setOwnOpen(next)
    onOpenChange?.(next)
  }

  const run = async () => {
    setFailure(null)
    const result = act()
    if (!(result instanceof Promise)) {
      setOpen(false)
      return
    }
    setPending(true)
    try {
      await result
      setPending(false)
      if (answered === undefined) setOpen(false)
    } catch (error) {
      setPending(false)
      setFailure(error)
    }
  }

  return (
    <ResponsiveAlertDialog open={open} onOpenChange={setOpen}>
      {trigger ? <ResponsiveAlertDialogTrigger asChild>{trigger}</ResponsiveAlertDialogTrigger> : null}
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>{title}</ResponsiveAlertDialogTitle>
          {description ? <ResponsiveAlertDialogDescription>{description}</ResponsiveAlertDialogDescription> : null}
        </ResponsiveAlertDialogHeader>
        {children}
        {failure ? <Failure error={failure} /> : null}
        {answered ?? null}
        <ResponsiveAlertDialogFooter>
          {answered ? (
            <ResponsiveAlertDialogCancel disabled={pending || busy}>{closeLabel}</ResponsiveAlertDialogCancel>
          ) : (
            <>
              <ResponsiveAlertDialogCancel disabled={pending}>{cancel}</ResponsiveAlertDialogCancel>
              <Button
                type="button"
                variant={destructive ? "destructive" : "default"}
                disabled={disabled || pending}
                onClick={() => void run()}
              >
                {pending ? (acting ?? action) : action}
              </Button>
            </>
          )}
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}
