import * as React from "react"

import { useIsMobile } from "@/hooks/use-mobile"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
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
 * `useIsMobile` is 640px, and it is already what decides whether the navigation is a column or a
 * sheet. A second breakpoint for dialogs would allow a state in which the navigation thinks it is
 * on a phone and a dialog does not.
 *
 * <h2>The two exceptions steward/98 wrote down are gone, and both on purpose</h2>
 * This file used to say that a confirmation could not be a sheet, because a sheet is dismissed by
 * flicking it away and a confirmation has to be answered. Till answered that on 2026-09-20:
 * flicking it away **is** an answer, and it is the same answer a click on the overlay already
 * gives - a cancel. There was never anything to lose, so `ResponsiveAlertDialog` below is the same
 * switch for the seven confirmations.
 *
 * <p>The other exception was the command palette, on the grounds that a sheet puts its text field
 * exactly where the on-screen keyboard comes up. That was a requirement of this component written
 * as a reason not to build it: `vaul` repositions a sheet that contains a focused input above the
 * keyboard, which is what `repositionInputs` does and it is on by default. So `command.tsx` builds
 * its shell out of these exports too, and `dialogs-are-responsive.test.ts` is what keeps the next
 * dialog from being a raw one.</p>
 *
 * <h2>What is still not this</h2>
 * `components/ui/sidebar.tsx`'s `Sheet` - the navigation on a phone. It comes from the side, it is
 * not a dialog, and it is the one place a side sheet is the right shape. It is a named exception in
 * the source rule rather than an unwritten one.
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

/**
 * The confirmation in front of something that cannot be taken back, in both shapes.
 *
 * <p>Radix's `AlertDialog` and `Dialog` differ in exactly two things: the role announced to a
 * screen reader (`alertdialog`), and that a click on the overlay does not close it. Only the first
 * survives the switch to a sheet, and that is fine - a sheet answers "no" when it is flicked away,
 * and so does the overlay of the dialog it replaces.</p>
 *
 * <p>The names mirror `alert-dialog.tsx` one for one, so converting a call site is its import line
 * and a rename, the same trade the plain half above makes.</p>
 */
export function ResponsiveAlertDialog({
  children,
  ...props
}: React.ComponentProps<typeof AlertDialog>) {
  const sheet = useIsMobile()
  const Root = sheet ? Drawer : AlertDialog

  return (
    <ResponsiveContext.Provider value={sheet}>
      <Root {...props}>{children}</Root>
    </ResponsiveContext.Provider>
  )
}

export function ResponsiveAlertDialogTrigger(
  props: React.ComponentProps<typeof AlertDialogTrigger>,
) {
  const Trigger = useSheet() ? DrawerTrigger : AlertDialogTrigger
  return <Trigger {...props} />
}

export function ResponsiveAlertDialogContent(
  props: React.ComponentProps<typeof AlertDialogContent>,
) {
  const Content = useSheet() ? DrawerContent : AlertDialogContent
  return <Content {...props} />
}

export function ResponsiveAlertDialogHeader(
  props: React.ComponentProps<typeof AlertDialogHeader>,
) {
  const Header = useSheet() ? DrawerHeader : AlertDialogHeader
  return <Header {...props} />
}

export function ResponsiveAlertDialogTitle(
  props: React.ComponentProps<typeof AlertDialogTitle>,
) {
  const Title = useSheet() ? DrawerTitle : AlertDialogTitle
  return <Title {...props} />
}

export function ResponsiveAlertDialogDescription(
  props: React.ComponentProps<typeof AlertDialogDescription>,
) {
  const Description = useSheet() ? DrawerDescription : AlertDialogDescription
  return <Description {...props} />
}

export function ResponsiveAlertDialogFooter(
  props: React.ComponentProps<typeof AlertDialogFooter>,
) {
  const Footer = useSheet() ? DrawerFooter : AlertDialogFooter
  return <Footer {...props} />
}

/**
 * The button that does the thing, and closes whichever shell it is in.
 *
 * <p>`AlertDialogAction` is a `Button` with Radix's `Action` inside it, and the sheet half is the
 * same sentence with `DrawerClose` in that place: both are a close, so an `onClick` that starts a
 * mutation runs and the shell goes away, in that order, in both shapes.</p>
 */
export function ResponsiveAlertDialogAction({
  variant = "default",
  size = "default",
  ...props
}: React.ComponentProps<typeof AlertDialogAction>) {
  if (useSheet()) {
    return (
      <Button variant={variant} size={size} asChild>
        <DrawerClose {...(props as React.ComponentProps<typeof DrawerClose>)} />
      </Button>
    )
  }
  return <AlertDialogAction variant={variant} size={size} {...props} />
}

/** The button that answers no. Outlined in both shapes, and a close in both. */
export function ResponsiveAlertDialogCancel({
  variant = "outline",
  size = "default",
  ...props
}: React.ComponentProps<typeof AlertDialogCancel>) {
  if (useSheet()) {
    return (
      <Button variant={variant} size={size} asChild>
        <DrawerClose {...(props as React.ComponentProps<typeof DrawerClose>)} />
      </Button>
    )
  }
  return <AlertDialogCancel variant={variant} size={size} {...props} />
}
