package ai.rever.boss.plugin.loader

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for the unbounded class read in `BinaryCompatibilityValidator.validate`.
 *
 * The validator read every `.class` entry with `jar.getInputStream(entry).use { it.readBytes() }`
 * and no cap, so a malicious jar whose class entry decompressed to GBs OOMed the host before
 * any signature check. Distinct from #914 (central-directory size as range-fetch bounds)
 * and #1114 (three manifest readers) - this is the `.class` payload read inside the
 * binary-compat validator, run on every install path before the load gate decides.
 *
 * The fix bounds the read with `readNBytes(MAX_CLASS_BYTES + 1)` and rejects classes
 * whose decompressed size exceeds the cap.
 */
class BinaryCompatibilityValidatorByteCapTest {
    private val tempJars = mutableListOf<File>()

    /**
     * Build a JAR whose single class entry is a stored (uncompressed) payload of [payloadSize]
     * bytes. Stored means the central-directory size and the decompressed size match,
     * so the bug path is reachable without a deflate trick.
     */
    private fun jarWithClassOfSize(payloadSize: Int): String {
        val jar = File.createTempFile("binarycompat-cap", ".jar")
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("com/example/Big.class"))
            val payload = ByteArray(payloadSize) { (it % 95 + 32).toByte() }
            out.write(payload)
            out.closeEntry()
        }
        // Confirm the entry's uncompressed size matches what we wrote, so the validator's
        // `entry.size` hint is trustworthy.
        JarFile(jar).use { verify ->
            val entry = verify.getJarEntry("com/example/Big.class")
            assertNotNull(entry, "test setup: entry not in jar")
            assertTrue(
                entry.size.toInt() == payloadSize,
                "test setup: expected ${payloadSize} bytes stored, got ${entry.size}",
            )
        }
        return jar.absolutePath
    }

    @Test
    fun `validate rejects a class entry whose decompressed size exceeds MAX_CLASS_BYTES`() {
        // 8 MiB stored - well above any plausible class file. The validator must refuse
        // it on the read cap, not OOM on `it.readBytes()`.
        val jarPath = jarWithClassOfSize(payloadSize = 8 * 1024 * 1024)

        val result =
            BinaryCompatibilityValidator.validate(
                classLoader = this::class.java.classLoader,
                jarPath = jarPath,
            )

        assertTrue(
            !result.isCompatible || result.errors.isNotEmpty(),
            "an 8 MiB class entry must be rejected, got isCompatible=${result.isCompatible} errors=${result.errors}",
        )
    }

    @Test
    fun `validate pins MAX_CLASS_BYTES in the source`() {
        // Source-level pin so a future refactor cannot silently drop the cap. Reading the
        // validator's source and asserting it references MAX_CLASS_BYTES and the read loop
        // is bounded, not unbounded.
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "plugin-platform").isDirectory },
                "could not locate the plugin-platform root",
            )
        val file =
            File(
                root,
                "plugin-platform/plugin-loader/src/desktopMain/kotlin/ai/rever/boss/plugin/loader/BinaryCompatibilityValidator.kt",
            )
        assertTrue(file.isFile, "BinaryCompatibilityValidator.kt not found at ${file.absolutePath}")
        val text = file.readText()

        assertTrue(
            text.contains("MAX_CLASS_BYTES") || text.contains("maxClassBytes"),
            "validate must reference a hard MAX_CLASS_BYTES cap",
        )
        assertTrue(
            Regex("""readNBytes\(\s*MAX_CLASS_BYTES""").containsMatchIn(text),
            "validate must bound the class read with readNBytes(MAX_CLASS_BYTES), not it.readBytes()",
        )
    }
}
