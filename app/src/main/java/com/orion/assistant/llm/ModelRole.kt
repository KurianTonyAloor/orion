package com.orion.assistant.llm

enum class ModelRole {
    PRIMARY_REASONER, // 1.5B - 3B high-level planning & knowledge
    SCREEN_NAVIGATOR  // 0.5B ultra-fast on-screen perception & UI interaction
}
