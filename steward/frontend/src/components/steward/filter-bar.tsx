import { CheckIcon, MagnifyingGlassIcon, XIcon } from "@phosphor-icons/react"
import type { ReactNode, Ref } from "react"
import { cn } from "cn"

import { t } from "@/lib/texts"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@/components/ui/input-group"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/**
 * The search and filter row of every page that has one: the search field first and growing, the filters beside it.
 *
 * It wraps only when the controls do not fit one line, the field never narrower than a phone's half.
 */
export function FilterBar({
  children,
  className,
  ref,
}: {
  children: ReactNode
  className?: string
  ref?: Ref<HTMLDivElement>
}) {
  return (
    <div ref={ref} data-slot="filter-bar" className={cn("flex flex-wrap items-center gap-2", className)}>
      {children}
    </div>
  )
}

/**
 * The search field, the magnifier inside it and a clear button once something is typed.
 *
 * With `onSubmit` it filters on Enter alone, as an exact match must; clearing submits the empty value at once.
 */
export function SearchField({
  value,
  onValueChange,
  onSubmit,
  label,
  placeholder = t("steward.settings.search"),
  className,
  inputClassName,
}: {
  value: string
  onValueChange: (value: string) => void
  onSubmit?: (value: string) => void
  /** The field's accessible name, which may say more than the placeholder has room for. */
  label: string
  placeholder?: string
  className?: string
  inputClassName?: string
}) {
  const clear = () => {
    onValueChange("")
    onSubmit?.("")
  }
  const field = (
    <InputGroup className={cn(!onSubmit && "min-w-[11rem] flex-1 basis-56", className)}>
      <InputGroupAddon>
        <MagnifyingGlassIcon aria-hidden />
      </InputGroupAddon>
      <InputGroupInput
        type="search"
        enterKeyHint="search"
        aria-label={label}
        placeholder={placeholder}
        value={value}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => onValueChange(event.target.value)}
        className={cn("[&::-webkit-search-cancel-button]:appearance-none", inputClassName)}
      />
      {value !== "" ? (
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-xs" aria-label={t("steward.settings.clear-search")} onClick={clear}>
            <XIcon aria-hidden />
          </InputGroupButton>
        </InputGroupAddon>
      ) : null}
    </InputGroup>
  )
  if (!onSubmit) return field
  return (
    <form
      role="search"
      className="flex min-w-[11rem] flex-1 basis-56"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit(value.trim())
      }}
    >
      {field}
    </form>
  )
}

/** The value of a {@link FilterSelect} that keeps every row. */
const EVERY = "\u0000every"

/** A filter by one value, `""` for every row, named for a screen reader and drawn without a label. */
export function FilterSelect({
  value,
  onValueChange,
  label,
  every,
  options,
  className,
}: {
  value: string
  onValueChange: (value: string) => void
  label: string
  /** What the choice that keeps every row reads. */
  every: string
  options: { value: string; label: string }[]
  className?: string
}) {
  return (
    <Select value={value === "" ? EVERY : value} onValueChange={(next) => onValueChange(next === EVERY ? "" : next)}>
      <SelectTrigger aria-label={label} className={cn("max-w-full min-w-0 shrink", className)}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent position="popper" align="end">
        <SelectItem value={EVERY}>{every}</SelectItem>
        {options.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/** A filter that is on or off, a button that stays pressed and ticked while it holds. */
export function FilterToggle({
  pressed,
  onPressedChange,
  children,
}: {
  pressed: boolean
  onPressedChange: (pressed: boolean) => void
  children: ReactNode
}) {
  return (
    <Button
      type="button"
      variant={pressed ? "secondary" : "outline"}
      aria-pressed={pressed}
      onClick={() => onPressedChange(!pressed)}
    >
      {pressed ? <CheckIcon aria-hidden data-icon="inline-start" /> : null}
      {children}
    </Button>
  )
}

/** What every row of the search shares, such as the loader and version a plugin must run on. */
export function FilterContext({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <Badge variant="outline" className="h-6 text-muted-foreground" title={title}>
      {children}
    </Badge>
  )
}
