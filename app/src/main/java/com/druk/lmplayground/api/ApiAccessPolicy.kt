package com.druk.lmplayground.api

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.druk.lmplayground.storage.StoragePreferences

/**
 * Decides whether a calling app may use the inference API.
 *
 * The default is permissive — but the seam is here from day one, because
 * retrofitting authorisation onto a shipped IPC surface is much harder than
 * designing for it. Every future policy shape fits [check] without changing its
 * signature:
 *
 * - **Allowlist** — match [packages] against a set in [StoragePreferences].
 * - **Signature pinning** — `packageManager.hasSigningCertificate(pkg, sha256,
 *   CERT_INPUT_SHA256)`.
 * - **Bearer token** — inspect [token], which comes from `lmp.auth_token` in
 *   the request body. This is why the parameter exists now rather than later.
 * - **Interactive consent** — return [Decision.NeedsUserConsent] with a
 *   `PendingIntent`; the handler surfaces it as `permission_denied` carrying
 *   `lmp.consent_intent` and the client fires it.
 *
 * **Why the policy is in code and not `android:permission` on the service.**
 * A custom permission declared by LM Playground is only granted to a client
 * whose `<uses-permission>` is evaluated *after* LM Playground is installed. A
 * client installed first would silently never receive the grant and would need
 * a reinstall to fix — a well-known footgun for a public API. Checking in code
 * also lets the user revoke access without either app being updated.
 */
interface ApiAccessPolicy {

    sealed interface Decision {
        object Allow : Decision
        data class Deny(val message: String) : Decision
        /** Reserved: an interactive grant the client can trigger. */
        data class NeedsUserConsent(val consent: PendingIntent) : Decision
    }

    enum class Op { SERVICE_INFO, LIST_MODELS, CHAT_COMPLETION, PUT_BLOB }

    /**
     * @param callingUid from `Binder.getCallingUid()`. Must be read
     *        synchronously on the binder thread — it is thread-local to the
     *        transaction and is meaningless after a coroutine hop.
     * @param packages every package sharing [callingUid].
     * @param token the client-supplied `lmp.auth_token`, if any.
     */
    fun check(callingUid: Int, packages: List<String>, op: Op, token: String?): Decision
}

/** The shipped default: everything is allowed. */
class AllowAllAccessPolicy : ApiAccessPolicy {
    override fun check(
        callingUid: Int,
        packages: List<String>,
        op: ApiAccessPolicy.Op,
        token: String?,
    ): ApiAccessPolicy.Decision = ApiAccessPolicy.Decision.Allow
}

/**
 * Honours the user's "Allow other apps to use LM Playground" setting.
 *
 * This is the concrete policy the app ships with, wrapped around
 * [AllowAllAccessPolicy]. It matters because LM Playground's whole positioning
 * is offline and private: an always-on, unauthenticated endpoint that lets any
 * installed app enumerate the user's downloaded models and run prompts on their
 * device is a real change to that posture, and the user should be able to say
 * no without uninstalling anything.
 */
class UserToggleAccessPolicy(
    private val preferences: StoragePreferences,
    private val delegate: ApiAccessPolicy = AllowAllAccessPolicy(),
) : ApiAccessPolicy {

    override fun check(
        callingUid: Int,
        packages: List<String>,
        op: ApiAccessPolicy.Op,
        token: String?,
    ): ApiAccessPolicy.Decision {
        if (!preferences.externalApiEnabled) {
            return ApiAccessPolicy.Decision.Deny(
                "The user has turned off API access for other apps. It can be re-enabled in " +
                    "LM Playground under Settings → Advanced."
            )
        }
        return delegate.check(callingUid, packages, op, token)
    }
}

/** Resolves a calling UID to its package names and a user-facing label. */
class CallerIdentity(private val context: Context) {

    fun packagesFor(uid: Int): List<String> =
        context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    /**
     * A display name for [uid] taken from the package manager.
     *
     * Never use the client-supplied `lmp.client_label` on its own — a caller
     * can claim to be anything. When both are present we prefer this one and
     * only fall back to the claim for disambiguation.
     */
    fun labelFor(uid: Int): String? {
        val packageName = packagesFor(uid).firstOrNull() ?: return null
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "no package info for uid $uid", e)
            packageName
        }
    }

    private companion object {
        private const val TAG = "CallerIdentity"
    }
}
