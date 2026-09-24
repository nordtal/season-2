import * as React from "react"
import { Command as CommandPrimitive } from "cmdk"
import { cn } from "cn"

import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import {
  InputGroup,
  InputGroupAddon,
} from "@/components/ui/input-group"
import { MagnifyingGlassIcon, CheckIcon } from "@phosphor-icons/react"

function Command({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive>) {
  return (
    <CommandPrimitive
      data-slot="command"
      className={cn(
        "flex size-full flex-col overflow-hidden rounded-xl! bg-popover p-1 text-popover-foreground",
        className
      )}
      {...props}
    />
  )
}

/**
 * `label` is not decoration, and leaving it out is how the input lost its name (steward/100).
 *
 * `cmdk` always renders a visually hidden `<label>` for the input and points the input's
 * `aria-labelledby` at it. Without a `label` that element is **empty** - and `aria-labelledby` wins
 * over `aria-label`, so the field ends up with no accessible name at all however many labels are
 * added from outside. Measured 2026-09-17: a screen reader announced "combobox" and nothing else,
 * because `role="combobox"` also removes the browser's placeholder-as-name fallback (HTML-AAM
 * grants that to the native textbox role only). Filling cmdk's own label is the fix; anything else
 * is shouted down by the empty one.
 *
 * It defaults to `title` so a caller that says nothing still gets a name rather than none.
 */
function CommandDialog({
  title = "Command Palette",
  description = "Search for a command to run...",
  label,
  filter,
  children,
  className,
  showCloseButton = false,
  ...props
}: React.ComponentProps<typeof ResponsiveDialog> & {
  title?: string
  description?: string
  label?: string
  /**
   * How a row is scored against what was typed, handed straight to `cmdk`'s own `filter`.
   *
   * Named here rather than left to the spread above, because the spread goes to the shell and
   * would have dropped it silently (steward/105). Undefined keeps cmdk's default subsequence
   * filter.
   */
  filter?: React.ComponentProps<typeof CommandPrimitive>["filter"]
  className?: string
  showCloseButton?: boolean
}) {
  return (
    <ResponsiveDialog {...props}>
      <ResponsiveDialogHeader className="sr-only">
        <ResponsiveDialogTitle>{title}</ResponsiveDialogTitle>
        <ResponsiveDialogDescription>{description}</ResponsiveDialogDescription>
      </ResponsiveDialogHeader>
      <ResponsiveDialogContent
        // `top-1/3` is the dialog half only. As a sheet the shell already sits at the bottom edge
        // and vaul moves it above the on-screen keyboard once the input takes focus, so a second
        // opinion about vertical position here would fight it.
        className={cn(
          "overflow-hidden rounded-xl! p-0 sm:top-1/3 sm:translate-y-0",
          className
        )}
        showCloseButton={showCloseButton}
      >
        <Command label={label ?? title} filter={filter}>
          {children}
        </Command>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

function CommandInput({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Input>) {
  return (
    <div data-slot="command-input-wrapper" className="p-1 pb-0">
      <InputGroup className="h-8! rounded-lg! border-input/30 bg-input/30 shadow-none! *:data-[slot=input-group-addon]:pl-2!">
        <CommandPrimitive.Input
          data-slot="command-input"
          className={cn(
            "w-full text-sm outline-hidden disabled:cursor-not-allowed disabled:opacity-50",
            className
          )}
          {...props}
        />
        <InputGroupAddon>
          <MagnifyingGlassIcon className="size-4 shrink-0 opacity-50" />
        </InputGroupAddon>
      </InputGroup>
    </div>
  )
}

function CommandList({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.List>) {
  return (
    <CommandPrimitive.List
      data-slot="command-list"
      className={cn(
        "no-scrollbar max-h-72 scroll-py-1 overflow-x-hidden overflow-y-auto outline-none",
        className
      )}
      {...props}
    />
  )
}

function CommandEmpty({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Empty>) {
  return (
    <CommandPrimitive.Empty
      data-slot="command-empty"
      className={cn("py-6 text-center text-sm", className)}
      {...props}
    />
  )
}

function CommandGroup({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Group>) {
  return (
    <CommandPrimitive.Group
      data-slot="command-group"
      className={cn(
        "overflow-hidden p-1 text-foreground **:[[cmdk-group-heading]]:px-2 **:[[cmdk-group-heading]]:py-1.5 **:[[cmdk-group-heading]]:text-xs **:[[cmdk-group-heading]]:font-medium **:[[cmdk-group-heading]]:text-muted-foreground",
        className
      )}
      {...props}
    />
  )
}

function CommandSeparator({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Separator>) {
  return (
    <CommandPrimitive.Separator
      data-slot="command-separator"
      className={cn("-mx-1 h-px bg-border", className)}
      {...props}
    />
  )
}

function CommandItem({
  className,
  children,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Item>) {
  return (
    <CommandPrimitive.Item
      data-slot="command-item"
      className={cn(
        "group/command-item relative flex cursor-default items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-hidden select-none in-data-[slot=dialog-content]:rounded-lg! in-data-[slot=drawer-content]:rounded-lg! data-[disabled=true]:pointer-events-none data-[disabled=true]:opacity-50 data-selected:bg-muted data-selected:text-foreground [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4 data-selected:*:[svg]:text-foreground",
        className
      )}
      {...props}
    >
      {children}
      <CheckIcon className="ml-auto opacity-0 group-has-data-[slot=command-shortcut]/command-item:hidden group-data-[checked=true]/command-item:opacity-100" />
    </CommandPrimitive.Item>
  )
}

function CommandShortcut({
  className,
  ...props
}: React.ComponentProps<"span">) {
  return (
    <span
      data-slot="command-shortcut"
      className={cn(
        // steward/105: a relative ceiling, so a long trailing note cannot squeeze the white label it
        // sits beside out of the row. The label shrinks first because it carries `flex-1`.
        //
        // steward/127: GONE below 640px, not smaller and not fainter. On a phone this column is a
        // path, and a path is the thing that shortens the name in order to be cut off itself - two
        // truncated strings where one whole one would have fitted. 640px is the app's own line
        // between narrow and wide, the same one `useIsMobile` and every dialog switch on.
        "ml-auto hidden max-w-[45%] truncate text-xs tracking-widest text-muted-foreground group-data-selected/command-item:text-foreground sm:block",
        className
      )}
      {...props}
    />
  )
}

export {
  Command,
  CommandDialog,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandShortcut,
  CommandSeparator,
}
