import * as React from "react"
import { cn } from "cn"
import { Drawer as DrawerPrimitive } from "vaul"

/**
 * A bottom sheet - the shape every dialog in Steward takes on a narrow screen.
 *
 * Till, 2026-09-18, asked for every dialog in the interface to be switched over at one common
 * place. Nothing imports this file directly; the one
 * thing that does is {@link ResponsiveDialog}, which is where the decision "sheet or dialog" is made
 * once for the whole interface. Importing `Drawer` at a call site would be a second place that
 * decides, which is how half of an interface ends up converted.
 *
 * **Bottom only, and that is the whole component.** shadcn's own drawer takes a `direction` and
 * carries four sets of classes for it. Three of those directions are a side sheet, and this
 * interface already has one of those (`components/ui/sheet.tsx`, which the navigation uses on a
 * phone). A drawer that can also come from the left is a second way to do the thing `sheet` already
 * does, and the first person to reach for it would be choosing between two components that look
 * identical on screen.
 *
 * **`vaul` and not a hand-rolled transform.** What a bottom sheet has to get right is the drag: it
 * follows the thumb, it has a velocity threshold rather than a position one, and it puts the scroll
 * of the content and the drag of the sheet in the right order so that a list inside it can still be
 * scrolled. That is the whole of `vaul`, it is 14 kB, and it is what shadcn's own `Drawer` is built
 * on - writing a second one against `radix-ui`'s `Dialog` would be the same code with less of it
 * tested.
 */
function Drawer({ ...props }: React.ComponentProps<typeof DrawerPrimitive.Root>) {
  return <DrawerPrimitive.Root data-slot="drawer" {...props} />
}

function DrawerTrigger({ ...props }: React.ComponentProps<typeof DrawerPrimitive.Trigger>) {
  return <DrawerPrimitive.Trigger data-slot="drawer-trigger" {...props} />
}

function DrawerPortal({ ...props }: React.ComponentProps<typeof DrawerPrimitive.Portal>) {
  return <DrawerPrimitive.Portal data-slot="drawer-portal" {...props} />
}

function DrawerClose({ ...props }: React.ComponentProps<typeof DrawerPrimitive.Close>) {
  return <DrawerPrimitive.Close data-slot="drawer-close" {...props} />
}

/**
 * The same black wash the dialog uses, so that the two do not look like two components.
 *
 * `vaul` animates the sheet itself with a transform it drives frame by frame, so there is no
 * `data-open:animate-in` here the way `dialog.tsx` has one - the overlay fades with vaul's own
 * state attribute instead.
 */
function DrawerOverlay({ className, ...props }: React.ComponentProps<typeof DrawerPrimitive.Overlay>) {
  return (
    <DrawerPrimitive.Overlay
      data-slot="drawer-overlay"
      className={cn("fixed inset-0 z-50 bg-black/10 supports-backdrop-filter:backdrop-blur-xs", className)}
      {...props}
    />
  )
}

function DrawerContent({ className, children, ...props }: React.ComponentProps<typeof DrawerPrimitive.Content>) {
  return (
    <DrawerPortal data-slot="drawer-portal">
      <DrawerOverlay />
      <DrawerPrimitive.Content
        data-slot="drawer-content"
        className={cn(
          // `max-h-[90svh]`, not `vh`: on iOS the visual viewport shrinks when the address bar is
          // out and a sheet sized in `vh` puts its own footer under the browser chrome.
          "fixed inset-x-0 bottom-0 z-50 flex max-h-[90svh] flex-col gap-4 rounded-t-xl bg-popover p-4 pb-[max(1rem,env(safe-area-inset-bottom))] text-sm text-popover-foreground ring-1 ring-foreground/10 outline-none",
          className,
        )}
        {...props}
      >
        {/*
          The grab handle, which is the only thing on screen that says this can be dragged. It is
          not a button and is not focusable: `vaul` takes the drag from the whole sheet, so this is
          a picture of an affordance rather than the control itself.
        */}
        <div aria-hidden className="mx-auto h-1 w-10 shrink-0 rounded-full bg-muted-foreground/30" />
        {children}
      </DrawerPrimitive.Content>
    </DrawerPortal>
  )
}

function DrawerHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="drawer-header" className={cn("flex flex-col gap-2", className)} {...props} />
}

/**
 * The footer, styled to match `DialogFooter` - the same bar, the same edge-to-edge treatment.
 *
 * Column and not `sm:flex-row`: this only ever renders below the breakpoint, so the row variant
 * would be a rule that can never apply. The order is reversed for the same reason the dialog's is:
 * the affirmative button belongs under the thumb.
 */
function DrawerFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="drawer-footer"
      className={cn(
        "-mx-4 -mb-4 mt-auto flex flex-col-reverse gap-2 border-t bg-muted/50 p-4 pb-[max(1rem,env(safe-area-inset-bottom))]",
        className,
      )}
      {...props}
    />
  )
}

function DrawerTitle({ className, ...props }: React.ComponentProps<typeof DrawerPrimitive.Title>) {
  return (
    <DrawerPrimitive.Title
      data-slot="drawer-title"
      className={cn("font-heading text-base leading-none font-medium", className)}
      {...props}
    />
  )
}

function DrawerDescription({ className, ...props }: React.ComponentProps<typeof DrawerPrimitive.Description>) {
  return (
    <DrawerPrimitive.Description
      data-slot="drawer-description"
      className={cn(
        "text-sm text-muted-foreground *:[a]:underline *:[a]:underline-offset-3 *:[a]:hover:text-foreground",
        className,
      )}
      {...props}
    />
  )
}

export {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerOverlay,
  DrawerPortal,
  DrawerTitle,
  DrawerTrigger,
}
