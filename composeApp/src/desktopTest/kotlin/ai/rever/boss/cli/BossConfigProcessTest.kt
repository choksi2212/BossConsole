package ai.rever.boss.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fresh JVM proves the command reads files and the embedded resource, not test-only properties. */
class BossConfigProcessTest {
    @Test
    fun `real command reports all five loaded tiers`(
        @TempDir directory: Path,
    ) {
        Files.createDirectories(directory.resolve(".boss"))
        Files.writeString(directory.resolve(".boss/env_vars"), "BOSS_MODE=KERNEL\n")
        Files.writeString(directory.resolve("local.properties"), "SUPABASE_URL=https://local.invalid\n")
        Files.writeString(
            directory.resolve("boss-build-config.properties"),
            "SUPABASE_FUNCTION_URL=https://embedded.invalid\n",
        )
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = directory.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                javaExecutable,
                "-Duser.home=$directory",
                "-DBOSS_LOG_LEVEL=from-system-property",
                "-cp",
                classpath,
                BossConfigProcessProbe::class.java.name,
            ).directory(directory.toFile()).apply {
                environment()["BOSS_BROWSER_SWIPE_NAV"] = "from-environment"
                environment().remove("BOSS_MODE")
                environment().remove("SUPABASE_URL")
                environment().remove("SUPABASE_FUNCTION_URL")
            }.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), stderr)
        val rows = Json.parseToJsonElement(stdout.trim()).jsonObject.getValue("rows").jsonArray
        val sources = rows.associate { row ->
            val fields = row.jsonObject
            fields.getValue("key").jsonPrimitive.content to fields.getValue("source").jsonPrimitive.content
        }
        assertEquals("environment", sources["BOSS_BROWSER_SWIPE_NAV"])
        assertEquals("system property", sources["BOSS_LOG_LEVEL"])
        assertEquals("env_vars file", sources["BOSS_MODE"])
        assertEquals("local.properties", sources["SUPABASE_URL"])
        assertEquals("embedded", sources["SUPABASE_FUNCTION_URL"])
        assertTrue(stdout.contains("https://embedded.invalid"))
    }
}

object BossConfigProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        configureHeadlessLogging()
        createBossCLI().parse(
            listOf(
                "config", "show", "--json",
                "--key", "BOSS_BROWSER_SWIPE_NAV",
                "--key", "BOSS_LOG_LEVEL",
                "--key", "BOSS_MODE",
                "--key", "SUPABASE_URL",
                "--key", "SUPABASE_FUNCTION_URL",
            ),
        )
    }
}
