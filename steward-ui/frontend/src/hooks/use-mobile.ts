import * as React from "react"

const MOBILE_BREAKPOINT = 640

export function useIsMobile() {
  // Answered on the first render rather than after it: the frame draws a different shape on each
  // side of this line, and `undefined` until an effect has run was one frame of the desktop's
  // island on every phone.
  const [isMobile, setIsMobile] = React.useState(() => window.innerWidth < MOBILE_BREAKPOINT)

  React.useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
    const onChange = () => {
      setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    }
    mql.addEventListener("change", onChange)
    setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    return () => mql.removeEventListener("change", onChange)
  }, [])

  return isMobile
}
