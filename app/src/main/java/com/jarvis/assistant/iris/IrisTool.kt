package com.jarvis.assistant.iris

import com.google.gson.JsonObject
import com.jarvis.assistant.data.model.FunctionDeclarationPayload

/**
 * Standardized spoken-friendly result returned by an IrisTool execution.
 */
data class ToolResult(
    val success: Boolean,
    val spoken: String,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null
) {
    companion object {
        fun success(spoken: String, data: Map<String, Any?> = emptyMap()): ToolResult =
            ToolResult(success = true, spoken = spoken, data = data)

        fun failure(spoken: String, error: String? = null): ToolResult =
            ToolResult(success = false, spoken = spoken, error = error ?: spoken)
    }
}

/**
 * Unified IRIS-MX Tool Contract conforming to Section 4.2 of the PRD.
 * Every tool exposes its Gemini FunctionDeclaration and executes args asynchronously.
 */
interface IrisTool {
    val declaration: FunctionDeclarationPayload
    suspend fun execute(args: JsonObject): ToolResult
}
