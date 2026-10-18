import { ArrowSquareOutIcon, CaretDownIcon, PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { Me, StepUpHold, StepUpLength } from "@/lib/api"
import { useFinishEnrolment, useRemoveAuthenticator, useStartEnrolment } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { QrCode } from "@/components/steward/qr-code"
import { Skeleton } from "@/components/steward/query-state"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { t } from "@/lib/texts"

/**
 * A confirm button that holds for the default step-up length, with a menu of the longer holds the server offers.
 *
 * The security key dialogs and the authenticator app dialogs both use it.
 */
export function HoldSplitButton({
  holds,
  onHold,
  disabled,
  submit,
  secondary,
  children,
}: {
  holds: StepUpHold[]
  onHold: (length?: StepUpLength) => void
  disabled: boolean
  /** Makes this the form's submit button, so Enter in a field holds the default length. */
  submit?: boolean
  /** Draws the button as the second choice. */
  secondary?: boolean
  children: React.ReactNode
}) {
  const longer = holds.slice(1)
  return (
    <div className="flex">
      <Button
        type={submit ? "submit" : "button"}
        variant={secondary ? "outline" : "default"}
        className={longer.length > 0 ? "flex-1 rounded-r-none" : "flex-1"}
        onClick={submit ? undefined : () => onHold()}
        disabled={disabled}
      >
        {children}
      </Button>
      {longer.length > 0 ? (
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button
              type="button"
              size="icon"
              variant={secondary ? "outline" : "default"}
              className={secondary ? "rounded-l-none border-l-0" : "rounded-l-none border-l-primary-foreground/25"}
              aria-label={t("steward.keys.longer-hold")}
              disabled={disabled}
            >
              <CaretDownIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-auto">
            {longer.map((offer) => (
              <DropdownMenuItem key={offer.hold} onSelect={() => onHold(offer.hold)}>
                {t("steward.keys.hold-for", { length: offer.minutes * 60 })}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
    </div>
  )
}

/**
 * The input for the six digits an authenticator app shows. It accepts the code suggested by a phone keyboard.
 */
export function CodeInput({
  id,
  value,
  onChange,
  autoFocus,
}: {
  id: string
  value: string
  onChange: (code: string) => void
  autoFocus?: boolean
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{t("steward.keys.code-label")}</Label>
      <Input
        id={id}
        name="code"
        value={value}
        onChange={(event) => onChange(event.target.value.replace(/[^0-9 ]/g, ""))}
        inputMode="numeric"
        autoComplete="one-time-code"
        spellCheck={false}
        pattern="[0-9 ]*"
        maxLength={7}
        placeholder="123 456"
        className="font-mono tracking-widest"
        autoFocus={autoFocus}
      />
    </div>
  )
}

/** The "or" between the security key and the authenticator app. */
export function OrDivider() {
  return (
    <div className="flex items-center gap-3 text-xs text-muted-foreground">
      <span className="h-px flex-1 bg-border" aria-hidden />
      {t("steward.keys.or")}
      <span className="h-px flex-1 bg-border" aria-hidden />
    </div>
  )
}

/**
 * Returns whether the typed text can be a code. The confirm button stays disabled until it can.
 */
export function looksLikeACode(code: string): boolean {
  return /^\d{6}$/.test(code.replace(/ /g, ""))
}

/** The actions behind the authenticator app row of the account popover and its two dialogs. */
export type AuthenticatorActions = ReturnType<typeof useAuthenticatorActions>

export function useAuthenticatorActions(me: Me | undefined) {
  const [enrolling, setEnrolling] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [code, setCode] = useState("")
  const start = useStartEnrolment()
  const finish = useFinishEnrolment()
  const remove = useRemoveAuthenticator()
  return {
    app: me?.authenticator,
    offered: me?.authenticatorOffered ?? false,
    /** The only factor cannot be removed. The server refuses it too. */
    onlyFactor: (me?.keys?.length ?? 0) === 0,
    start,
    finish,
    remove,
    code,
    setCode,
    enrolling,
    removing,
    /**
     * Opens the dialog and requests a new secret. The request needs a step-up when the account already has a factor.
     */
    startEnrolling: () => {
      setCode("")
      finish.reset()
      start.mutate()
      setEnrolling(true)
    },
    closeEnrolling: () => setEnrolling(false),
    startRemoving: () => {
      remove.reset()
      setRemoving(true)
    },
    closeRemoving: () => setRemoving(false),
    get asking() {
      return enrolling || removing
    },
  }
}

/**
 * The authenticator app row of the account popover. It shows a remove control, or an Add button when the installation offers an app.
 */
export function AuthenticatorAppRow({ state }: { state: AuthenticatorActions }) {
  if (!state.app && !state.offered) return null
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{t("steward.keys.app-heading")}</span>
        {state.app ? null : (
          <Button type="button" variant="ghost" size="sm" onClick={state.startEnrolling}>
            <PlusIcon aria-hidden />
            {t("steward.keys.add")}
          </Button>
        )}
      </div>
      {state.app ? (
        <div className="flex items-center gap-2 py-1">
          <TERM_ICON.authenticatorApp className="size-4 shrink-0 text-muted-foreground" aria-hidden />
          <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">
            {state.app.lastUsedAt
              ? t("steward.keys.last-used", { at: state.app.lastUsedAt })
              : t("steward.keys.registered", { at: state.app.addedAt })}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("steward.keys.remove-app")}
            title={state.onlyFactor ? t("steward.keys.only-factor") : undefined}
            disabled={state.onlyFactor}
            onClick={state.startRemoving}
          >
            <TrashIcon aria-hidden />
          </Button>
        </div>
      ) : (
        <p className="px-1 py-1 text-sm text-muted-foreground">{t("steward.keys.no-app")}</p>
      )}
      {state.remove.error ? (
        <p role="alert" className="px-1 text-xs text-destructive">
          {state.remove.error.message}
        </p>
      ) : null}
    </div>
  )
}

/**
 * The enrolment and removal dialogs, mounted outside the popover because a closing popover unmounts its dialogs.
 */
export function AuthenticatorAppDialogs({ state }: { state: AuthenticatorActions }) {
  return (
    <>
      <EnrolDialog state={state} />
      <AskThenAct
        open={state.removing}
        onOpenChange={(open) => open || state.closeRemoving()}
        title={t("steward.keys.remove-app-title")}
        description={t("steward.keys.remove-app-note")}
        cancel={t("steward.keys.keep-it")}
        action={t("steward.keys.remove-it")}
        act={() => state.remove.mutate()}
      />
    </>
  )
}

/**
 * The dialog that sets up an authenticator app. It shows a QR code and the key as text, and the app counts as set up once the admin enters one code from it.
 *
 * The dialog requests the secret when it opens. A step-up comes first if the account already has a factor.
 */
export function EnrolDialog({ state }: { state: AuthenticatorActions }) {
  const { start, finish, code, setCode } = state
  const onClose = state.closeEnrolling
  const enrolment = start.data
  const failure = start.error ?? finish.error

  return (
    <ResponsiveDialog open={state.enrolling} onOpenChange={(next) => next || onClose()}>
      <ResponsiveDialogContent className="sm:max-w-md">
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            if (!looksLikeACode(code)) return
            finish.mutate(code, { onSuccess: onClose })
          }}
        >
          <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>{t("steward.keys.app-title")}</ResponsiveDialogTitle>
            <ResponsiveDialogDescription>{t("steward.keys.app-scan")}</ResponsiveDialogDescription>
          </ResponsiveDialogHeader>

          {enrolment ? (
            <div className="flex flex-col items-center gap-3">
              <QrCode qr={enrolment.qr} label={t("steward.keys.app-qr")} />
              <div className="flex w-full flex-col gap-1">
                <span className="text-xs text-muted-foreground">{t("steward.keys.app-secret")}</span>
                <code
                  translate="no"
                  className="rounded-md bg-muted px-2 py-1.5 font-mono text-sm break-words select-all"
                >
                  {grouped(enrolment.secret)}
                </code>
              </div>
              <a
                href={enrolment.uri}
                className="inline-flex items-center gap-1 self-start text-sm text-primary underline-offset-4 hover:underline"
              >
                <ArrowSquareOutIcon aria-hidden className="size-4" />
                {t("steward.keys.open-in-app")}
              </a>
            </div>
          ) : start.isPending ? (
            <Skeleton className="mx-auto size-48 rounded-md" />
          ) : null}

          <CodeInput id="enrol-code" value={code} onChange={setCode} />

          {failure ? (
            <p role="alert" className="text-sm text-destructive">
              {failure.message}
            </p>
          ) : null}

          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button type="button" variant="ghost" onClick={onClose}>
              {t("steward.form.cancel")}
            </Button>
            <Button type="submit" disabled={!enrolment || finish.isPending || !looksLikeACode(code)}>
              {finish.isPending ? t("steward.keys.checking") : t("steward.keys.confirm-code")}
            </Button>
          </div>
        </form>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/** Splits the key into groups of four characters. */
function grouped(secret: string): string {
  return secret.replace(/(.{4})(?=.)/g, "$1 ")
}
