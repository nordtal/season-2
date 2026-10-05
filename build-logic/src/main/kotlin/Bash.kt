package eu.nordtal.season.build

import java.util.concurrent.TimeUnit

/** Whether this machine can run the deploy scripts' test suites, which need bash 4 or later. */
object Bash {
    /**
     * @return true when `bash` on the PATH is version 4 or later; always false on Windows, where it is WSL's
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
