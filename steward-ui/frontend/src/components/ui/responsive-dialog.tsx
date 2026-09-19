import * as React from "react"

import { useIsMobile } from "@/hooks/use-mobile"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
  DrawerTrigger,
} from "@/components/ui/drawer"

/**
 * One dialog, drawn as a bottom sheet on a narrow screen and as a centred dialog above it.
 *
 * Till, 2026-09-18: every dialog switches over, globally - and the point of this file is that
 * last word. The decision is made here, once, and no page decides it again: a call site that asked
 * `useIsMobile()` and picked its own would be the first of six that drift, and a half-converted
 * interface is worse than an unconverted one, because the boundary between the halves is invisible
 * until somebody is holding a phone.
 *
 * **What a call site changes is the import and nothing else.** The eight names below are the eight
 * `dialog.tsx` exports these callers used, with the same props, so converting a page is its import
 * line plus a rename.
 *
 * <h2>The breakpoint is the sidebar's</h2>
 * `useIsMobile` is 768px, and it is already what decides whether the navigation is a column or a
 * sheet. A second breakpoint for dialogs would allow a state in which the navigation thinks it is
 * on a phone and a dialog does not.
 *
 * <h2>What it deliberately does not cover</h2>
 * `AlertDialog` - the confirmation in front of a destructive action. That is a different component
 * with a different promise: it cannot be dismissed by clicking away, because the whole point is
 * that an answer is given. A bottom sheet is dismissed by flicking it off the screen with a thumb,
 * which is the opposite promise, and `vaul`'s `dismissible={false}` leaves something that looks
 * draggable and is not. Converting those seven call sites is a decision about what a confirmation
 * is, not a mechanical change, and steward/98 names it as the remainder rather than doing it
 * quietly.
 *
 * <p>`components/ui/command.tsx` is the other one left alone, and for a reason of its own: the
 * command palette is a text field that has to stay above the on-screen keyboard, and a sheet puts
 * it at the bottom edge - which is exactly where the keyboard comes up. Its drag gesture would also
 * fight the scroll of a list that is re-filtering under the thumb. It keeps its own shell.</p>
 */
const ResponsiveContext = React.createContext(false)

/** Whether this subtree is being drawn as a sheet. Read only by the components in this file. */
function useSheet(): boolean {
  return React.useContext(ResponsiveContext)
}

export function ResponsiveDialog({
  children,
  ...props
}: React.ComponentProps<typeof Dialog>) {
  // Answered `false` on the very first render and corrected in an effect - which is fine here, and
  // is worth saying why: these are mounted closed, at the top of a page or beside a popover, so the
  // correction has long happened before anything is on screen. What it does mean is that crossing
  // the breakpoint WHILE a dialog is open remounts its contents; nothing in this interface holds
  // unsaved text across a rotation, and the alternative - reading the width during render - is the
  // hazard `useIsMobile` exists to avoid.
  const sheet = useIsMobile()
  const Root = sheet ? Drawer : Dialog

  return (
    <ResponsiveContext.Provider value={sheet}>
      <Root {...props}>{children}</Root>
    </ResponsiveContext.Provider>
  )
}

export function ResponsiveDialogTrigger(props: React.ComponentProps<typeof DialogTrigger>) {
  const Trigger = useSheet() ? DrawerTrigger : DialogTrigger
  return <Trigger {...props} />
}

export function ResponsiveDialogContent({
  showCloseButton,
  ...props
}: React.ComponentProps<typeof DialogContent>) {
  // A sheet has no close button: it is closed by flicking it away or tapping the overlay, and a
  // second way to do it in the top corner is the corner a thumb cannot reach anyway.
  if (useSheet()) {
    return <DrawerContent {...props} />
  }
  return <DialogContent showCloseButton={showCloseButton} {...props} />
}

export function ResponsiveDialogHeader(props: React.ComponentProps<typeof DialogHeader>) {
  const Header = useSheet() ? DrawerHeader : DialogHeader
  return <Header {...props} />
}

export function ResponsiveDialogTitle(props: React.ComponentProps<typeof DialogTitle>) {
  const Title = useSheet() ? DrawerTitle : DialogTitle
  return <Title {...props} />
}

export function ResponsiveDialogDescription(
  props: React.ComponentProps<typeof DialogDescription>,
) {
  const Description = useSheet() ? DrawerDescription : DialogDescription
  return <Description {...props} />
}

export function ResponsiveDialogFooter({
  showCloseButton,
  ...props
}: React.ComponentProps<typeof DialogFooter>) {
  if (useSheet()) {
    return <DrawerFooter {...props} />
  }
  return <DialogFooter showCloseButton={showCloseButton} {...props} />
}

export function ResponsiveDialogClose(props: React.ComponentProps<typeof DialogClose>) {
  const Close = useSheet() ? DrawerClose : DialogClose
  return <Close {...props} />
}
