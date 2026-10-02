import * as React from "react"

import { useIsMobile } from "@/hooks/use-mobile"
import {
  AlertDialog,
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
 * One dialog, a bottom sheet below the sidebar's 640px breakpoint and a centred dialog above it.
 *
 * The exports mirror `dialog.tsx` one for one, so converting a call site is its import and a rename.
 */
const ResponsiveContext = React.createContext(false)

/** Whether this subtree is drawn as a sheet; read only by the components in this file. */
function useSheet(): boolean {
  return React.useContext(ResponsiveContext)
}

export function ResponsiveDialog({ children, ...props }: React.ComponentProps<typeof Dialog>) {
  /** `false` on the first render and corrected in an effect, so crossing the breakpoint while open remounts. */
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

export function ResponsiveDialogContent({ showCloseButton, ...props }: React.ComponentProps<typeof DialogContent>) {
  /** A sheet is closed by flicking it away or tapping the overlay, so it has no close button. */
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

export function ResponsiveDialogDescription(props: React.ComponentProps<typeof DialogDescription>) {
  const Description = useSheet() ? DrawerDescription : DialogDescription
  return <Description {...props} />
}

export function ResponsiveDialogFooter({ showCloseButton, ...props }: React.ComponentProps<typeof DialogFooter>) {
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
 * Flicking the sheet away answers no, like a click on the dialog's overlay.
 */
export function ResponsiveAlertDialog({ children, ...props }: React.ComponentProps<typeof AlertDialog>) {
  const sheet = useIsMobile()
  const Root = sheet ? Drawer : AlertDialog

  return (
    <ResponsiveContext.Provider value={sheet}>
      <Root {...props}>{children}</Root>
    </ResponsiveContext.Provider>
  )
}

export function ResponsiveAlertDialogTrigger(props: React.ComponentProps<typeof AlertDialogTrigger>) {
  const Trigger = useSheet() ? DrawerTrigger : AlertDialogTrigger
  return <Trigger {...props} />
}

export function ResponsiveAlertDialogContent(props: React.ComponentProps<typeof AlertDialogContent>) {
  const Content = useSheet() ? DrawerContent : AlertDialogContent
  return <Content {...props} />
}

export function ResponsiveAlertDialogHeader(props: React.ComponentProps<typeof AlertDialogHeader>) {
  const Header = useSheet() ? DrawerHeader : AlertDialogHeader
  return <Header {...props} />
}

export function ResponsiveAlertDialogTitle(props: React.ComponentProps<typeof AlertDialogTitle>) {
  const Title = useSheet() ? DrawerTitle : AlertDialogTitle
  return <Title {...props} />
}

export function ResponsiveAlertDialogDescription(props: React.ComponentProps<typeof AlertDialogDescription>) {
  const Description = useSheet() ? DrawerDescription : AlertDialogDescription
  return <Description {...props} />
}

export function ResponsiveAlertDialogFooter(props: React.ComponentProps<typeof AlertDialogFooter>) {
  const Footer = useSheet() ? DrawerFooter : AlertDialogFooter
  return <Footer {...props} />
}

/** The button that answers no, outlined and a close in both shapes. */
export function ResponsiveAlertDialogCancel({
  variant = "outline",
  size = "default",
  ...props
}: React.ComponentProps<typeof AlertDialogCancel>) {
  if (useSheet()) {
    return (
      <Button variant={variant} size={size} asChild>
        <DrawerClose {...props} />
      </Button>
    )
  }
  return <AlertDialogCancel variant={variant} size={size} {...props} />
}
