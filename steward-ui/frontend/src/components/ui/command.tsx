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
import { InputGroup, InputGroupAddon } from "@/components/ui/input-group"
import { MagnifyingGlassIcon, CheckIcon } from "@phosphor-icons/react"

function Command({ className, ...props }: React.ComponentProps<typeof CommandPrimitive>) {
  return (
    <CommandPrimitive
      data-slot="command"
      className={cn(
        "flex size-full flex-col overflow-hidden rounded-xl! bg-popover p-1 text-popover-foreground",
        className,
      )}
      {...props}
    />
  )
}

/**
 * `label` names the input through cmdk's hidden label, and defaults to `title`.
 *
 * `aria-labelledby` points at that label and outranks `aria-label`, so leaving it empty leaves no name.
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
  /** How a row is scored, handed to cmdk's `filter`; undefined keeps its subsequence default. */
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
        /** `top-1/3` only as a dialog, since as a sheet vaul already places the shell above the keyboard. */
        className={cn("overflow-hidden rounded-xl! p-0 sm:top-1/3 sm:translate-y-0", className)}
        showCloseButton={showCloseButton}
      >
        <Command label={label ?? title} filter={filter}>
          {children}
        </Command>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

function CommandInput({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Input>) {
  return (
    <div data-slot="command-input-wrapper" className="p-1 pb-0">
      <InputGroup className="h-8! rounded-lg! border-input/30 bg-input/30 shadow-none! *:data-[slot=input-group-addon]:pl-2!">
        <CommandPrimitive.Input
          data-slot="command-input"
          className={cn("w-full text-sm outline-hidden disabled:cursor-not-allowed disabled:opacity-50", className)}
          {...props}
        />
        <InputGroupAddon>
          <MagnifyingGlassIcon className="size-4 shrink-0 opacity-50" />
        </InputGroupAddon>
      </InputGroup>
    </div>
  )
}

function CommandList({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.List>) {
  return (
    <CommandPrimitive.List
      data-slot="command-list"
      className={cn("max-h-72 scroll-py-1 overflow-x-hidden overflow-y-auto outline-none", className)}
      {...props}
    />
  )
}

function CommandEmpty({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Empty>) {
  return (
    <CommandPrimitive.Empty
      data-slot="command-empty"
      className={cn("py-6 text-center text-sm", className)}
      {...props}
    />
  )
}

function CommandGroup({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Group>) {
  return (
    <CommandPrimitive.Group
      data-slot="command-group"
      className={cn(
        "overflow-hidden p-1 text-foreground **:[[cmdk-group-heading]]:px-2 **:[[cmdk-group-heading]]:py-1.5 **:[[cmdk-group-heading]]:text-xs **:[[cmdk-group-heading]]:font-medium **:[[cmdk-group-heading]]:text-muted-foreground",
        className,
      )}
      {...props}
    />
  )
}

function CommandSeparator({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Separator>) {
  return (
    <CommandPrimitive.Separator
      data-slot="command-separator"
      className={cn("-mx-1 h-px bg-border", className)}
      {...props}
    />
  )
}

function CommandItem({ className, children, ...props }: React.ComponentProps<typeof CommandPrimitive.Item>) {
  return (
    <CommandPrimitive.Item
      data-slot="command-item"
      className={cn(
        "group/command-item relative flex cursor-default items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-hidden select-none in-data-[slot=dialog-content]:rounded-lg! in-data-[slot=drawer-content]:rounded-lg! data-[disabled=true]:pointer-events-none data-[disabled=true]:opacity-50 data-selected:bg-muted data-selected:text-foreground [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4 data-selected:*:[svg]:text-foreground",
        className,
      )}
      {...props}
    >
      {children}
      <CheckIcon className="ml-auto opacity-0 group-has-data-[slot=command-shortcut]/command-item:hidden group-data-[checked=true]/command-item:opacity-100" />
    </CommandPrimitive.Item>
  )
}

function CommandShortcut({ className, ...props }: React.ComponentProps<"span">) {
  return (
    <span
      data-slot="command-shortcut"
      className={cn(
        /** Capped so a long note cannot squeeze the label out, and hidden below 640px where it would truncate both. */
        "ml-auto hidden max-w-[45%] truncate text-xs tracking-widest text-muted-foreground group-data-selected/command-item:text-foreground sm:block",
        className,
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
