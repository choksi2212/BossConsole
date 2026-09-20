package ai.rever.boss.utils

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the bug where `SingleInstanceManager.handleClient` spawned
 * one daemon thread per accepted connection without bounding in-flight handlers,
 * so a local flood could park an unbounded number of short-lived daemons
 * (issue #1326). The fix gates `handleClient` behind a semaphore that drops
 * the connection when the handler pool is full.
 *
 * The test: open the running host's TCP channel, hold the connection without
 * sending a request (so the handler stays parked on the read), and count how
 * many handlers we can keep parked. Once the bound is reached, a further
 * connection must be rejected without spawning a thread.
 */
class SingleInstanceFloodCapTest {
    private val port = findFreePort()

    @AfterTest
    fun cleanup() {
        SingleInstanceManager.release()
    }

    @Test
    fun `handleClient is bounded so a local flood cannot spawn unbounded threads`() {
        val open = SingleInstanceManager.acquireLock()
        assertTrue(open, "SingleInstanceManager failed to bind (test environment)")

        val parkedThreadsBefore = parkedThreadCount()
        // Connect (MAX_CLIENT_HANDLERS_FOR_TEST + attemptedExtra) connections and
        // send nothing. Each handler is parked on the read until the budget closes
        // the socket. With the fix in place, only MAX_CLIENT_HANDLERS_FOR_TEST
        // handlers are spawned; the extra attempts are dropped at accept time.
        val attemptedExtra = 5
        val heldSockets = mutableListOf<SocketChannel>()
        try {
            repeat(MAX_CLIENT_HANDLERS_FOR_TEST + attemptedExtra) {
                val ch = SocketChannel.open()
                ch.connect(InetSocketAddress("127.0.0.1", port))
                ch.configureBlocking(true)
                heldSockets += ch
            }

            // Give the listener a moment to accept everything, then count.
            val ready = CountDownLatch(1)
            Thread {
                Thread.sleep(1_500)
                ready.countDown()
            }.start()
            assertTrue(ready.await(3, TimeUnit.SECONDS), "listener did not drain accepts in time")
            val parkedThreadsAfter = parkedThreadCount()

            val spawned = parkedThreadsAfter - parkedThreadsBefore
            // The fix bounds this at MAX_CLIENT_HANDLERS_FOR_TEST. Without the fix
            // every accepted connection would spawn a daemon, so the count would
            // be MAX_CLIENT_HANDLERS_FOR_TEST + attemptedExtra (= 37). Allow small
            // slack for thread-lifecycle races.
            assertTrue(
                spawned <= MAX_CLIENT_HANDLERS_FOR_TEST + 2,
                "flood parked $spawned handler threads; expected <= ${MAX_CLIENT_HANDLERS_FOR_TEST + 2}",
            )
        } finally {
            heldSockets.forEach { runCatching { it.close() } }
        }
    }

    private fun parkedThreadCount(): Int {
        val all = Thread.getAllStackTraces().keys
        return all.count { it.name == "BOSS-IPC-Client-Handler" }
    }

    private fun findFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}

private const val MAX_CLIENT_HANDLERS_FOR_TEST = 32
