package eu.nordtal.s2.build

import java.util.concurrent.TimeUnit

/** Whether this machine can run the deploy scripts' test suites, which need bash 4 or later. */
object Bash {
    /**
     * @return true when `bash` on the PATH is version 4 or later; always false on Windows, whose
     *         `bash.exe` is WSL's and cannot see the checkout's paths the way the scripts expect
     */
    fun atLeast4(): Boolean {
        if (System.getProperty("os.name").lowercase().contains("windows")) return false
        return try {
            val process =
                ProcessBuilder("bash", "-c", "(( BASH_VERSINFO[0] >= 4 ))")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: java.io.IOException) {
            false
        }
    }
}
