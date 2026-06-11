package com.duddylabs.translator.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPreferencePolicyTest {
    @Test
    fun shouldPreferCellular_isDisabledWhenSettingIsOff() {
        assertFalse(
            NetworkPreferencePolicy.shouldPreferCellular(
                preferCellularData = false,
                targetUrl = "https://translator.example.com",
            ),
        )
    }

    @Test
    fun shouldPreferCellular_allowsPublicHosts() {
        assertTrue(
            NetworkPreferencePolicy.shouldPreferCellular(
                preferCellularData = true,
                targetUrl = "https://translator.example.com",
            ),
        )
        assertTrue(
            NetworkPreferencePolicy.shouldPreferCellular(
                preferCellularData = true,
                targetUrl = "wss://api.openai.com/v1/realtime",
            ),
        )
    }

    @Test
    fun shouldPreferCellular_ignoresLocalDevelopmentUrls() {
        val urls = listOf(
            "http://127.0.0.1:8001",
            "http://localhost:8001",
            "http://10.0.2.2:8001",
            "http://192.168.1.50:8001",
            "http://172.20.10.2:8001",
            "http://backend.local:8001",
        )

        urls.forEach { url ->
            assertFalse(
                url,
                NetworkPreferencePolicy.shouldPreferCellular(
                    preferCellularData = true,
                    targetUrl = url,
                ),
            )
        }
    }
}
