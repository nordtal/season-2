import * as React from "react"

const MOBILE_BREAKPOINT = 640

export function useIsMobile() {
  /**
   * Answers on the first render, because `undefined` until an effect ran drew the desktop island for one frame.
   */
  const [isMobile, setIsMobile] = React.useState(() => window.innerWidth < MOBILE_BREAKPOINT)

  React.useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
    const onChange = () => {
      setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    }
    mql.addEventListener("change", onChange)
    return () => mql.removeEventListener("change", onChange)
  }, [])

  return isMobile
}
