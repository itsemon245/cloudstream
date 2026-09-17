package com.lagradost.cloudstream3.cloudsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GoogleDriveAuthorizationTest {
    @Test
    fun `api not connected is reported as unavailable Google Play services`() {
        val result = googleDriveAuthorizationFailure(
            statusCode = 17,
            error = IllegalStateException("API is not available on this device"),
        )

        assertEquals(GoogleDriveAuthorizationOutcome.Unavailable, result)
    }

    @Test
    fun `developer error is reported as missing OAuth configuration`() {
        val error = IllegalStateException("OAuth client is misconfigured")
        val result = googleDriveAuthorizationFailure(statusCode = 10, error = error)

        assertEquals(GoogleDriveAuthorizationOutcome.ConfigurationRequired, result)
    }

    @Test
    fun `other authorization failures retain their original error`() {
        val error = IllegalStateException("Temporary authorization failure")
        val result = googleDriveAuthorizationFailure(statusCode = 8, error = error)

        assertSame(error, (result as GoogleDriveAuthorizationOutcome.Failed).error)
    }
}
