/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The release this build is. Absent from the dev server and the tests. */
  readonly VITE_VERSION?: string
}
