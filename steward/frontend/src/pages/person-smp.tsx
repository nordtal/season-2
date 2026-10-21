import { useState } from "react"
import { toast } from "sonner"

import type { Person } from "@/lib/api"
import { useSetAura, useSetSpins } from "@/lib/queries"
import { t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { personName } from "@/pages/access-person"

/** Sets a person's aura on the SMP, which may go below zero. */
export function AuraDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const write = useSetAura()
  return (
    <CountDialog
      id="aura"
      label={t("steward.people.aura")}
      title={t("steward.people.aura-title")}
      hint={t("steward.people.aura-now", { aura: person.aura })}
      start={person.aura}
      negative
      pending={write.isPending}
      open={open}
      onOpenChange={onOpenChange}
      act={(aura) =>
        write.mutate(
          { discordId: person.discordId, aura },
          {
            onSuccess: () => toast.success(t("steward.people.aura-set", { name: personName(person), aura })),
            onError: (error) => toast.error(t("steward.people.aura-not-written"), { description: String(error) }),
          },
        )
      }
    />
  )
}

/** Sets the extra spins a person has left on the SMP's wheel. */
export function SpinsDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const write = useSetSpins()
  return (
    <CountDialog
      id="spins"
      label={t("steward.people.spins")}
      title={t("steward.people.spins-title")}
      hint={t("steward.people.spins-now", { spins: person.spins })}
      start={person.spins}
      pending={write.isPending}
      open={open}
      onOpenChange={onOpenChange}
      act={(spins) =>
        write.mutate(
          { discordId: person.discordId, spins },
          {
            onSuccess: () => toast.success(t("steward.people.spins-set", { name: personName(person), spins })),
            onError: (error) => toast.error(t("steward.people.spins-not-written"), { description: String(error) }),
          },
        )
      }
    />
  )
}

/** Asks for one whole number. Steward refuses one out of range and says the range. */
function CountDialog({
  id,
  label,
  title,
  hint,
  start,
  negative = false,
  pending,
  open,
  onOpenChange,
  act,
}: {
  id: string
  label: string
  title: string
  hint: string
  start: number
  negative?: boolean
  pending: boolean
  open?: boolean
  onOpenChange?: (open: boolean) => void
  act: (value: number) => void
}) {
  const [value, setValue] = useState(String(start))
  const count = Number(value)
  const usable = value.trim() !== "" && Number.isInteger(count) && (negative || count >= 0)
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      action={t("steward.form.save")}
      disabled={!usable || pending}
      act={() => act(count)}
    >
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-count`}>{label}</Label>
        <Input
          id={`${id}-count`}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          type="number"
          min={negative ? undefined : 0}
          step={1}
          // A phone's number pad has no minus sign.
          inputMode={negative ? "text" : "numeric"}
        />
        <p className="text-xs text-muted-foreground">{hint}</p>
      </div>
    </AskThenAct>
  )
}
