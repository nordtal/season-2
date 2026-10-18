import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider } from "@tanstack/react-router"

import { trackAppFrame } from "@/lib/app-frame"
import { registerServiceWorker } from "@/lib/push"
import { loadTexts } from "@/lib/texts"
import { router } from "@/router"
import "@/index.css"

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      /** A dashboard that is read constantly must not refetch on every focus change. */
      staleTime: 5_000,
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
})

/** A throw here would be a black screen, so it is guarded. */
try {
  trackAppFrame()
} catch (error) {
  console.error("trackAppFrame failed to start; the layout will use its CSS fallback instead", error)
}

/** A service worker needs no permission, so it is registered at once. */
registerServiceWorker().catch((error: unknown) => {
  console.error("the service worker did not register; web push will not be available", error)
})

const container = document.getElementById("root")
if (!container) throw new Error("#root is missing from index.html")

/** So an override never flashes the packaged text first. */
void loadTexts().finally(() => {
  createRoot(container).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  )
})
