package com.druk.lmplayground.api

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.LlamaGenerationSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The continuation-token registry.
 *
 * The security-relevant case is [tokenFromAnotherCallerIsRejected]: a token
 * references a live conversation with its KV cache intact, so an app that
 * overheard one must not be able to continue someone else's turn.
 *
 * Robolectric because [LlamaGenerationSession] is constructed reflectively —
 * we never call it, we only need identity.
 */
@RunWith(RobolectricTestRunner::class)
class ParkedToolTurnsTest {

    /**
     * A session that is never actually used.
     *
     * [ParkedToolTurns] only stores and compares references — every real method
     * on the session goes over binder, and we never reach one. Constructing it
     * for real (the constructor is `internal`, and the test source set is a
     * friend of main) keeps the test honest about the type without needing a
     * mocking framework the project doesn't use.
     */
    private fun fakeSession(): LlamaGenerationSession {
        val client = InferenceClient(
            appContext = ApplicationProvider.getApplicationContext(),
            serviceComponent = ComponentName("com.druk.lmplayground", "Fake"),
        )
        return LlamaGenerationSession(client, sessionCounter++)
    }

    private var sessionCounter = 1

    @Test
    fun parkAndClaimRoundTrips() {
        val registry = ParkedToolTurns()
        val session = fakeSession()

        val token = registry.park(session, callerUid = 1000, modelFilename = "m.gguf",
            toolCallIds = setOf("call_0"))
        val claimed = registry.claim(token, 1000, "m.gguf", setOf("call_0"))

        assertNotNull(claimed)
        assertEquals(session, claimed)
        // Claiming removes it — a token is single-use.
        assertEquals(0, registry.size())
    }

    @Test
    fun tokenFromAnotherCallerIsRejected() {
        val registry = ParkedToolTurns()
        val token = registry.park(fakeSession(), callerUid = 1000, modelFilename = "m.gguf",
            toolCallIds = setOf("call_0"))

        assertNull(registry.claim(token, callerUid = 2000, "m.gguf", setOf("call_0")))
        // Still parked — a wrong-caller attempt must not let the attacker
        // evict the legitimate owner's continuation either.
        assertEquals(1, registry.size())
    }

    @Test
    fun unknownTokenFallsBackRatherThanFailing() {
        val registry = ParkedToolTurns()
        assertNull(registry.claim("cont_nonexistent", 1000, "m.gguf", setOf("call_0")))
        assertNull(registry.claim(null, 1000, "m.gguf", emptySet()))
    }

    @Test
    fun tokenIssuedAgainstAnotherModelIsRejected() {
        val registry = ParkedToolTurns()
        val token = registry.park(fakeSession(), 1000, "old.gguf", setOf("call_0"))
        assertNull(registry.claim(token, 1000, "new.gguf", setOf("call_0")))
        // Dropped: the session belongs to a model we're no longer using.
        assertEquals(0, registry.size())
    }

    @Test
    fun answeringDifferentToolCallsIsRejected() {
        // Resuming here would desynchronise the KV cache from the conversation
        // the client thinks it is having.
        val registry = ParkedToolTurns()
        val token = registry.park(fakeSession(), 1000, "m.gguf", setOf("call_0"))
        assertNull(registry.claim(token, 1000, "m.gguf", setOf("call_9")))
        assertEquals(0, registry.size())
    }

    @Test
    fun exceedingTheCapEvictsOldestFirst() {
        val registry = ParkedToolTurns()
        val first = registry.park(fakeSession(), 1000, "m.gguf", setOf("a"))
        val second = registry.park(fakeSession(), 1000, "m.gguf", setOf("b"))
        val third = registry.park(fakeSession(), 1000, "m.gguf", setOf("c"))

        assertEquals(ParkedToolTurns.MAX_PARKED, registry.size())
        // Each parked turn holds a live inference context, so the cap is a
        // memory guarantee, not a nicety.
        assertNull(registry.claim(first, 1000, "m.gguf", setOf("a")))
        assertNotNull(registry.claim(second, 1000, "m.gguf", setOf("b")))
        assertNotNull(registry.claim(third, 1000, "m.gguf", setOf("c")))
    }

    @Test
    fun clearDropsEverything() {
        val registry = ParkedToolTurns()
        registry.park(fakeSession(), 1000, "m.gguf", setOf("a"))
        registry.clear(destroySessions = false)
        assertEquals(0, registry.size())
    }
}
