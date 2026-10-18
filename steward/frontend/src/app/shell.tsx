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
import { useLiveStream } from "@/lib/live"
import { useMe } from "@/lib/queries"
import { useIsMobile } from "@/hooks/use-mobile"
import { Failure } from "@/components/steward/query-state"
import { SidebarProvider } from "@/components/ui/sidebar"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"
import { t } from "@/lib/texts"

export { breadcrumbsFor } from "@/app/breadcrumbs"

/** Its height is `--app-height`, which `lib/app-frame.ts` measures. */
export function Shell() {
  const me = useMe()
  const isMobile = useIsMobile()

  /** Draws nothing until `/api/me` answers, so no sidebar of pages that answer 401 flashes up. */
  if (me.isPending) return <SignInPage loading />

  const signedOut = me.data ? !me.data.signedIn : me.error instanceof ApiError && me.error.isSignedOut
  if (signedOut) return <SignInPage me={me.data} />

  /**
   * A failed `/api/me` also withholds the shell, because the CSRF token arrives with that answer.
   */
  if (!me.data) return <DoorIsStuck error={me.error} onRetry={() => void me.refetch()} />

  /**
   * An account without a second factor can reach only `/api/me`, so only the setup page can load.
   */
  if ((me.data.keys?.length ?? 0) === 0 && !me.data.authenticator) return <SecurityKeyPage me={me.data} />

  /** Every other route refuses a session that has used no factor. `verified` is per session. */
  if (!me.data.verified) return <HoldKeyPage me={me.data} />

  return <SignedIn me={me.data} isMobile={isMobile} />
}

function SignedIn({ me, isMobile }: { me: Me; isMobile: boolean }) {
  /**
   * This runs after the key check, because `/api/live` answers only a session that holds a key.
   */
  useLiveStream(true)
  return (
    <TooltipProvider delayDuration={300}>
      <SidebarProvider
        /**
         * The provider writes this cookie and never reads it, so this call reads it to keep a collapsed sidebar collapsed.
         */
        defaultOpen={sidebarDefaultOpen(document.cookie)}
      >
        <AppFrame me={me} />
        {/* On a phone the toasts are at the bottom centre, where a corner toast would cover a control. One Toaster serves every `toast()` call. */}
        <Toaster position={isMobile ? "bottom-center" : "bottom-right"} richColors closeButton />
        <CommandPalette />
        {/* It is inside the shell, because the setup and key pages call nothing that could ask for a step-up. */}
        <StepUp />
      </SidebarProvider>
    </TooltipProvider>
  )
}

function DoorIsStuck({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex min-h-(--app-height) items-center justify-center bg-background px-6 py-12">
      <div className="flex w-full max-w-md flex-col gap-6">
        <div className="flex items-center gap-3">
          <StewardMark className="size-8" />
          <div className="flex flex-col">
            <span className="text-sm font-semibold tracking-tight">{t("steward.keys.brand")}</span>
            <span className="text-sm text-muted-foreground">{t("steward.keys.season")}</span>
          </div>
        </div>
        <Failure error={error} onRetry={onRetry} />
        <p className="text-center text-sm text-muted-foreground">{t("steward.shell.stuck")}</p>
      </div>
    </div>
  )
}
