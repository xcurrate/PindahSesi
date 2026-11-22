package com.example.cgptswitcher

import java.util.concurrent.TimeUnit

/**
 * Wrapper tipis di atas `su -c <script>`.
 * Semua operasi privileged app ini lewat sini.
 */
object RootShell {

    data class Result(
        val ok: Boolean,
        val output: String,
        val exitCode: Int
    )

    fun run(script: String, timeoutSec: Long = 60): Result {
        return try {
            val p = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            reader.readLines().forEach { sb.appendLine(it) }
            val code = if (done) p.exitValue() else { p.destroyForcibly(); -1 }
            Result(done && code == 0, sb.toString().trim(), code)
        } catch (e: Exception) {
            Result(false, e.message ?: "exec failed", -1)
        }
    }
}
