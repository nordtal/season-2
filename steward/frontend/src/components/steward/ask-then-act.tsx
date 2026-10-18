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
import { t } from "@/lib/texts"

/**
 * The confirmation in front of a change: a question, then the change, then either the dialog closes or the failure shows inside it.
 *
 * While the change runs, nothing closes the dialog, so a slow answer is not lost behind a closed dialog.
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
  cancel = t("steward.form.cancel"),
  destructive = false,
  disabled = false,
  act,
  answered,
  busy = false,
  closeLabel = t("steward.form.close"),
}: {
  /**
   * When set, the caller decides whether the dialog is open. When left out, {@link trigger} opens it.
   */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** The button that opens the dialog, wrapped as the trigger. */
  trigger?: ReactNode
  title: ReactNode
  description?: ReactNode
  /** Content between the question and the buttons, such as fields or a warning. */
  children?: ReactNode
  /** The text of the button that acts. */
  action: ReactNode
  /** The text of that button while the action runs. It defaults to the action's text. */
  acting?: ReactNode
  cancel?: string
  destructive?: boolean
  /** Disables the action, for example while a field is empty. */
  disabled?: boolean
  /**
   * The change. A returned promise keeps the dialog open until it ends. A rejection shows inside the dialog as the failure.
   */
  act: () => Promise<unknown> | void
  /**
   * The result of the change, for a change whose answer is worth reading. It is null until there is one. A success keeps the dialog open, and then only Close is offered.
   */
  answered?: ReactNode
  /** Something the caller still waits for. It keeps the dialog open like the change does. */
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
