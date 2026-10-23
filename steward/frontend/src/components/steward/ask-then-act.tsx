import { useState, type ReactNode } from "react"
import { SpinnerIcon } from "@phosphor-icons/react"

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
}: {
  /** When set, the caller decides whether the dialog is open. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  trigger?: ReactNode
  title: ReactNode
  description?: ReactNode
  children?: ReactNode
  action: ReactNode
  acting?: ReactNode
  cancel?: string
  destructive?: boolean
  disabled?: boolean
  /**
   * A returned promise keeps the dialog open until it ends. A rejection shows inside the dialog.
   */
  act: () => Promise<unknown> | void
}) {
  const [ownOpen, setOwnOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const open = openProp ?? ownOpen

  const setOpen = (next: boolean) => {
    if (!next && pending) return
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
      setOpen(false)
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
        {failure ? <Failure error={failure} write /> : null}
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel disabled={pending}>{cancel}</ResponsiveAlertDialogCancel>
          <Button
            type="button"
            variant={destructive ? "destructive" : "default"}
            disabled={disabled || pending}
            onClick={() => void run()}
          >
            {pending ? <SpinnerIcon aria-hidden className="animate-spin" /> : null}
            {pending ? (acting ?? action) : action}
          </Button>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}
