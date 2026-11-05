import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { api, type Players, type Router, type SeasonChange, type SeasonMode, type Switch } from "@/lib/api"
import { live, RECONCILE } from "@/lib/live"
import { keys, SECOND } from "@/lib/query-keys"

/** The launch date, maintenance and the game mode, with the last switch's run, which moves with the runs. */
export function useSeasonRouter(enabled = true) {
  return useQuery({
    queryKey: keys.season,
    queryFn: () => api<Router>("/api/season"),
    ...live("SEASON", "RUNS"),
    refetchInterval: (query) => nextRead(query.state.data, Date.now()),
    enabled,
  })
}

/**
 * Returns how long the Router waits before it reads again: a second past a launch ahead, since a launch that passes
 * writes no row that would announce it, and a minute otherwise.
 */
export function nextRead(router: Router | undefined, now: number): number {
  if (router?.opening !== "COUNTDOWN" || !router.launch) return RECONCILE
  return Math.min(RECONCILE, Math.max(SECOND, new Date(router.launch).getTime() - now + SECOND))
}

/** The players a change moves. Each read asks for every player's access, so only an open dialog asks. */
export function useSeasonPlayers(enabled: boolean) {
  return useQuery({
    queryKey: [...keys.season, "players"],
    queryFn: () => api<Players>("/api/season/players"),
    ...live("SERVICES"),
    enabled,
  })
}

type Reason = { reason?: string }

function useSeasonChange<Body>(path: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: Body & Reason) => api<SeasonChange>(path, { method: "POST", body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.season })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}

/** Sets the launch date. A date ahead of a launched season closes it until then. */
export function useSetLaunch() {
  return useSeasonChange<{ at: string }>("/api/season/launch")
}

export function useLaunchNow() {
  return useSeasonChange<object>("/api/season/launch-now")
}

export function useCloseSeason() {
  return useSeasonChange<object>("/api/season/close")
}

export function useSetMaintenance() {
  return useSeasonChange<{ on: boolean }>("/api/season/maintenance")
}

/** Asks for a mode switch run, which the runs list shows too. */
export function useSwitchMode() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { target: SeasonMode } & Reason) => api<Switch>("/api/season/switch", { method: "POST", body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.season })
      void client.invalidateQueries({ queryKey: ["runs"] })
      void client.invalidateQueries({ queryKey: ["journal"] })
    },
  })
}
