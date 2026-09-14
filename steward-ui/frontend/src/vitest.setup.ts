import { configure } from "@testing-library/react"

/**
 * How long an async query may wait before it counts as a failure.
 *
 * Testing Library's own default is one second, and one second is not a statement about this code -
 * it is a statement about how busy the machine is. These tests run inside `sh gradlew build`,
 * alongside four Java test JVMs on the same host, and that is exactly where the budget ran out:
 * `status.test.tsx` went red with `expected '' to contain 'older image'` in a full build and
 * green on its own seconds later, twice. A test that measures load rather than behaviour is worse
 * than no test, because the next person to see it red will re-run it until it is green.
 *
 * Five seconds is far more than any of these renders needs - the whole file takes about four
 * seconds including jsdom's startup - and still short enough that a genuinely stuck query fails
 * the run rather than hanging it.
 */
configure({ asyncUtilTimeout: 5_000 })

/**
 * `window.matchMedia`, which jsdom does not have.
 *
 * The shell asks whether it is on a phone - the sidebar is a column or a sheet, the toaster is in
 * the corner or under the thumb - and `useIsMobile` answers with a media query. jsdom has no layout
 * and therefore no `matchMedia`, so without this every test that renders the shell dies on a
 * `TypeError` that has nothing to do with what it was testing.
 *
 * It always answers **no**: jsdom's window is 1024 wide, which is a desktop, and that is the branch
 * these tests are about. A test that wants the phone can stub this itself.
 */
if (typeof window !== "undefined" && !window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList
}
