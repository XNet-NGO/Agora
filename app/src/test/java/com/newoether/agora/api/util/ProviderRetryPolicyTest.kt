package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetryPolicyTest {

    @Test
    fun allowsFiveRetriesAfterInitialRequest() {
        assertEquals(5, ProviderRetryPolicy.MAX_RETRIES)
        assertEquals(6, ProviderRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun delayScheduleIsThreeFiveSecondThenTwoThirtySecondWaits() {
        assertEquals(
            listOf(5_000L, 5_000L, 5_000L, 30_000L, 30_000L),
            (1..ProviderRetryPolicy.MAX_RETRIES).map(ProviderRetryPolicy::delayMillis),
        )
    }

    @Test
    fun failedToGenerateOutcomeRemainsRecognizedCaseInsensitively() {
        assertTrue(ProviderRetryPolicy.isFailedToGenerateOutcome("Failed to generate"))
        assertTrue(
            ProviderRetryPolicy.isFailedToGenerateOutcome(
                "upstream: FAILED TO GENERATE response",
            )
        )
        assertFalse(ProviderRetryPolicy.isFailedToGenerateOutcome("completed"))
        assertFalse(ProviderRetryPolicy.isFailedToGenerateOutcome(null))
    }

    @Test
    fun transientUpstreamMessagesAreRecognizedCaseInsensitively() {
        assertTrue(ProviderRetryPolicy.isRetryableUpstreamFailure("Failed to generate"))
        assertTrue(
            ProviderRetryPolicy.isRetryableUpstreamFailure(
                "The server response could not be read.",
            )
        )
        assertTrue(
            ProviderRetryPolicy.isRetryableUpstreamFailure(
                "UPSTREAM: THE SERVER RESPONSE COULD NOT BE READ",
            )
        )
        assertFalse(ProviderRetryPolicy.isRetryableUpstreamFailure("completed"))
        assertFalse(ProviderRetryPolicy.isRetryableUpstreamFailure(null))
    }

    @Test
    fun permanentQuotaBillingAndRequestDetailsWinOverRetryableHttpStatusAndText() {
        listOf(
            "insufficient_quota", "quota_exhausted", "insufficient_balance", "insufficient_credits",
            "billing_hard_limit_reached", "billing_not_active", "invalid_api_key",
            "authentication_error", "invalid_request_error", "context_length_exceeded",
            "unsupported_parameter",
        ).forEach { identifier ->
            listOf("code", "type").forEach { field ->
                listOf(429, 503).forEach { status ->
                    assertFalse(
                        "$status / $field=$identifier",
                        ProviderRetryPolicy.shouldRetryHttp(
                            status,
                            """{"error":{"$field":"$identifier","message":"Failed to generate; overloaded"}}""",
                            setOf(429, 503),
                        ),
                    )
                }
            }
        }
        listOf(
            "You exceeded your current quota", "Your credit balance is too low",
            "Insufficient balance", "Out of credits", "Billing hard limit reached",
        ).forEach { message ->
            assertFalse(
                message,
                ProviderRetryPolicy.shouldRetryHttp(429, message, setOf(429, 503)),
            )
        }
    }

    @Test
    fun perMinuteQuotaAndOrdinaryOverloadRemainTransient() {
        listOf(
            """{"error":{"code":"rate_limit_exceeded","message":"Per-minute quota exceeded"}}""",
            """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Request quota exceeded; retry in one minute"}}""",
            """{"error":{"code":"quota_exceeded","message":"Tokens per minute exceeded"}}""",
            "Too many requests",
        ).forEach { body ->
            assertTrue(body, ProviderRetryPolicy.shouldRetryHttp(429, body, setOf(429, 503)))
        }
        assertTrue(ProviderRetryPolicy.shouldRetryHttp(503, "Server overloaded", setOf(503)))
    }

    @Test
    fun streamClassificationUsesCodeAndTypeAndKeepsPermanentPrecedence() {
        listOf(
            GenerationError.Api("insufficient_quota", "rate_limit_error", "Failed to generate"),
            GenerationError.Api("429", "invalid_request_error", "Overloaded"),
            GenerationError.Api(null, "authentication_error", "Authentication failed"),
            GenerationError.Api("429", "RESOURCE_EXHAUSTED", "Credit balance is too low"),
        ).forEach { error -> assertFalse(error.toString(), ProviderRetryPolicy.shouldRetryStreamError(error)) }
        listOf(
            GenerationError.Api("rate_limit_exceeded", null, "Try again"),
            GenerationError.Api(null, "overloaded_error", "Try again"),
            GenerationError.Api("429", "RESOURCE_EXHAUSTED", "Per-minute quota exceeded"),
        ).forEach { error -> assertTrue(error.toString(), ProviderRetryPolicy.shouldRetryStreamError(error)) }
    }

    @Test
    fun responseReadRetryRecognizesTypedParseAndExactMessagesDespitePermanentFields() {
        listOf(
            GenerationError.SseParse("not-json", "invalid JSON"),
            GenerationError.Api("insufficient_quota", "authentication_error", "The server response could not be read."),
            GenerationError.Api("invalid_request_error", "provider_error", "UPSTREAM: THE SERVER RESPONSE COULD NOT BE READ"),
            GenerationError.Network(401, "The server response could not be read."),
            GenerationError.Unknown(IllegalStateException("The server response could not be read.")),
            GenerationError.Unknown(IllegalStateException("request failed", java.io.IOException("The server response could not be read."))),
            GenerationError.Unknown(java.net.UnknownHostException("The server response could not be read.")),
        ).forEach { error ->
            assertTrue(error.toString(), ProviderRetryPolicy.requiresResponseReadRetry(error))
        }
        listOf(
            null,
            GenerationError.Api("invalid_request_error", null, "The server response was rejected"),
            GenerationError.Api("insufficient_quota", null, "Failed to generate"),
            GenerationError.Network(0, "Unexpected end of stream"),
            GenerationError.Unknown(IllegalStateException("Response could not be read")),
            GenerationError.Unknown(kotlinx.coroutines.CancellationException("The server response could not be read.")),
            GenerationError.Unknown(java.io.IOException("The server response could not be read.", kotlinx.coroutines.CancellationException("stopped"))),
            GenerationError.Timeout,
        ).forEach { error ->
            assertFalse(error.toString(), ProviderRetryPolicy.requiresResponseReadRetry(error))
        }
    }
    @Test
    fun exactUnreadableHttpResponseOverridesAuthenticationAndPermanentRequestFields() {
        listOf(400, 401, 403, 429, 503).forEach { status ->
            listOf("insufficient_quota", "authentication_error", "invalid_request_error").forEach { type ->
                val body = """{"error":{"code":"$type","type":"$type","message":"The server response could not be read."}}"""
                assertTrue("$status / $type", ProviderRetryPolicy.shouldRetryHttp(status, body, setOf(429, 503)))
                assertFalse(
                    "$status / $type without exact phrase",
                    ProviderRetryPolicy.shouldRetryHttp(
                        status,
                        body.replace("The server response could not be read.", "Failed to generate: request rejected"),
                        setOf(429, 503),
                    ),
                )
            }
        }
    }
    @Test
    fun encodedUnreadableHttpMessageUsesDecodedClassificationBeforePermanentRejection() {
        val body = """{"error":{"type":"invalid_request_error","message":"The server response could not be \u0072ead."}}"""
        assertFalse(ProviderRetryPolicy.isUnreadableServerResponseFailure(body))
        listOf(400, 401, 403, 429, 503).forEach { status ->
            assertTrue(status.toString(), ProviderRetryPolicy.isResponseReadHttpFailure(status, body))
            assertTrue(status.toString(), ProviderRetryPolicy.shouldRetryHttp(status, body, setOf(429, 503)))
            val unrelated = body.replace("\\u0072ead.", "rejected.")
            assertFalse(status.toString(), ProviderRetryPolicy.isResponseReadHttpFailure(status, unrelated))
            assertFalse(status.toString(), ProviderRetryPolicy.shouldRetryHttp(status, unrelated, setOf(429, 503)))
        }
    }
    @Test
    fun exactUnreadableStreamMessageOverridesPermanentApiFields() {
        listOf("insufficient_quota", "authentication_error", "invalid_request_error").forEach { type ->
            val error = GenerationError.Api(type, type, "The server response could not be read.")
            assertTrue(type, ProviderRetryPolicy.shouldRetryStreamError(error))
            assertFalse(type, ProviderRetryPolicy.shouldRetryStreamError(error.copy(message = "Request rejected")))
        }
    }
    @Test
    fun authenticationStatusCannotBeOverriddenByTransientMessage() {
        listOf(401, 403).forEach { status ->
            assertFalse(ProviderRetryPolicy.shouldRetryHttp(status, "Failed to generate", setOf(429, 503)))
        }
    }

    @Test
    fun transientUpstreamHttpBodyRetriesEvenOnOtherwiseNonRetryableStatus() {
        listOf(
            "upstream failed to generate",
            "The server response could not be read",
        ).forEach { body ->
            assertTrue(
                ProviderRetryPolicy.shouldRetryHttp(
                    statusCode = 400,
                    body = body,
                    retryableStatusCodes = setOf(429, 502, 503, 504),
                )
            )
        }
        assertFalse(
            ProviderRetryPolicy.shouldRetryHttp(
                statusCode = 400,
                body = "invalid API key",
                retryableStatusCodes = setOf(429, 502, 503, 504),
            )
        )
    }
}
