import { CommandPalette } from "@/app/command-palette"
import { AppFrame } from "@/app/frames"
import { HoldKeyPage } from "@/app/hold-key"
import { SecurityKeyPage } from "@/app/security-key"
import { sidebarDefaultOpen } from "@/app/sidebar-state"
import { StepUp } from "@/app/step-up"
import { SignInPage } from "@/app/sign-in"
import { StewardMark } from "@/app/steward-mark"
import { ApiError } from "@/lib/api"
import type { Me } from "@/lib/api"
import { useMe } from "@/lib/queries"
import { useIsMobile } from "@/hooks/use-mobile"
import { Failure } from "@/components/steward/query-state"
import { SidebarProvider } from "@/components/ui/sidebar"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"

export { breadcrumbsFor } from "@/app/breadcrumbs"

/**
 * The shell: a fixed viewport with one scrolling column, and navigation from `app/frames.tsx`.
 *
 * Its height is `--app-height`, measured by `lib/app-frame.ts`, and every edge gives back its safe area inset.
 */
export function Shell() {
  const me = useMe()
  const isMobile = useIsMobile()

  /** Draws nothing until `/api/me` answers, so no sidebar of 401ing pages flashes up first. */
  if (me.isPending) return <SignInPage loading />

  /** Only a 401 is the signed out state; any other failure is a fault and must not draw the sign in page. */
  const signedOut = me.data ? !me.data.signedIn : me.error instanceof ApiError && me.error.isSignedOut
  if (signedOut) return <SignInPage me={me.data} />

  /** A failed `/api/me` also withholds the shell, since the CSRF token arrives with it. */
  if (!me.data) return <DoorIsStuck error={me.error} onRetry={() => void me.refetch()} />

  /** An account without a security key reaches only `/api/me`, so the key page is the only one that answers. */
  if ((me.data.keys?.length ?? 0) === 0) return <SecurityKeyPage me={me.data} />

  /** A session that has not held its key here is refused everywhere else; `verified` is per session. */
  if (!me.data.verified) return <HoldKeyPage me={me.data} />

  return <SignedIn me={me.data} isMobile={isMobile} />
}

/** Everything past the four doors, apart from {@link Shell} since it reads the router and they do not. */
function SignedIn({ me, isMobile }: { me: Me; isMobile: boolean }) {
  return (
    <TooltipProvider delayDuration={300}>
      <SidebarProvider
        /** The provider writes this cookie but never reads it, so reading it keeps a collapsed sidebar collapsed. */
        defaultOpen={sidebarDefaultOpen(document.cookie)}
      >
        <AppFrame me={me} />
        {/* Bottom centre on a phone, where a corner toast covers a control; one Toaster, as `toast()` reaches all. */}
        <Toaster position={isMobile ? "bottom-center" : "bottom-right"} richColors closeButton />
        <CommandPalette />
        {/* Inside the shell, since the setup and key pages call nothing that could ask for it. */}
        <StepUp />
      </SidebarProvider>
    </TooltipProvider>
  )
}

/** `/api/me` failed with something other than a 401, and {@link Failure} names which service answered. */
function DoorIsStuck({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex min-h-(--app-height) items-center justify-center bg-background px-6 py-12">
      <div className="flex w-full max-w-md flex-col gap-6">
        <div className="flex items-center gap-3">
          <StewardMark className="size-8" />
          <div className="flex flex-col">
            <span className="text-sm font-semibold tracking-tight">Nordtal Steward</span>
            <span className="text-sm text-muted-foreground">Season 2</span>
          </div>
        </div>
        <Failure error={error} onRetry={onRetry} />
        <p className="text-center text-sm text-muted-foreground">
          This is not an expired session. You stay signed in - the interface only knows who you are again once this
          request gets through.
        </p>
      </div>
    </div>
  )
}
