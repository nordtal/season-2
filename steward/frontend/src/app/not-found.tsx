import { CompassIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"

import { PageHeader } from "@/components/steward/page-header"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { t } from "@/lib/texts"

/** Any address that is not a route. It offers the two ways out rather than only naming the fault. */
export function NotFoundPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.keys.not-found")} />
      <Card className="max-w-2xl">
        <CardContent className="flex flex-col items-start gap-4">
          <div className="flex items-start gap-3">
            <CompassIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
            <p className="max-w-prose text-sm text-muted-foreground">{t("steward.keys.finds-every")}</p>
          </div>
          <Button asChild size="sm">
            <Link to="/">{t("steward.keys.back-to-status")}</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
