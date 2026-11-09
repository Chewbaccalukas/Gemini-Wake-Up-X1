package com.example

import com.example.importer.GeminiChatParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiChatParserTest {

    private val parser = GeminiChatParser()

    @Test
    fun testParseTranscriptText() = runBlocking {
        val transcript = """
            You: What are the three laws of motion?
            
            Gemini: Newton's three laws of motion are:
            1. An object at rest remains at rest unless acted upon by a net force.
            2. Force equals mass times acceleration (F = ma).
            3. For every action, there is an equal and opposite reaction.
            
            You: Can you give an example of the third law?
            
            Gemini: Sure! A classic example is a rocket launch. The engines expel exhaust gas downwards with high force, and the reaction force pushes the rocket upward into space.
        """.trimIndent()

        val parsed = parser.parseGeminiInput(transcript)
        assertNotNull("Parsed result should not be null", parsed)
        assertEquals(4, parsed!!.messages.size)
        assertEquals("user", parsed.messages[0].sender)
        assertEquals("What are the three laws of motion?", parsed.messages[0].content)
        assertEquals("gemini", parsed.messages[1].sender)
        assertTrue(parsed.messages[1].content.contains("Newton's three laws"))
        assertEquals("user", parsed.messages[2].sender)
        assertEquals("gemini", parsed.messages[3].sender)
        assertTrue(parsed.title.isNotBlank())
        assertTrue(parsed.suggestedWakeWord.isNotBlank())
    }

    @Test
    fun testParseJsonExport() = runBlocking {
        val json = """
            {
                "title": "Quantum Physics Intro",
                "messages": [
                    {
                        "sender": "user",
                        "content": "Explain entanglement simply"
                    },
                    {
                        "sender": "gemini",
                        "content": "Quantum entanglement is a phenomenon where two particles become connected so that measuring one instantly influences the other."
                    }
                ]
            }
        """.trimIndent()

        val parsed = parser.parseGeminiInput(json)
        assertNotNull(parsed)
        assertEquals("Quantum Physics Intro", parsed!!.title)
        assertEquals(2, parsed.messages.size)
        assertEquals("user", parsed.messages[0].sender)
        assertEquals("gemini", parsed.messages[1].sender)
    }

    @Test
    fun testParseQABlocksTranscript() = runBlocking {
        val qaTranscript = """
            Q: How do solar panels work?
            A: Solar panels convert sunlight into electrical energy using photovoltaic cells.
        """.trimIndent()

        val parsed = parser.parseGeminiInput(qaTranscript)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.messages.size)
        assertEquals("user", parsed.messages[0].sender)
        assertEquals("gemini", parsed.messages[1].sender)
    }
}
