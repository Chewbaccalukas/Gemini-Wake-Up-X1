package com.example.data.model

data class PromptTemplate(
    val id: String,
    val title: String,
    val promptText: String,
    val iconName: String = "Sparkles", // Sparkles, Summary, Code, Lightbulb, Chat, Check
    val isCustom: Boolean = false
)
