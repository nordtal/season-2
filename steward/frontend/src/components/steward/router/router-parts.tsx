import { type ComponentProps, type ReactNode, useId } from "react"
import { ArrowRightIcon } from "@/components/ui/icons"

import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"
import { Hint } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

/** A row of facts on a card, each a heading above its value. */
export function Facts({ children }: { children: ReactNode }) {
  return <dl className="flex flex-wrap gap-x-8 gap-y-3">{children}</dl>
}

export function Fact({ term, hint, children }: { term: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="font-heading text-xs font-medium text-muted-foreground">{term}</dt>
      <dd className="flex flex-col text-sm">
        <span>{children}</span>
        {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
      </dd>
    </div>
  )
}

/** The facts of a dialog, a heading beside each value. */
export function DFacts({ children }: { children: ReactNode }) {
  return <dl className="flex flex-col gap-2.5">{children}</dl>
}

export function DFact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[7.5rem_minmax(0,1fr)] items-baseline gap-2">
      <dt className="font-heading text-xs font-medium text-muted-foreground">{term}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

/** What a change moves from, muted, and what it moves to, with as many steps between as given. */
export function Pair({ steps }: { steps: ReactNode[] }) {
  return (
    <span className="inline-flex flex-wrap items-baseline gap-1">
      {steps.map((step, at) => (
        <span key={at} className="inline-flex items-baseline gap-1">
          {at > 0 ? <ArrowRightIcon aria-hidden className="size-3 self-center text-muted-foreground" /> : null}
          <span className={at === 0 && steps.length === 2 ? "text-muted-foreground" : undefined}>{step}</span>
        </span>
      ))}
    </span>
  )
}

/** A value with a muted remark after it. */
export function WithHint({ children, hint }: { children: ReactNode; hint?: ReactNode }) {
  return (
    <span className="inline-flex flex-wrap items-baseline gap-x-1.5">
      <span>{children}</span>
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </span>
  )
}

/** A line with an icon before it, as a dialog's warning or a card's remark. */
export function Note({
  icon: Icon = TERM_ICON.warning,
  tone,
  small = false,
  children,
}: {
  icon?: typeof TERM_ICON.warning
  tone?: "warn" | "down"
  small?: boolean
  children: ReactNode
}) {
  return (
    <p
      className={cn(
        "flex items-start",
        small ? "gap-1.5 text-xs" : "gap-2 text-sm",
        tone === "warn" && "text-warning",
        tone === "down" && "text-destructive",
        !tone && "text-muted-foreground",
      )}
    >
      <Icon aria-hidden className={cn("shrink-0", small ? "mt-px size-3.5" : "mt-0.5 size-4")} />
      <span>{children}</span>
    </p>
  )
}

export function ReasonField({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const id = useId()
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{t("steward.router.reason")}</Label>
      <Input
        id={id}
        value={value}
        maxLength={500}
        placeholder={t("steward.router.reason-placeholder")}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  )
}

/** The line at the end of a dialog whose action asks for the security key. */
export function KeyNext() {
  return (
    <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <TERM_ICON.securityKey aria-hidden className="size-3.5 shrink-0" />
      {t("steward.router.key-next")}
    </p>
  )
}

/** A button whose change asks for the security key on confirm, which its trailing key and its tip say. */
export function KeyedButton({
  tip = t("steward.router.key-tip"),
  children,
  ...props
}: { tip?: ReactNode; children: ReactNode } & ComponentProps<typeof Button>) {
  return (
    <Hint tip={tip}>
      <Button type="button" {...props}>
        {children}
        <TERM_ICON.securityKey aria-hidden data-icon="inline-end" className="opacity-70" />
      </Button>
    </Hint>
  )
}

/** Parts of a line set apart by space, never by a dot. */
export function Parts({ className, children }: { className?: string; children: ReactNode }) {
  return <span className={cn("flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5", className)}>{children}</span>
}

/** A card of the tab: its icon and title, the badges on the right, and its content with the action at the bottom. */
export function RouterCard({
  icon: Icon,
  title,
  badges,
  className,
  children,
}: {
  icon: typeof TERM_ICON.warning
  title: string
  badges?: ReactNode
  className?: string
  children: ReactNode
}) {
  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Icon className="size-4 text-muted-foreground" aria-hidden />
          {title}
        </CardTitle>
        {badges ? <CardAction className="flex flex-wrap items-center justify-end gap-1.5">{badges}</CardAction> : null}
      </CardHeader>
      <CardContent className="flex flex-1 flex-col gap-4">{children}</CardContent>
    </Card>
  )
}
