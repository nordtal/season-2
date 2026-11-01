import { createContext, useContext } from "react"

/** Returns the worded breach a control's id shows, or null; a control outside a form shows none. */
export type BreachAt = (id: string) => string | null

const NONE: BreachAt = () => null

export const BreachContext = createContext<BreachAt>(NONE)

/** The broken rule the control with `id` shows, in words, or null. */
export function useBreach(id: string): string | null {
  return useContext(BreachContext)(id)
}

/** The lookup itself, for a control that shows the breaches of several entries. */
export function useBreaches(): BreachAt {
  return useContext(BreachContext)
}
