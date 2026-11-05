import type { Router, Service } from "@/lib/api"
import { cn } from "@/lib/utils"
import { choice, t } from "@/lib/texts"
import { ServiceIcon } from "@/components/steward/service-name"
import { TERM_ICON } from "@/components/steward/term-icons"
import { LIMBO, OTHER, gateOf, modeName, serverOf, whereOf } from "@/components/steward/router/router-state"

const STATE_COLOUR = { ok: "text-success", warn: "text-warning", down: "text-destructive", idle: "text-foreground" }

/** The status line above the cards: where members land now, whether the gate lets them through, and who is online where. */
export function GateBar({ router, services, now }: { router: Router; services: Service[]; now: number }) {
  const gate = gateOf(router, now)
  const where = whereOf(router, services)
  const word = t("steward.router.now", { now: choice(gate.now) })
  const held = gate.now === "waiting"

  return (
    <section
      aria-label={t("steward.router.tab")}
      className="flex flex-col gap-1 border-b pb-4 md:flex-row md:items-center md:gap-6"
    >
      <p
        className={cn(
          "flex items-center gap-2.5 text-2xl/8 font-semibold tracking-tight",
          held && gate.tone === "warn" && "text-warning",
          held && gate.tone === "down" && "text-destructive",
        )}
      >
        <NowIcon now={gate.now} className={cn("size-6 shrink-0", !held && "text-muted-foreground")} />
        {word}
      </p>
      <div className="flex min-w-0 flex-col gap-1">
        <p className="flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5 text-sm font-medium">
          {gate.state ? (
            <span className={STATE_COLOUR[gate.tone]}>{t("steward.router.gate", { gate: gate.state })}</span>
          ) : null}
          {gate.why ? <span className="text-muted-foreground">{gate.why}</span> : null}
          {gate.except.map((line) => (
            <span key={line} className="text-muted-foreground">
              {line}
            </span>
          ))}
        </p>
        <p className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
          <span className="font-heading">{t("steward.router.online")}</span>
          {where.length === 0 ? <span>{t("steward.router.nobody")}</span> : null}
          {where.map((row) => (
            <span key={row.service}>
              <span className="tnum text-foreground">{row.players}</span>{" "}
              {row.service === LIMBO
                ? t("steward.router.in-waiting-room")
                : t("steward.router.on-server", { server: serverName(router, row.service) })}
            </span>
          ))}
        </p>
      </div>
    </section>
  )
}

function serverName(router: Router, service: string): string {
  return modeName(service === serverOf(router.mode) ? router.mode : OTHER[router.mode])
}

function NowIcon({ now, className }: { now: string; className: string }) {
  if (now === "closed") return <TERM_ICON.closed aria-hidden className={className} />
  if (now === "countdown") return <TERM_ICON.countdown aria-hidden className={className} />
  if (now === "waiting") return <ServiceIcon service={LIMBO} className={className} />
  return <ServiceIcon service={choice(now)} className={className} />
}
