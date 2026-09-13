import type { ReactNode } from "react"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"

/**
 * Every page in this alpha is one of these.
 *
 * It says what will live here and nothing more - there is no fake data anywhere in this scaffold,
 * on purpose: a placeholder that renders invented numbers is a screenshot somebody eventually
 * believes. The backend lands next, and each of these is replaced by the real thing.
 */
export function Placeholder({
  title,
  note,
  detail,
  children,
}: {
  title: string
  note: string
  detail?: string
  children?: ReactNode
}) {
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-1.5">
        <h1 className="text-2xl font-semibold tracking-tight text-balance">{title}</h1>
        <p className="max-w-prose text-sm text-muted-foreground">{note}</p>
      </header>

      <Card className="max-w-3xl">
        <CardHeader>
          <CardTitle className="text-sm font-medium">Noch nicht angebunden</CardTitle>
          <CardDescription>
            {detail ??
              "Die Oberfläche steht, die Daten kommen aus der API, sobald sie da ist."}
          </CardDescription>
        </CardHeader>
        {children ? <CardContent>{children}</CardContent> : null}
      </Card>
    </div>
  )
}
