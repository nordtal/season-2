import { useState, type ReactNode } from "react"
import { ArrowLeftIcon, SpinnerIcon } from "@/components/ui/icons"

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
  step,
  back,
  next,
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
  /** The step of a two-step dialog, shown above the title. */
  step?: number
  /** Takes the place of the cancel button with a way back to the step before. */
  back?: () => void
  /** Makes the action a step forward: it calls this and keeps the dialog open. */
  next?: () => void
  /**
   * A returned promise keeps the dialog open until it ends. A rejection shows inside the dialog.
   */
  act?: () => Promise<unknown> | void
}) {
  const [ownOpen, setOwnOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const open = openProp ?? ownOpen

  const setOpen = (value: boolean) => {
    if (!value && pending) return
    if (!value) setFailure(null)
    setOwnOpen(value)
    onOpenChange?.(value)
  }

  const run = async () => {
    setFailure(null)
    if (next) {
      next()
      return
    }
    const result = act?.()
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
          {step ? (
            <p className="font-heading text-xs font-medium text-muted-foreground tnum">
              {t("steward.router.step", { step })}
            </p>
          ) : null}
          <ResponsiveAlertDialogTitle>{title}</ResponsiveAlertDialogTitle>
          {description ? <ResponsiveAlertDialogDescription>{description}</ResponsiveAlertDialogDescription> : null}
        </ResponsiveAlertDialogHeader>
        {children}
        {failure ? <Failure error={failure} write /> : null}
        <ResponsiveAlertDialogFooter>
          {back ? (
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => {
                setFailure(null)
                back()
              }}
            >
              <ArrowLeftIcon aria-hidden />
              {t("steward.router.back")}
            </Button>
          ) : (
            <ResponsiveAlertDialogCancel disabled={pending}>{cancel}</ResponsiveAlertDialogCancel>
          )}
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
