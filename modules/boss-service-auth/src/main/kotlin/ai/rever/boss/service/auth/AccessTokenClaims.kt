package ai.rever.boss.service.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Parsed claims from the Supabase access token.
 *
 * `is_admin` and `user_permissions` are injected by `public.custom_access_token_hook`
 * at token mint time; reading them from the access token prevents client-side
 * privilege escalation via user-writable `user_metadata`.
 */
internal data class AccessTokenClaims(
    val isAdmin: Boolean,
    val permissions: Set<String>,
) {
    companion object {
        val NONE = AccessTokenClaims(isAdmin = false, permissions = emptySet())
    }
}

/**
 * Extracts [AccessTokenClaims] from [accessToken], returning null if the token cannot be parsed.
 */
internal fun accessTokenClaims(accessToken: String): AccessTokenClaims? {
    return try {
        val parts = accessToken.split(".")
        if (parts.size != 3) return null
        val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
        val claims = Json.parseToJsonElement(payload).jsonObject
        val isAdmin =
            claims["is_admin"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
        val permissions =
            (claims["user_permissions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.toSet()
                .orEmpty()
        AccessTokenClaims(isAdmin = isAdmin, permissions = permissions)
    } catch (_: Exception) {
        null
    }
}
