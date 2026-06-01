package com.insola.uv.dashboard

import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class ForecastErrorMessageTest {

    @Test
    fun connectivityExceptionYieldsOfflineMessage() {
        val message = forecastErrorMessage(UnknownHostException("Unable to resolve host"))
        assertEquals("You appear to be offline — showing a clear-sky estimate.", message)
    }

    @Test
    fun genericExceptionYieldsServiceMessage() {
        val message = forecastErrorMessage(RuntimeException("boom"))
        assertEquals("Couldn't reach the forecast service — showing a clear-sky estimate.", message)
    }
}
