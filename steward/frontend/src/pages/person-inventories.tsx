import { useMemo, useState } from "react"
import { toast } from "sonner"

import type { GameIcons, InventoryStack, InventoryState, Person, PlayerInventories, StateMode } from "@/lib/api"
import { useGameData, useInventories, useRemoveStack } from "@/lib/queries"
import { choice, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { GameIcon } from "@/components/steward/game-icon"
import { QueryState } from "@/components/steward/query-state"
import { Hint, StatusBadge, type Tone } from "@/components/steward/status"
import { ServiceName } from "@/components/steward/service-name"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"

function slots(from: number, to: number): number[] {
  return Array.from({ length: to - from }, (_, index) => from + index)
}

/** Paper's slots of a player's inventory: 39 the helmet down to 36 the boots, then 40 the offhand. */
const ARMOUR = [39, 38, 37, 36, 40]
const MAIN = slots(9, 36)
const HOTBAR = slots(0, 9)
const ENDER_CHEST = slots(0, 27)

const TONE: Record<InventoryState["status"], Tone> = { HELD: "ok", AWAY: "idle", STORED: "idle" }

/** The Survival and Creative states of a person's player on each server. */
export function PersonInventories({ person }: { person: Person }) {
  const inventories = useInventories(person.discordId)
  return (
    <QueryState
      query={inventories}
      rows={4}
      empty={{ title: t("steward.people.no-inventory") }}
      isEmpty={(answer) => answer.states.length === 0}
    >
      {(answer) => <Inventories person={person} answer={answer} />}
    </QueryState>
  )
}

function Inventories({ person, answer }: { person: Person; answer: PlayerInventories }) {
  const servers = [...new Set(answer.states.map((state) => state.server))]
  const [server, setServer] = useState(
    answer.online !== undefined && servers.includes(answer.online) ? answer.online : servers[0],
  )
  const here = answer.states.filter((state) => state.server === server)
  const [mode, setMode] = useState<StateMode>((here.find((state) => state.status !== "STORED") ?? here[0]).mode)
  const state = here.find((candidate) => candidate.mode === mode) ?? here[0]
  const game = useGameData()
  const names = useMemo(
    () => new Map((game.data?.registries.item ?? []).map((entry) => [entry.id, entry.text ?? entry.id])),
    [game.data],
  )
  const [picked, setPicked] = useState<InventoryStack>()
  const remove = useRemoveStack(person.discordId)
  const nameOf = (item: string) => names.get(item) ?? item

  const grid = (label: string, shown: number[], container: InventoryStack["container"]) => (
    <SlotGrid
      label={label}
      shown={shown}
      stacks={state.stacks.filter((stack) => stack.container === container)}
      icons={game.data?.icons}
      nameOf={nameOf}
      pick={setPicked}
    />
  )

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Select
          value={server}
          onValueChange={(next) => {
            setServer(next)
            const there = answer.states.filter((candidate) => candidate.server === next)
            setMode((there.find((candidate) => candidate.status !== "STORED") ?? there[0]).mode)
          }}
        >
          <SelectTrigger aria-label={t("steward.people.inventory-server")} className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {servers.map((name) => (
              <SelectItem key={name} value={name}>
                <ServiceName service={name} />
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Tabs
          value={state.mode}
          onValueChange={(next) => {
            const tab = here.find((candidate) => candidate.mode === next)
            if (tab) setMode(tab.mode)
          }}
        >
          <TabsList>
            {here.map((candidate) => (
              <TabsTrigger key={candidate.mode} value={candidate.mode} className="px-3">
                {t("steward.people.mode", { mode: choice(candidate.mode) })}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        <StatusBadge tone={TONE[state.status]} tipContent={t("steward.people.exact-tip", { at: state.saved })}>
          {t("steward.people.inventory-status", { status: choice(state.status), server: state.server })}
        </StatusBadge>
        <span className="text-sm text-muted-foreground tnum">{t("steward.people.level", { level: state.level })}</span>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <div className="flex flex-col gap-3">
          {grid(t("steward.people.armour"), ARMOUR, "INVENTORY")}
          {grid(t("steward.people.main-inventory"), MAIN, "INVENTORY")}
          {grid(t("steward.people.hotbar"), HOTBAR, "INVENTORY")}
        </div>
        {grid(t("steward.people.ender-chest"), ENDER_CHEST, "ENDER_CHEST")}
      </div>

      <AskThenAct
        open={picked !== undefined}
        onOpenChange={(open) => {
          if (!open) setPicked(undefined)
        }}
        title={picked ? t("steward.people.remove-stack", { item: nameOf(picked.item), amount: picked.amount }) : ""}
        description={t("steward.people.remove-stack-loss", { server: state.server })}
        action={t("steward.form.remove")}
        destructive
        act={() =>
          picked &&
          remove
            .mutateAsync({
              server: state.server,
              mode: state.mode,
              container: picked.container,
              slot: picked.slot,
              item: picked.item,
              amount: picked.amount,
            })
            .then(() =>
              toast.success(t("steward.people.stack-removed", { item: nameOf(picked.item), amount: picked.amount })),
            )
        }
      />
    </div>
  )
}

/** Nine slots a row, as the game draws them. An empty slot is drawn and cannot be picked. */
function SlotGrid({
  label,
  shown,
  stacks,
  icons,
  nameOf,
  pick,
}: {
  label: string
  shown: number[]
  stacks: InventoryStack[]
  icons: GameIcons | undefined
  nameOf: (item: string) => string
  pick: (stack: InventoryStack) => void
}) {
  const bySlot = new Map(stacks.map((stack) => [stack.slot, stack]))
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="text-xs text-muted-foreground">{label}</h3>
      <div className="grid max-w-sm grid-cols-9 gap-1">
        {shown.map((slot) => {
          const stack = bySlot.get(slot)
          if (!stack) return <div key={slot} aria-hidden className="aspect-square rounded-sm bg-muted" />
          const name = t("steward.people.stack", { item: nameOf(stack.item), amount: stack.amount })
          return (
            <Hint key={slot} tip={name}>
              <button
                type="button"
                aria-label={name}
                onClick={() => pick(stack)}
                className="relative flex aspect-square items-center justify-center rounded-sm border border-border bg-secondary outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
              >
                <GameIcon icons={icons} item={stack.item} size={24} />
                {stack.amount > 1 ? (
                  <span className="absolute right-0.5 bottom-0 text-badge leading-none font-semibold tnum">
                    {stack.amount}
                  </span>
                ) : null}
              </button>
            </Hint>
          )
        })}
      </div>
    </section>
  )
}
