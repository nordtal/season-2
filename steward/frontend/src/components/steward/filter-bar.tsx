import { CheckIcon, FunnelSimpleIcon, MagnifyingGlassIcon, XIcon } from "@phosphor-icons/react"
import { useState, type ReactNode, type Ref } from "react"
import { cn } from "cn"

import { t } from "@/lib/texts"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@/components/ui/input-group"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/**
 * The row wraps only when the controls do not fit on one line. The field is never narrower than half a phone.
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
 * The one search field of every list, styled here alone. With `onSubmit` it filters on Enter only, as an exact match must.
 *
 * Clearing submits the empty value at once.
 */
export function SearchField({
  value,
  onValueChange,
  onSubmit,
  label,
  placeholder = t("steward.settings.search"),
  inputMode,
}: {
  value: string
  onValueChange: (value: string) => void
  onSubmit?: (value: string) => void
  inputMode?: "numeric"
  label: string
  placeholder?: string
}) {
  const clear = () => {
    onValueChange("")
    onSubmit?.("")
  }
  const field = (
    <InputGroup className={cn(!onSubmit && "min-w-[11rem] flex-1 basis-56")}>
      <InputGroupAddon>
        <MagnifyingGlassIcon aria-hidden />
      </InputGroupAddon>
      <InputGroupInput
        type="search"
        enterKeyHint="search"
        inputMode={inputMode}
        aria-label={label}
        placeholder={placeholder}
        value={value}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => onValueChange(event.target.value)}
        className="[&::-webkit-search-cancel-button]:hidden"
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

/** The button that opens a list's filters. It is filled and counts them while any is active. */
export function FilterMenu({ active, children }: { active: number; children: ReactNode }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant={active > 0 ? "secondary" : "outline"}>
          <FunnelSimpleIcon aria-hidden data-icon="inline-start" />
          {t("steward.settings.filters")}
          {/* The space makes the name "Filters 2" and not "Filters2". */}
          {active > 0 ? " " : null}
          {active > 0 ? (
            <Badge variant="default" className="tnum">
              {active}
            </Badge>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="max-h-(--radix-popover-content-available-height) gap-3 overflow-y-auto">
        {children}
      </PopoverContent>
    </Popover>
  )
}

/** One filter in the menu. Its tick stands after the label, so the label never moves. */
export function FilterOption({
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
      variant="ghost"
      aria-pressed={pressed}
      onClick={() => onPressedChange(!pressed)}
      className="justify-start font-normal aria-pressed:bg-muted"
    >
      {children}
      <CheckIcon aria-hidden data-icon="inline-end" className={cn("ml-auto", !pressed && "invisible")} />
    </Button>
  )
}

/** A named group of options of which one at most applies. `""` is none, and pressing the chosen one again clears it. */
export function FilterChoice({
  label,
  value,
  onValueChange,
  options,
}: {
  label: string
  value: string
  onValueChange: (value: string) => void
  options: { value: string; label: string }[]
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-col">
      <span className="px-2.5 pb-1 text-xs font-medium text-muted-foreground">{label}</span>
      {options.map((option) => (
        <FilterOption
          key={option.value}
          pressed={value === option.value}
          onPressedChange={(pressed) => onValueChange(pressed ? option.value : "")}
        >
          {option.label}
        </FilterOption>
      ))}
    </div>
  )
}

/** An exact value in the menu, which applies on Enter. */
export function FilterField({
  label,
  value,
  onSubmit,
  inputMode,
}: {
  label: string
  value: string
  onSubmit: (value: string) => void
  inputMode?: "numeric"
}) {
  const [typed, setTyped] = useState(value)
  return (
    <div className="flex flex-col">
      <span className="px-2.5 pb-1 text-xs font-medium text-muted-foreground">{label}</span>
      {/* A row, so the field's flex basis is a width and not a height. */}
      <div className="flex">
        <SearchField
          value={typed}
          onValueChange={setTyped}
          onSubmit={onSubmit}
          label={label}
          placeholder=""
          inputMode={inputMode}
        />
      </div>
    </div>
  )
}

export function FilterContext({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <Badge variant="outline" className="h-6 text-muted-foreground" title={title}>
      {children}
    </Badge>
  )
}
