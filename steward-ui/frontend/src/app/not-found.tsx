import { Link } from "@tanstack/react-router"
import { Compass } from "lucide-react"

import { PageHeader } from "@/components/steward/page-header"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"

/** Any address that is not a route. It offers the two ways out rather than only naming the fault. */
export function NotFoundPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Page not found"
      />
      <Card className="max-w-2xl">
        <CardContent className="flex flex-col items-start gap-4">
          <div className="flex items-start gap-3">
            <Compass className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
            <p className="max-w-prose text-sm text-muted-foreground">
              <kbd className="rounded-sm border border-border bg-secondary px-1.5 py-0.5 font-mono text-xs">Ctrl+K</kbd>{" "}
              finds every page this interface has.
            </p>
          </div>
          <Button asChild size="sm">
            <Link to="/">Back to Status</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
