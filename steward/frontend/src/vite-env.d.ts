/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * The release this build is, from Gradle's `version`. It is absent from the dev server and the tests.
   */
  readonly VITE_VERSION?: string
}
