package com.jarvis.assistant.data.model.contract

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Standardized uppercase error codes across all LUNA AI tools.
 * Fixes Bug #26 (Inconsistent error handling).
 */
object ToolErrorCodes {
    const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val APP_NOT_FOUND = "APP_NOT_FOUND"
    const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    const val TIMEOUT = "TIMEOUT"
    const val EXECUTION_FAILED = "EXECUTION_FAILED"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
    const val HARDWARE_UNAVAILABLE = "HARDWARE_UNAVAILABLE"
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val FEATURE_NOT_SUPPORTED = "FEATURE_NOT_SUPPORTED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val SERVICE_DISCONNECTED = "SERVICE_DISCONNECTED"
    const val UNKNOWN_ERROR = "UNKNOWN_ERROR"
}

/**
 * Standard structured error envelope.
 */
data class ToolError(
    @SerializedName("code")
    val code: String,

    @SerializedName("message")
    val message: String,

    @SerializedName("recoverable")
    val recoverable: Boolean = true,

    @SerializedName("action")
    val action: String? = null,

    @SerializedName("details")
    val details: Map<String, String>? = null
)

/**
 * Unified canonical Tool Response for all LUNA AI tools.
 * Conforms to FR-JSON-2, FR-JSON-4 and Bug #26 resolution.
 */
data class ToolResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("tool")
    val tool: String,

    @SerializedName("action")
    val action: String? = null,

    @SerializedName("target")
    val target: String? = null,

    @SerializedName("spoken")
    val spoken: String? = null,

    @SerializedName("execution_time_ms")
    val executionTimeMs: Long = 0,

    @SerializedName("verified")
    val verified: Boolean = false,

    @SerializedName("data")
    val data: Map<String, Any?>? = null,

    @SerializedName("error")
    val error: ToolError? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        fun success(
            tool: String,
            action: String? = null,
            target: String? = null,
            spoken: String? = null,
            executionTimeMs: Long = 0,
            verified: Boolean = true,
            data: Map<String, Any?>? = null
        ): ToolResponse = ToolResponse(
            success = true,
            tool = tool,
            action = action,
            target = target,
            spoken = spoken,
            executionTimeMs = executionTimeMs,
            verified = verified,
            data = data,
            error = null
        )

        fun failure(
            tool: String,
            code: String,
            message: String,
            spoken: String? = null,
            action: String? = null,
            recoverable: Boolean = true,
            executionTimeMs: Long = 0,
            details: Map<String, String>? = null
        ): ToolResponse = ToolResponse(
            success = false,
            tool = tool,
            action = action,
            target = null,
            spoken = spoken ?: message,
            executionTimeMs = executionTimeMs,
            verified = false,
            data = null,
            error = ToolError(
                code = code,
                message = message,
                recoverable = recoverable,
                action = action,
                details = details
            )
        )

        fun permissionRequired(
            tool: String,
            permissionName: String,
            spoken: String = "Permission is required to perform this action."
        ): ToolResponse = failure(
            tool = tool,
            code = ToolErrorCodes.PERMISSION_REQUIRED,
            message = "Permission '$permissionName' is required.",
            spoken = spoken,
            action = "request_permission",
            recoverable = true,
            details = mapOf("permission" to permissionName)
        )
    }
}
