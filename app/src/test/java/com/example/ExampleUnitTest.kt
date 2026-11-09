package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun matchesCategoryFilter_handlesPresetsAndCustomCategories() {
    val assistantChat = com.example.data.model.ChatSession(
      id = 1L,
      title = "General Gemini Assistant",
      wakeWord = "hey gemini",
      isDefaultWakeChat = true
    )
    val codeChat = com.example.data.model.ChatSession(
      id = 2L,
      title = "Kotlin Code Debugger",
      wakeWord = "code mode",
      iconName = "Code"
    )
    val personaChat = com.example.data.model.ChatSession(
      id = 3L,
      title = "Socratic Tutor",
      wakeWord = "tutor mode",
      greetingMessage = "Welcome! What question shall we explore today?",
      systemInstruction = "You are a Socratic philosopher."
    )

    assertTrue(com.example.ui.components.STICKY_PRESET_CATEGORIES.containsAll(listOf("All", "Assistant", "Code", "Custom Personas")))
    assertTrue(com.example.ui.components.matchesCategoryFilter(assistantChat, "All", null))
    assertTrue(com.example.ui.components.matchesCategoryFilter(assistantChat, "Assistant", null))
    assertTrue(com.example.ui.components.matchesCategoryFilter(codeChat, "Code", "Projects"))
    assertTrue(com.example.ui.components.matchesCategoryFilter(personaChat, "Custom Personas", "Personal"))
    assertTrue(com.example.ui.components.matchesCategoryFilter(codeChat, "Projects", "Projects"))
    assertFalse(com.example.ui.components.matchesCategoryFilter(codeChat, "Research", "Projects"))
  }
}
