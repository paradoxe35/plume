package me.pngwasi.plume.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Required-field rules for the provider editor. */
class ProviderValidationTest {

    private val complete = ProviderConfig(
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
    )

    @Test
    fun `a fully filled provider is valid`() {
        val result = validateProvider(complete, apiKey = "sk-x", requireLabel = true)

        assertTrue(result.isValid)
        assertNull(result.baseUrl)
        assertNull(result.model)
        assertNull(result.apiKey)
        assertNull(result.label)
    }

    @Test
    fun `a missing base url is reported`() {
        val result = validateProvider(complete.copy(baseUrl = ""), "sk-x", requireLabel = true)

        assertFalse(result.isValid)
        assertEquals("Base URL is required", result.baseUrl)
    }

    @Test
    fun `a base url without a scheme is reported`() {
        val result = validateProvider(complete.copy(baseUrl = "api.groq.com"), "sk-x", requireLabel = true)

        assertNotNull(result.baseUrl)
        assertTrue(result.baseUrl!!.contains("http"))
    }

    @Test
    fun `a plain http base url is allowed for local gateways`() {
        val local = complete.copy(baseUrl = "http://localhost:11434/v1")

        assertNull(validateProvider(local, "sk-x", requireLabel = true).baseUrl)
    }

    @Test
    fun `a missing model is reported`() {
        val result = validateProvider(complete.copy(model = "  "), "sk-x", requireLabel = true)

        assertEquals("Model is required", result.model)
    }

    @Test
    fun `a missing api key is reported`() {
        val result = validateProvider(complete, apiKey = "", requireLabel = true)

        assertEquals("API key is required", result.apiKey)
    }

    @Test
    fun `a custom provider must have a display name`() {
        val result = validateProvider(complete.copy(label = ""), "sk-x", requireLabel = true)

        assertEquals("Display name is required", result.label)
    }

    /** Built-ins render their own name, so the field is not shown and not required. */
    @Test
    fun `a built-in provider does not require a display name`() {
        val result = validateProvider(complete.copy(label = ""), "sk-x", requireLabel = false)

        assertNull(result.label)
        assertTrue(result.isValid)
    }

    @Test
    fun `every missing field is reported at once`() {
        val result = validateProvider(ProviderConfig(), apiKey = "", requireLabel = true)

        assertNotNull(result.label)
        assertNotNull(result.baseUrl)
        assertNotNull(result.model)
        assertNotNull(result.apiKey)
    }
}
