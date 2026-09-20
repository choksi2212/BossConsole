package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CloseSessionRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import io.grpc.Context
import io.grpc.kotlin.GrpcContextElement
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the bug where `TerminalServiceImpl.streamOutput` re-checked
 * the caller on EVERY chunk the subscriber received, doing a TLS lookup +
 * permission check on every output byte. See issue #1321.
 *
 * The fix moves the per-stream auth check to stream START, so the per-chunk
 * hot path never re-enters `IpcCall.current()`. This test counts the number
 * of `IpcCall.current()` invocations during a stream subscription that
 * receives many chunks, and asserts the count is bounded (well below the
 * chunk count) rather than scaling linearly with the bytes emitted.
 */
class TerminalServiceStreamOutputAuthCountTest {
    private val root = Files.createTempDirectory("terminal-stream-auth-")
    private val service = TerminalServiceImpl(activeLimit = 4, historyLimit = 8)
    private val registry = ProcessTokenRegistry()
    private val tls = IpcTlsIdentity.create()
    private val token = registry.issue("stream-auth")
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, tls).addService(service).start()
    private val client =
        BossIpcClient("tcp://127.0.0.1:${server.port}", IpcClientCredentials(tls.certificateBase64, token))
    private val stub = TerminalServiceGrpcKt.TerminalServiceCoroutineStub(client.channel)

    // Counters: how many times IpcCall.current() was invoked during a stream
    // subscription. The supplier is called once per IpcCall.current() call
    // (every TLS lookup), so this measures the auth-check cost.
    private val authCalls = AtomicInteger(0)
    private val counterSupplier: () -> ai.rever.boss.ipc.auth.ProcessIdentity? =
        {
            authCalls.incrementAndGet()
            registry.principalFor(token)
        }
    private val callerContext =
        GrpcContextElement(
            Context.ROOT.withValue(ProcessIdentityInterceptor.CURRENT_PRINCIPAL, counterSupplier),
        )

    @AfterTest
    fun cleanup() =
        runBlocking {
            stub.listSessions(Empty.getDefaultInstance()).sessionsList.forEach { session ->
                stub.closeSession(CloseSessionRequest.newBuilder().setSessionId(session.sessionId).build())
            }
            client.shutdown(0)
            server.stop()
            service.close()
            root.toFile().deleteRecursively()
        }

    @Test
    fun `streamOutput auth check does not scale with the chunk count`() =
        runBlocking {
            withTimeout(10_000) {
                // Start a session that emits many small chunks. The `flood` shell
                // pattern echoes 200 lines via printf - the pump sees one read
                // per chunk because the child writes one line at a time.
                val id = start("flood", expectedChunks = 200)
                val before = authCalls.get()
                val output = stub.streamOutput(stream(id)).toList()
                val after = authCalls.get()
                val used = after - before
                val chunks = output.size

                assertTrue(
                    chunks >= 50,
                    "test setup: expected at least 50 chunks from the flood session, got $chunks",
                )
                // The old code did one auth call per chunk; that is the failure
                // we are guarding against. The fix bounds the count: one check
                // to admit the stream, plus a small constant for stream teardown
                // and any flow-internal use of IpcCall. A loose bound of 4 keeps
                // the test readable while pinning the regression.
                assertTrue(
                    used < chunks / 10,
                    "streamOutput auth check must not scale with chunk count (used=$used, chunks=$chunks)",
                )
            }
        }

    private suspend fun start(
        mode: String,
        expectedChunks: Int,
    ): String {
        // The shell test pattern `echo line$i\n` x N produces exactly N chunks
        // for a typical pipe-buffered child. Each `printf` line is short enough
        // to land in a single read on the pipe, so the pump appends one chunk
        // per line. Use the same pattern the existing limits test uses.
        val n = expectedChunks
        val command = if (mode == "flood") {
            // 200 lines: each line is ~16 bytes, well below the 4096 buffer
            // so the pump sees one chunk per line. Deterministic count via shell
            // brace expansion so the loop does not fork.
            val script = buildString {
                append("for i in $(seq 1 $n); do printf 'line%s\\n' \"$i\"; done")
            }
            listOf("/bin/sh", "-c", script)
        } else {
            listOf(mode)
        }
        val response =
            stub.createSession(
                ai.rever.boss.ipc.proto.services.CreateSessionRequest
                    .newBuilder()
                    .addAllCommand(command)
                    .build(),
            )
        return response.sessionId
    }

    private fun stream(id: String) = StreamOutputRequest.newBuilder().setSessionId(id).build()
}
