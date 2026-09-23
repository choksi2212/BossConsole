package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryEdge
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pin the contract for definitions that the executor cannot honour. `MasteryEdge.condition`
 * is documented and transported but the executor never reads it (issue #1060) - a guarded
 * edge silently executes its dependent node regardless of the guard. The validation refuses
 * any definition that depends on a condition so a guarded mastery fails closed at create
 * time rather than running unconditionally.
 */
class MasteryDefinitionValidationTest {
    @Test
    fun `a definition with a non-null condition is refused as INVALID_ARGUMENT (#1060)`() =
        runTest {
            val service = service()
            val conditional =
                baseDefinition("guarded")
                    .toBuilder()
                    .addEdges(
                        MasteryEdge
                            .newBuilder()
                            .setFromNode("INPUT")
                            .setToNode("node")
                            .setCondition("scan_clean == \"true\""),
                    ).build()

            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(conditional)
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
            assertTrue(
                error.status.description
                    .orEmpty()
                    .contains("condition is reserved"),
                "the refusal must name the field so the author knows which to remove: " +
                    error.status.description,
            )
        }

    @Test
    fun `a blank condition is accepted (#1060)`() =
        runTest {
            // A blank condition is the wire-default for editors that always emit the field;
            // accepting it keeps authoring ergonomic.
            val service = service()
            val withBlank =
                baseDefinition("blank")
                    .toBuilder()
                    .addEdges(
                        MasteryEdge
                            .newBuilder()
                            .setFromNode("INPUT")
                            .setToNode("node")
                            .setCondition("   "),
                    ).build()
            service.createMastery(withBlank) // no throw
        }

    @Test
    fun `an unconditional definition is unchanged by the new validation (#1060)`() =
        runTest {
            // Regression guard: a definition with edges but no conditions must keep being
            // accepted, both with and without the edges field populated.
            val service = service()
            service.createMastery(baseDefinition("plain"))
            val withEdge =
                baseDefinition("with-edge")
                    .toBuilder()
                    .addEdges(
                        MasteryEdge
                            .newBuilder()
                            .setFromNode("INPUT")
                            .setToNode("node"),
                    ).build()
            service.createMastery(withEdge) // no throw
        }

    private fun service(): MasteryServiceImpl =
        MasteryServiceImpl(
            MasteryExecutor(
                object : CapabilityResolver {
                    override suspend fun invoke(
                        pluginId: String,
                        action: String,
                        input: Map<String, String>,
                    ): Map<String, String> = mapOf("result" to "ok")

                    override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
                },
            ),
            definitionLimit = 3,
            executionLimit = 1,
            historyLimit = 1,
        )

    private fun baseDefinition(id: String): MasteryDefinition =
        MasteryDefinition
            .newBuilder()
            .setId(id)
            .addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("node")
                    .setPluginId("plugin")
                    .setAction("test"),
            ).build()
}
