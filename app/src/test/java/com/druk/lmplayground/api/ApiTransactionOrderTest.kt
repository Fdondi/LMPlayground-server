package com.druk.lmplayground.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the AIDL wire contract.
 *
 * Binder assigns transaction codes by declaration order
 * (`FIRST_CALL_TRANSACTION + index`). Third-party clients compile against a
 * snapshot of `IChatService.aidl` and are **not** rebuilt when LM Playground
 * updates — so reordering, removing, or re-signing a method silently
 * mis-dispatches for every already-installed client. The symptom is not a
 * clean error: it is one method's arguments being read by another's
 * implementation.
 *
 * If this test fails, you changed the wire contract. Append instead.
 */
class ApiTransactionOrderTest {

    /**
     * The contract as shipped in API version 1. Order is significant; the
     * names are not — a rename is source-compatible and wire-compatible, a
     * reorder is neither.
     */
    private val expectedOrder = listOf(
        "TRANSACTION_getApiVersion",
        "TRANSACTION_getServiceInfo",
        "TRANSACTION_listModels",
        "TRANSACTION_createChatCompletion",
        "TRANSACTION_cancel",
        "TRANSACTION_putBlob",
    )

    @Test
    fun transactionCodesAreStable() {
        val codes = expectedOrder.map { name ->
            val field = IChatService.Stub::class.java.getDeclaredField(name)
            field.isAccessible = true
            name to field.getInt(null)
        }

        // Codes must be contiguous from FIRST_CALL_TRANSACTION, in declaration
        // order — that is exactly what the generated dispatcher assumes.
        val first = android.os.IBinder.FIRST_CALL_TRANSACTION
        codes.forEachIndexed { index, (name, code) ->
            assertEquals("$name moved: the wire contract changed", first + index, code)
        }
    }

    @Test
    fun getApiVersionIsTransactionZeroForever() {
        // The one method a client may call without feature-detecting first.
        val field = IChatService.Stub::class.java.getDeclaredField("TRANSACTION_getApiVersion")
        field.isAccessible = true
        assertEquals(android.os.IBinder.FIRST_CALL_TRANSACTION, field.getInt(null))
    }

    @Test
    fun callbackTransactionCodesAreStable() {
        val expected = listOf(
            "TRANSACTION_onChunk",
            "TRANSACTION_onComplete",
            "TRANSACTION_onError",
        )
        val first = android.os.IBinder.FIRST_CALL_TRANSACTION
        expected.forEachIndexed { index, name ->
            val field = IChatCompletionCallback.Stub::class.java.getDeclaredField(name)
            field.isAccessible = true
            assertEquals("$name moved: the callback contract changed", first + index,
                field.getInt(null))
        }
    }

    @Test
    fun noMethodWasAddedWithoutUpdatingThisTest() {
        // Appending is fine — but the new method has to be recorded here, or
        // the next reorder slips through unnoticed.
        val declared = IChatService.Stub::class.java.declaredFields
            .filter { it.name.startsWith("TRANSACTION_") }
        assertEquals(
            "IChatService gained or lost a method; append it to expectedOrder",
            expectedOrder.size, declared.size,
        )
    }
}
