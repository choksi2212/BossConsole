package ai.rever.boss.cli

import ai.rever.boss.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObjectBuilder
import java.util.Properties

/**
 * Inspects the resolved BOSS configuration with source attribution.
 *
 * Every value the host reads comes through a four-tier chain: environment
 * variable, system property, the `env_vars` file under the data directory
 * (`BOSS_MODE` only), `local.properties`, then embedded build config baked
 * in at packaging time. A reader who only sees the resolved value has no
 * way to know which tier won, and "the setting doesn't take effect" is the
 * question this command exists to answer.
 *
 * Usage:
 *   boss config show [--key <key>]... [--all] [--json]
 *
 * `--all` includes keys whose resolved value is null (the chain returned no
 * tier at all); without it, only keys that resolved to something are listed.
 * A sensitive value (license, token, anon key) is masked to its prefix and
 * length so the report is safe to share.
 *
 * Exit codes: 0 if every requested key resolved, 1 if any of them resolved
 * to null. `--json` always exits 0; the JSON `missing` array carries the
 * keys that did not resolve.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
class BossConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) =
        "Inspects resolved BOSS configuration with per-key source attribution"

    private val show = ConfigShow()

    val json by option("--json", help = "Output the report as JSON").flag(default = false)
    val all by option(
        "--all",
        help = "Include keys whose resolved value is null",
    ).flag(default = false)
    val key by option(
        "-k",
        "--key",
        help = "Restrict to one or more keys (repeatable); defaults to every tracked key",
    ).multiple()

    override fun run() {
        val keys =
            if (key.isEmpty()) {
                show.trackedKeys
            } else {
                key
            }
        val report =
            show.collect(
                keys = keys,
                includeAll = all,
                envProvider = System::getenv,
                syspropProvider = System::getProperty,
            )
        renderAndExit(report, json)
    }

    private fun renderAndExit(
        report: ConfigShow.Report,
        json: Boolean,
    ) {
        if (json) {
            echo(
                buildJsonObject {
                    put("status", if (report.missing.isEmpty()) "ok" else "missing")
                    put("rows", buildJsonArray { report.rows.forEach { addJsonObject { serializeRow(it) } } })
                    put("missing", buildJsonArray { report.missing.forEach { add(it) } })
                }.toString(),
            )
        } else {
            echo("BOSS Configuration")
            echo("-----------------")
            if (report.rows.isEmpty()) {
                echo("No tracked key resolved. Try with --all or --key <name>.")
            } else {
                val keyWidth = (report.rows.maxOf { it.key.length }).coerceAtLeast(3)
                val sourceWidth = (report.rows.maxOf { it.source.label.length }).coerceAtLeast(6)
                echo("KEY".padEnd(keyWidth) + "  " + "VALUE".padEnd(40) + "  " + "SOURCE".padEnd(sourceWidth) + "  TIER")
                for (row in report.rows) {
                    val valueCell = if (row.masked) row.value else row.value.take(40)
                    echo(
                        row.key.padEnd(keyWidth) + "  " +
                            valueCell.padEnd(40) + "  " +
                            row.source.label.padEnd(sourceWidth) + "  " +
                            row.tier.toString(),
                    )
                }
            }
            if (report.missing.isNotEmpty()) {
                echo("")
                echo("Did not resolve:")
                for (k in report.missing) {
                    echo("  $k")
                }
            }
        }
        if (report.missing.isNotEmpty() && !json) throw ProgramResult(1)
    }

    private fun JsonObjectBuilder.serializeRow(row: ConfigShow.Row) {
        put("key", row.key)
        put("value", row.value)
        put("source", row.source.label)
        put("tier", row.tier.toString())
        put("masked", row.masked)
    }
}

/**
 * Source-of-truth the operator sees in the report. Every entry says which
 * tier actually won the resolve, not just "where the key was found" - a
 * `local.properties` line that is overwritten by an env var still resolves
 * through the env var, and the report has to agree with that.
 */
enum class ConfigSource(val label: String) {
    ENV_VAR("environment"),
    SYSTEM_PROPERTY("system property"),
    ENV_VARS_FILE("env_vars file"),
    LOCAL_PROPERTIES("local.properties"),
    EMBEDDED("embedded"),
    NONE("not set"),
}

/** Numeric precedence; the lowest number is the tier that wins. */
enum class ConfigTier(val priority: Int) {
    ENV_VAR(1),
    SYSTEM_PROPERTY(2),
    ENV_VARS_FILE(3),
    LOCAL_PROPERTIES(4),
    EMBEDDED(5),
    NONE(99),
}

/**
 * Pure aggregation. Splitting this from the Clikt wiring is what makes the
 * precedence rules testable: a test can hand-build every tier and assert
 * which one wins, which is what callers actually need to know.
 */
class ConfigShow {
    /** Keys the host reads at startup, in display order. */
    val trackedKeys: List<String> =
        listOf(
            "BOSS_MODE",
            "BOSS_DATA_DIR",
            "BOSS_LOG_LEVEL",
            "BOSS_BROWSER_TELEMETRY_DISABLED",
            "BOSS_BROWSER_SWIPE_NAV",
            "BOSS_RENDERING_MODE",
            "BOSS_UPDATE_APP_ID",
            "BOSS_UPDATE_BUCKET",
            "BOSS_UPDATE_PRIMARY_SOURCE",
            "BOSS_RPA_PROFILE_CAP_BYTES",
            "SUPABASE_URL",
            "SUPABASE_FUNCTION_URL",
            "SUPABASE_ANON_KEY",
            "GITHUB_TOKEN",
            "jxbrowser.license.key",
            "JXBROWSER_LICENSE_KEY",
        )

    /** A single resolved key, with the tier that won and the source it came from. */
    data class Row(
        val key: String,
        val value: String,
        val source: ConfigSource,
        val tier: ConfigTier,
        val masked: Boolean,
    )

    data class Report(
        val rows: List<Row>,
        val missing: List<String>,
    )

    /**
     * Resolve [keys] against the four tiers.
     *
     * `includeAll = false` skips keys that did not resolve anywhere; `true`
     * keeps them under a `missing` bucket so the caller can show "did not
     * resolve" rather than silently omitting them.
     *
     * `envVarsProps` is read only when the key is `BOSS_MODE`, matching the
     * precedence in [ConfigLoader.resolve]. A null `embeddedProps` is
     * tolerated so tests can pin tier-5 wins without a packaged build.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun collect(
        keys: List<String>,
        includeAll: Boolean,
        envProvider: (String) -> String?,
        syspropProvider: (String) -> String?,
        envVarsProps: Properties = Properties(),
        localProps: Properties = Properties(),
        embeddedProps: Properties? = Properties(),
    ): Report {
        val rows = mutableListOf<Row>()
        val missing = mutableListOf<String>()
        for (key in keys) {
            val resolved =
                resolveOne(
                    key = key,
                    envValue = envProvider(key),
                    sysPropValue = syspropProvider(key),
                    envVarsProps = envVarsProps,
                    localProps = localProps,
                    embeddedProps = embeddedProps,
                ) ?: continue
            val (tier, source) = resolved
            if (tier == ConfigTier.NONE) {
                if (includeAll) {
                    rows += Row(key = key, value = "", source = source, tier = tier, masked = false)
                } else {
                    missing += key
                }
                continue
            }
            val value = valueAt(key, tier, envProvider, syspropProvider, envVarsProps, localProps, embeddedProps)
            val masked = isSensitive(key)
            val displayValue = if (masked) maskValue(value) else value
            rows += Row(key = key, value = displayValue, source = source, tier = tier, masked = masked)
        }
        return Report(rows = rows, missing = missing.sorted())
    }

    /**
     * Walk the precedence chain. Returns the tier that wins and its [ConfigSource].
     * Pure; only the reader injection matters for behaviour.
     */
    private fun resolveOne(
        key: String,
        envValue: String?,
        sysPropValue: String?,
        envVarsProps: Properties,
        localProps: Properties,
        embeddedProps: Properties?,
    ): Pair<ConfigTier, ConfigSource>? {
        val envBlank = envValue.isNullOrBlank()
        val sysBlank = sysPropValue.isNullOrBlank()
        val envVarsBlank = if (key == "BOSS_MODE") envVarsProps.getProperty(key).isNullOrBlank() else true
        val localBlank = localProps.getProperty(key).isNullOrBlank()
        val embeddedBlank = embeddedProps?.getProperty(key).isNullOrBlank()

        val tier =
            when {
                !envBlank -> ConfigTier.ENV_VAR
                !sysBlank -> ConfigTier.SYSTEM_PROPERTY
                !envVarsBlank -> ConfigTier.ENV_VARS_FILE
                !localBlank -> ConfigTier.LOCAL_PROPERTIES
                !embeddedBlank -> ConfigTier.EMBEDDED
                else -> ConfigTier.NONE
            }
        // The same lookup is mirrored in ConfigLoader.resolve. We mirror
        // it here so the test can pin which tier wins without standing up
        // the full ConfigLoader process init.
        val resolved = ConfigLoader.resolve(
            key = key,
            defaultValue = null,
            envValue = envValue,
            sysPropValue = sysPropValue,
            envVarsProps = envVarsProps,
            localProps = localProps,
            embeddedProps = embeddedProps ?: Properties(),
        )
        if (resolved == null) return null
        val source =
            when (tier) {
                ConfigTier.ENV_VAR -> ConfigSource.ENV_VAR
                ConfigTier.SYSTEM_PROPERTY -> ConfigSource.SYSTEM_PROPERTY
                ConfigTier.ENV_VARS_FILE -> ConfigSource.ENV_VARS_FILE
                ConfigTier.LOCAL_PROPERTIES -> ConfigSource.LOCAL_PROPERTIES
                ConfigTier.EMBEDDED -> ConfigSource.EMBEDDED
                ConfigTier.NONE -> ConfigSource.NONE
            }
        return tier to source
    }

    /**
     * The literal value at [tier]. Used for the masked display, so the
     * report shows what is at the winning tier, not the normalised value
     * the host applies downstream (e.g. `BOSS_MODE` is upper-cased at
     * resolve time but stored upper-case here too, so the discrepancy
     * would be invisible - this keeps the report honest).
     */
    private fun valueAt(
        key: String,
        tier: ConfigTier,
        envProvider: (String) -> String?,
        syspropProvider: (String) -> String?,
        envVarsProps: Properties,
        localProps: Properties,
        embeddedProps: Properties?,
    ): String =
        when (tier) {
            ConfigTier.ENV_VAR -> envProvider(key).orEmpty()
            ConfigTier.SYSTEM_PROPERTY -> syspropProvider(key).orEmpty()
            ConfigTier.ENV_VARS_FILE -> envVarsProps.getProperty(key).orEmpty()
            ConfigTier.LOCAL_PROPERTIES -> localProps.getProperty(key).orEmpty()
            ConfigTier.EMBEDDED -> embeddedProps?.getProperty(key).orEmpty()
            ConfigTier.NONE -> ""
        }

    /** Keys whose value is too sensitive to print in plain. */
    private fun isSensitive(key: String): Boolean =
        key in SENSITIVE_KEYS

    /**
     * Mask a sensitive value. Shows the first 4 chars, then `…<len>` so the
     * report tells the reader what kind of value is there without printing
     * the secret. `sk-abc...def (len=51)` is enough to compare two reports
     * for equality, which is what an operator usually wants.
     */
    private fun maskValue(value: String): String {
        if (value.isEmpty()) return ""
        val head = value.take(4)
        return "$head... (len=${value.length})"
    }

    private companion object {
        val SENSITIVE_KEYS =
            setOf(
                "SUPABASE_ANON_KEY",
                "GITHUB_TOKEN",
                "jxbrowser.license.key",
                "JXBROWSER_LICENSE_KEY",
            )
    }
}
