import { CommandPalette } from "@/app/command-palette"
import { ChosenFrame } from "@/app/designs/chosen-frame"
import { initialNav } from "@/app/designs/sidebar-variant"
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
 * The shell: a fixed viewport, one scrolling column, and a sidebar that is a column on a desktop
 * and a sheet on a phone.
 *
 * **There is no header any more** (steward/89, Till 2026-09-17). It is gone in both states, with
 * its border, and what it carried is now an island at the top left and the account picture level
 * with it. `app/frames.tsx` holds the frame Till chose out of the nine that were built for that
 * comparison; `the-header-is-gone.test.ts` is what keeps the header from growing back.
 *
 * The document itself does not scroll. An operator watching a log window and a service table at
 * the same time should not lose their place to do it, so only the content column moves.
 *
 * **The height is measured, not asked for** (`lib/app-frame.ts`). Measured on 2026-09-14 on an
 * iPhone home screen: at `h-svh` the page and the sidebar sheet were both cut off about a fifth
 * above the bottom edge, so the viewport unit is not the window there. `--app-height` is the
 * visible viewport and falls back to `100svh` before any script has run and anywhere without a
 * `visualViewport`, which is every desktop browser this is used from.
 *
 * **The safe areas are given back here, at the edges.** `index.html` asks for `viewport-fit=cover`
 * and a translucent status bar, which means iOS draws this behind the notch and the home indicator
 * rather than letterboxing it; every edge that touches one puts the inset back with
 * `env(safe-area-inset-*)`. On anything that is not a phone those are zero and none of it applies.
 */
export function Shell() {
  const me = useMe()
  const isMobile = useIsMobile()

  // Nothing is drawn until this has answered. A shell rendered first and replaced a moment later
  // would flash a sidebar full of pages that every answer 401 - which reads as a broken interface
  // rather than as a missing session.
  if (me.isPending) return <SignInPage loading />

  // A 401 here IS the signed-out state; `useMe` is the one route that answers without a session.
  // Anything else that fails is a fault, and it must not be dressed up as one: a 500 or a 429 from
  // `/api/me` used to draw the sign-in page, where the only offered action - signing in again -
  // cannot fix it, and the actual reason was nowhere on screen.
  const signedOut = me.data ? !me.data.signedIn : me.error instanceof ApiError && me.error.isSignedOut
  if (signedOut) return <SignInPage me={me.data} />

  // The shell is not drawn on a failed `/api/me` either, and not because the pages could not report
  // it themselves: the CSRF token arrives with this answer, so without it every write in the
  // interface would be refused, one confusing page at a time.
  if (!me.data) return <DoorIsStuck error={me.error} onRetry={() => void me.refetch()} />

  // SIGNED IN AND STILL NOT IN. An account with no registered security key reaches /api/me and
  // nothing else (V20), so this is not a page being withheld - it is the only page that answers.
  // Drawing the shell here would be a sidebar of eleven links to 403s.
  //
  // `keys` is absent, not empty, when nobody is signed in - which cannot happen here, because
  // `signedOut` above already returned. An account that HAS keys always sends an array.
  if ((me.data.keys?.length ?? 0) === 0) return <SecurityKeyPage me={me.data} />

  // SIGNED IN, HAS A KEY, AND HAS NOT HELD IT HERE. Package C, and the sentence "Discord alone is
  // not enough" in one line: a session that has completed the Discord redirect and nothing else
  // reaches /api/me and is refused everywhere else, so this is again the only page that answers.
  // `verified` is per SESSION and not per account - signing out and back in lands here.
  if (!me.data.verified) return <HoldKeyPage me={me.data} />

  return <SignedIn me={me.data} isMobile={isMobile} />
}

/**
 * Everything past the four doors, in one place - and the only place the shell is chosen.
 *
 * It is a component of its own rather than the tail of {@link Shell} for a plain reason: it reads
 * the router, and the four answers above are drawn without one. `shell.test.tsx` renders `Shell`
 * with no route tree at all, which is exactly what makes those four testable.
 */
function SignedIn({ me, isMobile }: { me: Me; isMobile: boolean }) {
  return (
    <TooltipProvider delayDuration={300}>
      <SidebarProvider
        // WHAT THE SIDEBAR REMEMBERS. The provider writes this cookie whenever the sidebar is
        // opened or closed and never reads it back - reading it is the application's job, and
        // skipping that job is what makes a collapsed sidebar spring open again on every reload.
        // The phone's sheet is a different piece of state (`openMobile`) and is untouched by it,
        // which is what lets one sidebar be both things.
        //
        // A frame on the sidebar comparison page says which state it starts in (`?nav=`), and that
        // wins over the cookie for as long as the comparison exists.
        defaultOpen={initialNav() ? initialNav() === "open" : sidebarDefaultOpen(document.cookie)}
        style={{ "--sidebar-width": "13rem" } as React.CSSProperties}
      >
        <ChosenFrame me={me} />
        {/*
          Bottom right on a desktop, bottom centre on a phone - a thumb is in the middle, and a
          corner toast on a narrow screen covers whatever control is in that corner. One Toaster
          with a chosen position, not two hidden from each other: `toast()` reaches every mounted
          one, so a second would be a second notification nobody sees but the screen reader.
        */}
        <Toaster position={isMobile ? "bottom-center" : "bottom-right"} richColors closeButton />
        <CommandPalette />
        {/*
          Mounted once, here, and drawn only when something asks for it. It is inside the shell
          rather than above it because the two pages above - the setup and the key - are the two
          screens on which nothing can ask: everything they call is SIGNED_IN.
        */}
        <StepUp />
      </SidebarProvider>
    </TooltipProvider>
  )
}

/**
 * `/api/me` answered, and it was not a no.
 *
 * The whole interface hangs off this one route, so there is nothing useful to draw behind this and
 * nothing to do but say what happened and offer to ask again. {@link Failure} is the same component
 * every list uses, for the same reason: it names *which* of the three services answered badly.
 */
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
          This is not an expired session. You stay signed in - the interface only knows who you are
          again once this request gets through.
        </p>
      </div>
    </div>
  )
}
