package com.example.data.repository

import android.content.Context
import com.example.data.model.PromptTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PromptRepository(context: Context) {

    private val prefs = context.getSharedPreferences("gemini_prompts_pref", Context.MODE_PRIVATE)

    private val defaultPrompts = listOf(
        PromptTemplate(
            id = "default_summarize",
            title = "Summarize",
            promptText = "Please summarize our discussion so far into clear, bulleted takeaways.",
            iconName = "Sparkles",
            isCustom = false
        ),
        PromptTemplate(
            id = "default_action_items",
            title = "Action Items",
            promptText = "Extract all actionable tasks, next steps, and responsibilities mentioned in this thread.",
            iconName = "Lightbulb",
            isCustom = false
        ),
        PromptTemplate(
            id = "default_code_refactor",
            title = "Refactor Code",
            promptText = "Analyze the code and suggest clean refactoring for readability, edge cases, and performance.",
            iconName = "Code",
            isCustom = false
        ),
        PromptTemplate(
            id = "default_explain_simply",
            title = "Explain Simply",
            promptText = "Explain this topic simply with an intuitive real-world analogy (ELI5).",
            iconName = "Chat",
            isCustom = false
        ),
        PromptTemplate(
            id = "default_critique",
            title = "Critique & Improve",
            promptText = "Critique this concept and highlight blind spots, potential risks, and recommendations.",
            iconName = "Sparkles",
            isCustom = false
        ),
        PromptTemplate(
            id = "default_continue",
            title = "Continue",
            promptText = "Please continue elaborating where we left off.",
            iconName = "Chat",
            isCustom = false
        )
    )

    private val _prompts = MutableStateFlow<List<PromptTemplate>>(loadPrompts())
    val prompts: StateFlow<List<PromptTemplate>> = _prompts.asStateFlow()

    private fun loadPrompts(): List<PromptTemplate> {
        val result = mutableListOf<PromptTemplate>()
        result.addAll(defaultPrompts)

        val rawJson = prefs.getString("custom_prompts_json", null) ?: return result
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    PromptTemplate(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        promptText = obj.getString("promptText"),
                        iconName = obj.optString("iconName", "Sparkles"),
                        isCustom = true
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    private fun saveCustomPrompts() {
        val customList = _prompts.value.filter { it.isCustom }
        val array = JSONArray()
        for (item in customList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("promptText", item.promptText)
                put("iconName", item.iconName)
            }
            array.put(obj)
        }
        prefs.edit().putString("custom_prompts_json", array.toString()).apply()
    }

    fun addCustomPrompt(title: String, promptText: String, iconName: String = "Sparkles") {
        val newPrompt = PromptTemplate(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            promptText = promptText.trim(),
            iconName = iconName,
            isCustom = true
        )
        val updated = _prompts.value.toMutableList()
        updated.add(newPrompt)
        _prompts.value = updated
        saveCustomPrompts()
    }

    fun deletePrompt(id: String) {
        val updated = _prompts.value.filter { it.id != id || !it.isCustom }
        _prompts.value = updated
        saveCustomPrompts()
    }
}
