import * as React from "react"
import { cn } from "cn"
import { Drawer as DrawerPrimitive } from "vaul"

/**
 * A bottom sheet, every dialog's shape on a narrow screen, used only through {@link ResponsiveDialog}.
 *
 * Bottom only, since `sheet.tsx` already covers the sides; `vaul` does the drag. Not the lift above the keyboard:
 * vaul's ignores the pan iOS adds, so the sheet stands on `--keyboard-inset` instead.
 */
function Drawer({ ...props }: React.ComponentProps<typeof DrawerPrimitive.Root>) {
  return <DrawerPrimitive.Root data-slot="drawer" repositionInputs={false} {...props} />
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

/** The dialog's black wash, faded by vaul's own state attribute since vaul drives the sheet's transform. */
function DrawerOverlay({ className, ...props }: React.ComponentProps<typeof DrawerPrimitive.Overlay>) {
  return (
    <DrawerPrimitive.Overlay
      data-slot="drawer-overlay"
      className={cn("fixed inset-0 z-50 bg-black/10 supports-backdrop-filter:backdrop-blur-xs", className)}
      {...props}
    />
  )
}

/**
 * Moves the focus into a sheet that opens, which vaul leaves where it was unless the sheet's own field took it.
 *
 * A field outside that kept it would keep the keyboard open over the sheet, so it is blurred first.
 */
function takeTheFocus(event: Event) {
  const sheet = event.target
  if (!(sheet instanceof HTMLElement) || sheet.contains(document.activeElement)) return
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
  sheet.focus({ preventScroll: true })
}

function DrawerContent({
  className,
  children,
  onOpenAutoFocus,
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Content>) {
  return (
    <DrawerPortal data-slot="drawer-portal">
      <DrawerOverlay />
      <DrawerPrimitive.Content
        data-slot="drawer-content"
        onOpenAutoFocus={(event) => {
          onOpenAutoFocus?.(event)
          takeTheFocus(event)
        }}
        className={cn(
          /**
           * `svh`, not `vh`, since on iOS a sheet sized in `vh` puts its footer under the browser chrome. With the
           * keyboard open it stands on the keyboard and fits what is left visible, both measured by `lib/app-frame.ts`.
           *
           * `--sheet-gutter` is the sheet's side padding, which a sheet that pads its own rows reads instead of adding to.
           */
          "fixed inset-x-0 bottom-(--keyboard-inset,0px) z-50 flex max-h-[min(90svh,calc(var(--visible-height,100svh)-env(safe-area-inset-top)-0.5rem))] flex-col gap-4 rounded-t-xl bg-popover px-(--sheet-gutter) pt-4 [--sheet-gutter:1.5rem] pb-[max(1rem,env(safe-area-inset-bottom))] text-sm text-popover-foreground ring-1 ring-foreground/10 outline-none",
          className,
        )}
        {...props}
      >
        {/* A picture of the grab handle, not a control, since vaul takes the drag from the whole sheet. */}
        <div aria-hidden className="mx-auto h-1 w-10 shrink-0 rounded-full bg-muted-foreground/30" />
        {children}
      </DrawerPrimitive.Content>
    </DrawerPortal>
  )
}

function DrawerHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="drawer-header" className={cn("flex flex-col gap-2", className)} {...props} />
}

/** The footer of `DialogFooter`, always a column below the breakpoint, the affirmative button under the thumb. */
function DrawerFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="drawer-footer"
      className={cn(
        "-mx-(--sheet-gutter) -mb-4 mt-auto flex flex-col-reverse gap-2 border-t bg-muted/50 px-(--sheet-gutter) pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]",
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
