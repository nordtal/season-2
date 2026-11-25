import {
  ArrowLeftIcon,
  ClockCounterClockwiseIcon,
  LayoutIcon,
  PlusIcon,
  SpinnerIcon,
  XIcon,
} from "@/components/ui/icons"
import { useQueryClient } from "@tanstack/react-query"
import { Link, useNavigate } from "@tanstack/react-router"
import { type ReactNode, useMemo, useRef, useState } from "react"
import { toast } from "sonner"

import {
  api,
  ApiError,
  errorMessage,
  type CommandAsked,
  type Npc,
  type NpcTextMeasureRequest,
  type NpcView,
} from "@/lib/api"
import {
  type Language,
  type Problem,
  LANGUAGES,
  addStep,
  arranged,
  missingIn,
  moveStep,
  overlapping,
  problemsOf,
  sameNpc,
  stepOf,
} from "@/lib/npc"
import { readNpcDraft, setNpcDraft, useNpcDraft } from "@/lib/npc-drafts"
import { NPCS_PATH } from "@/lib/npc-paths"
import { finalRun } from "@/lib/queries-commands"
import { keys } from "@/lib/query-keys"
import { measureKey, useNpc, useNpcMeasurements, useNpcMenus, usePublishNpc, useRemoveNpc } from "@/lib/queries-npcs"
import { message, t } from "@/lib/texts"
import { useDebounced } from "@/lib/use-debounced"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { StepCanvas, StepList, type TooLong } from "@/components/steward/npc/graph"
import { GermanProvider, germanKey } from "@/components/steward/npc/german"
import { DialoguePreview, requestsOf } from "@/components/steward/npc/preview"
import { NpcSettings } from "@/components/steward/npc/settings"
import { Heading, StepPanel } from "@/components/steward/npc/step-panel"
import { NpcVersions } from "@/components/steward/npc/versions"
import { QueryState, Skeleton } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

/**
 * The editor of one NPC. Every change goes into a draft the browser holds; Publish sends the whole draft against the
 * version it was read at, and a draft that equals what is published is no draft.
 */

type Tab = "dialogue" | "settings"

/** How long typing rests before the texts are measured again. */
const MEASURE_AFTER_MS = 250

/** Every text of the NPC as the panel measures it: the names, and each step's text and answers, in both languages. */
function everyText(npc: Npc): NpcTextMeasureRequest[] {
  return LANGUAGES.flatMap((language) => [
    { text: npc.name[language], kind: "NAME" as const, answers: 0 },
    ...npc.dialogue.steps.flatMap((step) => requestsOf(npc, step, language).slice(1)),
  ]).filter((request) => request.text !== "")
}

function isTooLong(value: unknown): value is [string, Language[]][] {
  return Array.isArray(value)
}

/** The languages in which each step is too long, from the string the editor keeps them as. */
function toTooLong(marks: string): TooLong {
  const parsed: unknown = JSON.parse(marks)
  return new Map(isTooLong(parsed) ? parsed : [])
}

/** The German sections that hold the text a problem lacks: the name, a step's page, or each of its answers without one. */
function germanOf(npc: Npc, problem: Problem): string[] {
  if (problem.problem === "name") return [germanKey.name]
  if (problem.problem === "step-text" || problem.problem === "step-tall") {
    return [germanKey.step(problem.step, problem.page)]
  }
  if (problem.problem !== "answer-text") return []
  const answers = stepOf(npc.dialogue, problem.step)?.answers ?? []
  return answers.flatMap((answer, index) =>
    answer.text.de.trim() === "" ? [germanKey.answer(problem.step, index)] : [],
  )
}

/** The NPC's name as the page calls it: the English one, as Steward is English. */
export function npcTitle(npc: Npc): string {
  return npc.name.en.trim() || npc.name.de.trim() || t("steward.npcs.unnamed")
}

export function NpcEditor({ id }: { id: string }) {
  const query = useNpc(id)
  const draft = useNpcDraft(id)
  // An NPC that was never published answers 404 and lives in its draft alone, so a draft shows whatever the read says.
  const shown = draft ? { published: query.data } : query.data ? { published: query.data } : undefined
  return (
    <QueryState
      query={{
        data: shown,
        error: draft ? null : query.error,
        isPending: shown === undefined && query.isPending,
        fetchStatus: query.fetchStatus,
        refetch: () => void query.refetch(),
      }}
    >
      {(open) => {
        const npc = draft?.npc ?? open?.published?.npc
        return open === undefined || npc === undefined ? (
          <Waiting />
        ) : (
          <Editor
            key={id}
            npc={npc}
            base={draft?.base ?? open.published?.version ?? 0}
            published={open.published}
            dirty={draft !== undefined}
          />
        )
      }}
    </QueryState>
  )
}

/** The editor's head inside the NPCs tab: the way back to the list, the NPC's name and its actions. */
function EditorHead({ title, actions }: { title: string; actions?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-center justify-between gap-4">
      <div className="-ml-2 flex min-w-0 items-center gap-1">
        <Button asChild variant="ghost" size="icon-sm">
          <Link {...NPCS_PATH} aria-label={t("steward.npcs.back")}>
            <ArrowLeftIcon aria-hidden />
          </Link>
        </Button>
        <h2 className="truncate text-xl font-semibold font-heading tracking-tight">{title}</h2>
      </div>
      {actions ? <div className="flex max-w-full flex-wrap items-center justify-end gap-2">{actions}</div> : null}
    </header>
  )
}

/** The editor before its NPC is read: the title, the tabs, then the canvas beside the step's fields. */
function Waiting() {
  return (
    <div className="flex flex-col gap-6" role="status" aria-busy="true">
      <span className="sr-only">{t("steward.failure.loading")}</span>
      <Skeleton className="h-8 w-56" />
      <Skeleton className="h-8 w-40" />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Skeleton className="h-list xl:col-span-2" />
        <div className="flex flex-col gap-3 max-xl:hidden">
          <Skeleton className="h-24" />
          <Skeleton className="h-24" />
          <Skeleton className="h-48" />
        </div>
      </div>
    </div>
  )
}

function Editor({
  npc,
  base,
  published,
  dirty,
}: {
  npc: Npc
  /** The version the draft was read at, 0 for an NPC that was never published. */
  base: number
  published: NpcView | undefined
  dirty: boolean
}) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const menus = useNpcMenus()
  const publish = usePublishNpc()
  const remove = useRemoveNpc()
  const [tab, setTab] = useState<Tab>(published === undefined ? "settings" : "dialogue")
  const [picked, setPicked] = useState(npc.dialogue.start)
  const [previewPage, setPreviewPage] = useState(0)
  const [language, setLanguage] = useState<Language>("en")
  const [german, setGerman] = useState<ReadonlySet<string>>(() => new Set())
  const toggleGerman = (key: string, open: boolean) =>
    setGerman((before) => {
      const after = new Set(before)
      if (open) after.add(key)
      else after.delete(key)
      return after
    })
  const [versions, setVersions] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [playing, setPlaying] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const play = useRef<string | null>(null)
  const sending = useRef(false)
  const panel = useRef<HTMLDivElement>(null)

  const step = stepOf(npc.dialogue, picked) ?? stepOf(npc.dialogue, npc.dialogue.start) ?? npc.dialogue.steps[0]
  const title = npcTitle(npc)

  /** Changes the draft as it is now, so a change that follows an answer, such as an uploaded skin, keeps what was typed meanwhile. */
  const edit = (change: (npc: Npc) => Npc) => {
    const held = readNpcDraft(npc.id)
    const live = client.getQueryData<NpcView>(keys.npc(npc.id))
    // A late answer for an NPC that was deleted meanwhile starts no new draft.
    if (held === undefined && live === undefined && published !== undefined) return
    const from = held ?? (live ? { npc: live.npc, base: live.version } : { npc, base })
    const next = change(from.npc)
    // While a publication is on its way the draft stays, even one that equals what was live: the answer decides.
    const untouched = !sending.current && live !== undefined && from.base === live.version && sameNpc(next, live.npc)
    setNpcDraft(npc.id, untouched ? undefined : { npc: next, base: from.base })
  }

  const texts = useDebounced(
    useMemo(() => everyText(npc), [npc]),
    MEASURE_AFTER_MS,
  )
  const measured = useNpcMeasurements(texts).data
  const missing = useMemo(() => new Map(npc.dialogue.steps.map((each) => [each.id, missingIn(each)])), [npc])
  // While a typed text is not measured yet the marks stay as they were, so they do not go out at every keystroke.
  const fresh = useMemo(() => {
    const long: [string, Language[]][] = []
    for (const each of npc.dialogue.steps) {
      const languages: Language[] = []
      for (const code of LANGUAGES) {
        for (const request of requestsOf(npc, each, code).slice(1)) {
          if (request.text === "") continue
          const measurement = measured?.get(measureKey(request))
          if (measurement === undefined) return undefined
          if (!measurement.fits && !languages.includes(code)) languages.push(code)
        }
      }
      long.push([each.id, languages])
    }
    return JSON.stringify(long)
  }, [npc, measured])
  const [marks, setMarks] = useState("[]")
  if (fresh !== undefined && fresh !== marks) setMarks(fresh)
  const tooLong = useMemo(() => toTooLong(marks), [marks])

  /** Says what keeps the draft from playing, and opens the place where it is put right. */
  const refuse = (heading: string, problem: Problem) => {
    toast.error(heading, {
      description: t("steward.npcs.problem", {
        problem: problem.problem,
        language: "language" in problem ? problem.language : "en",
      }),
    })
    if ("step" in problem) {
      setTab("dialogue")
      setPicked(problem.step)
      if ("page" in problem) setPreviewPage(problem.page)
    } else {
      setTab("settings")
    }
    if ("language" in problem && problem.language === "de") {
      for (const key of germanOf(npc, problem)) toggleGerman(key, true)
    }
  }

  const send = (version: number) => {
    const [problem] = problemsOf(npc, measured)
    if (problem) {
      refuse(t("steward.npcs.not-published-yet"), problem)
      return
    }
    // The promise takes the answer, so the draft follows the publication even after the admin left the page.
    sending.current = true
    publish.mutateAsync({ npc, version }).then(
      (view) => {
        sending.current = false
        // What was typed while the publication was on its way stays a draft, now of the new version.
        const held = readNpcDraft(npc.id)
        setNpcDraft(npc.id, held && !sameNpc(held.npc, npc) ? { npc: held.npc, base: view.version } : undefined)
        setConflict(false)
        toast.success(t("steward.npcs.published", { name: npcTitle(view.npc), version: view.version }))
      },
      (error: unknown) => {
        sending.current = false
        if (error instanceof ApiError && error.code === "NPC_CHANGED") {
          // The dialog names the version that is live now.
          void client.invalidateQueries({ queryKey: keys.npcs }).then(() => {
            // An NPC that was deleted meanwhile has no version to load or to publish over.
            const read = client.getQueryState(keys.npc(npc.id))?.error
            if (read instanceof ApiError && read.status === 404) toast.error(t("steward.answer.no-npc"))
            else setConflict(true)
          })
        } else {
          toast.error(t("steward.npcs.not-published-yet"), { description: errorMessage(error) })
        }
      },
    )
  }

  const ended = (id: string) => {
    if (play.current !== id) return false
    play.current = null
    setPlaying(null)
    return true
  }
  const startPlaying = async () => {
    const [problem] = problemsOf(npc, measured)
    if (problem) {
      refuse(t("steward.npcs.not-played"), problem)
      return
    }
    setStarting(true)
    try {
      const asked = await api<CommandAsked>("/api/npc-preview", { method: "POST", body: { npc, language } })
      setStarting(false)
      play.current = asked.id
      setPlaying(asked.id)
      const run = await finalRun(client, asked.id)
      if (!ended(asked.id) || run.status === "DONE" || run.status === "CANCELLED") return
      if (run.status === "REFUSED" || run.status === "EXPIRED") toast.error(t("steward.answer.take-in-offline"))
      else toast.error(t("steward.npcs.not-played"), { description: run.result ? message(run.result) : undefined })
    } catch (error) {
      setStarting(false)
      play.current = null
      setPlaying(null)
      toast.error(t("steward.npcs.not-played"), { description: errorMessage(error) })
    }
  }
  const cancelPlaying = () => {
    const id = playing
    if (id === null || !ended(id)) return
    api<void>(`/api/commands/${id}`, { method: "DELETE" }).catch((error: unknown) =>
      toast.error(t("steward.npcs.not-played"), { description: errorMessage(error) }),
    )
  }

  /** Picks a step. Below the canvas's width the step's editor is under the list, so the page moves to it. */
  const pick = (id: string, fromList: boolean) => {
    setPicked(id)
    setPreviewPage(0)
    if (fromList) requestAnimationFrame(() => panel.current?.scrollIntoView({ behavior: "smooth", block: "start" }))
  }

  const graph = { dialogue: npc.dialogue, menus: menus.data ?? [], selected: step.id, tooLong, missing }
  const live = published?.version ?? 0
  return (
    <GermanProvider value={{ open: german, toggle: toggleGerman }}>
      <div className="flex flex-col gap-6">
        <EditorHead
          title={title}
          actions={
            <>
              <Badge variant="secondary">
                {live === 0 ? t("steward.npcs.not-published") : t("steward.npcs.live", { version: live })}
              </Badge>
              {dirty && live > 0 ? <StatusBadge tone="warn">{t("steward.npcs.changed")}</StatusBadge> : null}
              {dirty ? (
                <AskThenAct
                  trigger={
                    <Button type="button" variant="ghost">
                      {t("steward.npcs.discard")}
                    </Button>
                  }
                  title={t("steward.npcs.discard-ask", { name: title })}
                  description={live > 0 ? t("steward.npcs.discard-note", { version: base }) : undefined}
                  action={t("steward.npcs.discard")}
                  destructive
                  act={() => {
                    setNpcDraft(npc.id, undefined)
                    if (live === 0) void navigate({ ...NPCS_PATH, replace: true })
                  }}
                />
              ) : null}
              {live > 0 ? (
                <Button type="button" variant="ghost" onClick={() => setVersions(true)}>
                  <ClockCounterClockwiseIcon aria-hidden />
                  {t("steward.npcs.versions")}
                </Button>
              ) : null}
              {playing === null ? (
                <Button type="button" variant="secondary" disabled={starting} onClick={() => void startPlaying()}>
                  <TERM_ICON.start aria-hidden />
                  {t("steward.npcs.play")}
                </Button>
              ) : (
                <Button type="button" variant="destructive" onClick={cancelPlaying}>
                  <XIcon aria-hidden />
                  {t("steward.npcs.cancel-play")}
                </Button>
              )}
              <Button type="button" disabled={!dirty || publish.isPending} onClick={() => send(base)}>
                {publish.isPending ? <SpinnerIcon aria-hidden className="animate-spin" /> : null}
                {publish.isPending ? t("steward.npcs.publishing") : t("steward.npcs.publish")}
              </Button>
            </>
          }
        />
        <Tabs value={tab} onValueChange={(value) => setTab(value === "settings" ? "settings" : "dialogue")}>
          <TabsList>
            <TabsTrigger value="dialogue">{t("steward.npcs.tab", { tab: "dialogue" })}</TabsTrigger>
            <TabsTrigger value="settings">{t("steward.npcs.tab", { tab: "settings" })}</TabsTrigger>
          </TabsList>
          <TabsContent value="dialogue" className="mt-4 grid grid-cols-1 gap-6 xl:grid-cols-3">
            <div className="flex min-w-0 flex-col gap-3 xl:col-span-2">
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    const added = addStep(npc, step.id)
                    edit(() => added.npc)
                    setPicked(added.id)
                    setPreviewPage(0)
                  }}
                >
                  <PlusIcon aria-hidden />
                  {t("steward.npcs.add-step")}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="max-xl:hidden"
                  onClick={() => edit((draft) => arranged(draft))}
                >
                  <LayoutIcon aria-hidden />
                  {t("steward.npcs.arrange")}
                </Button>
              </div>
              <StepCanvas
                {...graph}
                dialogue={overlapping(npc.dialogue) ? arranged(npc).dialogue : npc.dialogue}
                className="flex-1 max-xl:hidden"
                onSelect={(id) => pick(id, false)}
                onMove={(id, x, y) =>
                  edit((draft) => moveStep(overlapping(draft.dialogue) ? arranged(draft) : draft, id, x, y))
                }
              />
              <StepList {...graph} className="xl:hidden" onSelect={(id) => pick(id, true)} />
            </div>
            <div ref={panel} className="flex min-w-0 scroll-mt-4 flex-col gap-6">
              <StepPanel
                npc={npc}
                step={step}
                menus={menus.data ?? []}
                measured={measured}
                edit={edit}
                onSelectPage={setPreviewPage}
                onDeleted={() => {
                  setPicked("")
                  setPreviewPage(0)
                }}
              />
              <section className="flex flex-col gap-3">
                <Heading
                  actions={
                    <>
                      {step.pages.length > 1 ? (
                        <Tabs
                          value={String(Math.min(previewPage, step.pages.length - 1))}
                          onValueChange={(value) => setPreviewPage(Number(value))}
                        >
                          <TabsList>
                            {step.pages.map((_, index) => (
                              <TabsTrigger key={index} value={String(index)} className="px-2 text-xs tnum">
                                {index + 1}
                              </TabsTrigger>
                            ))}
                          </TabsList>
                        </Tabs>
                      ) : null}
                      <Tabs value={language} onValueChange={(value) => setLanguage(value === "de" ? "de" : "en")}>
                        <TabsList>
                          {LANGUAGES.map((each) => (
                            <TabsTrigger key={each} value={each} className="px-2.5 text-xs">
                              {t("steward.npcs.language", { language: each })}
                            </TabsTrigger>
                          ))}
                        </TabsList>
                      </Tabs>
                    </>
                  }
                >
                  {t("steward.npcs.preview")}
                </Heading>
                <DialoguePreview
                  npc={npc}
                  step={step}
                  pageIndex={previewPage}
                  language={language}
                  measured={measured}
                />
              </section>
            </div>
          </TabsContent>
          <TabsContent value="settings" className="mt-4">
            <NpcSettings
              npc={npc}
              published={published?.npc}
              measured={measured}
              edit={edit}
              onDelete={() =>
                remove.mutateAsync(npc.id).then(() => {
                  setNpcDraft(npc.id, undefined)
                  toast.success(t("steward.npcs.deleted", { name: title }))
                  return navigate({ ...NPCS_PATH, replace: true })
                })
              }
            />
          </TabsContent>
        </Tabs>
        {live > 0 ? (
          <NpcVersions
            id={npc.id}
            name={title}
            open={versions}
            onOpenChange={setVersions}
            onLoad={(version) => {
              const restored = version.npc ?? (version.dialogue ? { ...npc, dialogue: version.dialogue } : undefined)
              if (restored === undefined) return
              edit(() => restored)
              setPicked(restored.dialogue.start)
              setPreviewPage(0)
              setVersions(false)
              toast.success(t("steward.npcs.loaded", { number: version.number }))
            }}
          />
        ) : null}
        <AskThenAct
          open={conflict}
          onOpenChange={setConflict}
          title={t("steward.npcs.conflict", { name: title })}
          description={t("steward.npcs.conflict-note", { live, read: base })}
          action={t("steward.npcs.load-live", { live })}
          act={() => setNpcDraft(npc.id, undefined)}
        >
          <Button
            type="button"
            variant="outline"
            disabled={publish.isPending || live === base}
            onClick={() => send(live)}
          >
            {t("steward.npcs.overwrite", { live })}
          </Button>
        </AskThenAct>
      </div>
    </GermanProvider>
  )
}
