import * as React from "react"
import { cn } from "cn"
import { Tooltip as TooltipPrimitive } from "radix-ui"

function TooltipProvider({ delayDuration = 0, ...props }: React.ComponentProps<typeof TooltipPrimitive.Provider>) {
  return <TooltipPrimitive.Provider data-slot="tooltip-provider" delayDuration={delayDuration} {...props} />
}

/** What a trigger needs to turn a tap into a toggle: whether the tip is open, and a way to set it. */
const TapContext = React.createContext<{
  isOpen: () => boolean
  toggle: (open: boolean) => void
} | null>(null)

/**
 * A tooltip that also opens on a tap, since a touch screen has no hover and a tap only focuses.
 *
 * A mouse and the keyboard keep Radix's own behaviour; a tap on the trigger toggles, a tap elsewhere closes.
 */
function Tooltip({ onOpenChange, defaultOpen = false, ...props }: React.ComponentProps<typeof TooltipPrimitive.Root>) {
  const [open, setOpen] = React.useState(defaultOpen)
  const tapping = React.useRef(false)

  const change = (next: boolean) => {
    /** Radix closes the tip on every click, which would undo the tap that opened it. */
    if (!next && tapping.current) return
    setOpen(next)
    onOpenChange?.(next)
  }
  const context = {
    isOpen: () => open,
    toggle: (next: boolean) => {
      tapping.current = true
      setTimeout(() => (tapping.current = false), 0)
      change(next)
    },
  }

  return (
    <TapContext.Provider value={context}>
      <TooltipPrimitive.Root data-slot="tooltip" {...props} open={open} onOpenChange={change} />
    </TapContext.Provider>
  )
}

function TooltipTrigger({ onPointerDown, onClick, ...props }: React.ComponentProps<typeof TooltipPrimitive.Trigger>) {
  const tap = React.useContext(TapContext)
  /** Whether the open tip is seen before this press closes it, and whether the press came from a finger. */
  const press = React.useRef<{ touch: boolean; wasOpen: boolean }>({ touch: false, wasOpen: false })

  return (
    <TooltipPrimitive.Trigger
      data-slot="tooltip-trigger"
      onPointerDown={(event) => {
        onPointerDown?.(event)
        press.current = { touch: event.pointerType === "touch", wasOpen: tap?.isOpen() ?? false }
      }}
      onClick={(event) => {
        onClick?.(event)
        if (press.current.touch) tap?.toggle(!press.current.wasOpen)
        press.current.touch = false
      }}
      {...props}
    />
  )
}

function TooltipContent({
  className,
  sideOffset = 0,
  collisionPadding = 8,
  children,
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        data-slot="tooltip-content"
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        className={cn(
          "z-50 inline-flex w-fit max-w-xs origin-(--radix-tooltip-content-transform-origin) items-center gap-1.5 rounded-md bg-popover px-3 py-1.5 text-xs text-popover-foreground shadow-foreground/10 shadow-sm has-data-[slot=kbd]:pr-1.5 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 **:data-[slot=kbd]:relative **:data-[slot=kbd]:isolate **:data-[slot=kbd]:z-50 **:data-[slot=kbd]:rounded-sm data-[state=delayed-open]:animate-in data-[state=delayed-open]:fade-in-0 data-[state=delayed-open]:zoom-in-95 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95",
          className,
        )}
        {...props}
      >
        {children}
        {/* The arrow takes the body's surface as `fill` for Radix's svg and `background` for shadcn's square. */}
        <TooltipPrimitive.Arrow className="z-50 size-2.5 translate-y-[calc(-50%_-_2px)] rotate-45 rounded-[2px] bg-popover fill-popover" />
      </TooltipPrimitive.Content>
    </TooltipPrimitive.Portal>
  )
}

export { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger }
