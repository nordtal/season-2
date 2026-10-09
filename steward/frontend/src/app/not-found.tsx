import { Link } from "@tanstack/react-router"

import { PageHeader } from "@/components/steward/page-header"
import { Button } from "@/components/ui/button"
import { t } from "@/lib/texts"

/** Any address that is not a route, with the way back to the overview; Ctrl+K reaches every other page. */
export function NotFoundPage() {
  return (
    <div className="flex flex-col items-start gap-6">
      <PageHeader title={t("steward.keys.not-found")} />
      <Button asChild size="sm">
        <Link to="/">{t("steward.keys.back-to-status")}</Link>
      </Button>
    </div>
  )
}
