package com.jarvis.assistant.iris

import android.content.Context
import com.google.gson.JsonObject
import com.jarvis.assistant.data.model.FunctionDeclarationPayload
import com.jarvis.assistant.data.model.FunctionParametersPayload
import com.jarvis.assistant.data.model.PropertySchemaPayload
import com.jarvis.assistant.data.model.ToolPayload
import com.jarvis.assistant.util.LunaLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for all IRIS-MX tools conforming to Section 4.2 of the PRD.
 * Manages tool registration, produces Gemini Live tool declaration payloads,
 * and executes incoming tool calls with logging, safety, and error handling.
 */
class IrisToolRegistry(private val context: Context) {

    companion object {
        private const val TAG = "IrisToolRegistry"

        @Volatile
        private var instance: IrisToolRegistry? = null

        fun getInstance(context: Context): IrisToolRegistry {
            return instance ?: synchronized(this) {
                instance ?: IrisToolRegistry(context.applicationContext).also {
                    it.registerDefaultTools()
                    instance = it
                }
            }
        }
    }

    private val tools = ConcurrentHashMap<String, IrisTool>()

    fun registerTool(tool: IrisTool) {
        val name = tool.declaration.name
        tools[name] = tool
        LunaLogger.d(TAG, "Registered IRIS tool: $name")
    }

    fun getTool(name: String): IrisTool? = tools[name]

    fun getAllDeclarations(): List<FunctionDeclarationPayload> {
        return tools.values.map { it.declaration }
    }

    fun getToolPayloads(): List<ToolPayload> {
        return listOf(ToolPayload(getAllDeclarations()))
    }

    suspend fun execute(name: String, args: JsonObject): ToolResult {
        val tool = tools[name]
        if (tool == null) {
            LunaLogger.w(TAG, "Tool not found in registry: $name")
            return ToolResult.failure("Unknown tool: $name")
        }

        return try {
            val startTime = System.currentTimeMillis()
            LunaLogger.i(TAG, "Executing IRIS tool: $name with args: $args")
            val result = tool.execute(args)
            val elapsed = System.currentTimeMillis() - startTime
            LunaLogger.i(TAG, "Tool $name completed in ${elapsed}ms: ${result.spoken}")
            result
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Tool execution failed for $name: ${e.message}", e)
            ToolResult.failure("Error executing $name: ${e.message}")
        }
    }

    /**
     * Registers all standard IRIS-MX tools defined in Section 4.2 of the PRD.
     */
    private fun registerDefaultTools() {
        val deviceManager = com.jarvis.assistant.util.DeviceAutomationManager(context)
        val alarmService = com.jarvis.assistant.automation.AlarmService.getInstance(context)
        val navService = com.jarvis.assistant.automation.NavigationService.getInstance(context)
        val autoResponseService = com.jarvis.assistant.automation.AutoResponseService.getInstance(context)
        val deepResearchService = com.jarvis.assistant.network.DeepResearchService.getInstance(context)
        val memoryDb = com.jarvis.assistant.data.memory.LunaMemoryDatabase.get(context)
        val memoryRepo = com.jarvis.assistant.data.memory.MemoryRepository(memoryDb.memoryDao())

        // 1. open_app
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "open_app",
                description = "Launches an installed Android app by its name, or brings it to the foreground if already running.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("app_name" to PropertySchemaPayload("STRING", "Name of the app to launch")),
                    required = listOf("app_name")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val appName = args.get("app_name")?.asString ?: return ToolResult.failure("Missing app_name")
                val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.OpenApp(appName))
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 2. make_phone_call
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "make_phone_call",
                description = "Places a direct phone call to a saved contact name or phone number.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("target" to PropertySchemaPayload("STRING", "Contact name or phone number to call")),
                    required = listOf("target")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val target = args.get("target")?.asString ?: return ToolResult.failure("Missing target")
                val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.PhoneCall(target))
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 3. control_incoming_call
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "control_incoming_call",
                description = "Answers or ends an active phone call, or toggles speakerphone.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("action" to PropertySchemaPayload("STRING", "accept, end, speaker_on, or speaker_off")),
                    required = listOf("action")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val action = args.get("action")?.asString ?: "accept"
                val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.PhoneCallControl(action))
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 4. send_whatsapp_message
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "send_whatsapp_message",
                description = "Sends a WhatsApp text message to a contact and taps send via Ghost accessibility service.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "target" to PropertySchemaPayload("STRING", "Recipient contact name or phone number"),
                        "message" to PropertySchemaPayload("STRING", "Message body to send")
                    ),
                    required = listOf("target", "message")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val target = args.get("target")?.asString ?: return ToolResult.failure("Missing recipient")
                val message = args.get("message")?.asString ?: return ToolResult.failure("Missing message")
                val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.WhatsAppMessage(target, message))
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 5. control_media_playback
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "control_media_playback",
                description = "Controls universal media playback (play, pause, next, previous, stop).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("action" to PropertySchemaPayload("STRING", "play, pause, next, previous, stop")),
                    required = listOf("action")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val action = args.get("action")?.asString ?: "play_pause"
                val cmd = when (action.lowercase()) {
                    "play" -> com.jarvis.assistant.util.DeviceCommand.MediaPlay
                    "pause" -> com.jarvis.assistant.util.DeviceCommand.MediaPause
                    "next" -> com.jarvis.assistant.util.DeviceCommand.MediaNext
                    "previous" -> com.jarvis.assistant.util.DeviceCommand.MediaPrevious
                    "stop" -> com.jarvis.assistant.util.DeviceCommand.MediaStop
                    else -> com.jarvis.assistant.util.DeviceCommand.MediaPlayPause
                }
                val res = deviceManager.executeAsync(cmd)
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 6. set_alarm
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "set_alarm",
                description = "Schedules an alarm clock on the device.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "hour" to PropertySchemaPayload("INTEGER", "Hour in 24-hour format (0-23)"),
                        "minute" to PropertySchemaPayload("INTEGER", "Minute (0-59)"),
                        "message" to PropertySchemaPayload("STRING", "Optional alarm label")
                    ),
                    required = listOf("hour", "minute")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val hour = args.get("hour")?.asInt ?: 7
                val minute = args.get("minute")?.asInt ?: 0
                val message = args.get("message")?.asString
                val res = alarmService.scheduleAlarm(hour, minute, false, message)
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 7. set_timer
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "set_timer",
                description = "Sets a countdown timer on the device.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "duration_seconds" to PropertySchemaPayload("INTEGER", "Timer duration in seconds"),
                        "message" to PropertySchemaPayload("STRING", "Optional timer label")
                    ),
                    required = listOf("duration_seconds")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val duration = args.get("duration_seconds")?.asInt ?: 60
                val message = args.get("message")?.asString
                val res = alarmService.scheduleTimer(duration, message)
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 8. open_deep_link
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "open_deep_link",
                description = "Opens a deep link URI or web URL (YouTube, Spotify, Maps, geo URIs, web).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("uri" to PropertySchemaPayload("STRING", "Deep link URI or web URL")),
                    required = listOf("uri")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val uriStr = args.get("uri")?.asString ?: return ToolResult.failure("Missing URI")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uriStr)).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return try {
                    context.startActivity(intent)
                    ToolResult.success("Opened $uriStr")
                } catch (e: Exception) {
                    ToolResult.failure("Failed to open link: ${e.message}")
                }
            }
        })

        // 9. web_search
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "web_search",
                description = "Performs real-time web search and deep research for live news, sports matches, prices, and facts.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("query" to PropertySchemaPayload("STRING", "Search query")),
                    required = listOf("query")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val q = args.get("query")?.asString ?: return ToolResult.failure("Missing query")
                var answer: String? = null
                var err: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                deepResearchService.startResearch(
                    userQuery = q,
                    onComplete = { output ->
                        answer = output.finalAnswer
                        latch.countDown()
                    },
                    onError = { e ->
                        err = e
                        latch.countDown()
                    }
                )

                latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                return if (answer != null) {
                    ToolResult.success(answer!!)
                } else {
                    ToolResult.failure(err ?: "Web search timed out.")
                }
            }
        })

        // 10. get_device_info
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "get_device_info",
                description = "Gets current device status including battery percentage, connectivity, and storage.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.GetDeviceStatus("all"))
                return ToolResult.success(res.spoken)
            }
        })

        // 11. flashlight
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "flashlight",
                description = "Turns device flashlight / torch on or off.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("enabled" to PropertySchemaPayload("BOOLEAN", "true to turn on, false to turn off")),
                    required = listOf("enabled")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val enabled = args.get("enabled")?.asBoolean ?: true
                val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.Torch(enabled))
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 12. navigate_to
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "navigate_to",
                description = "Starts Google Maps navigation from device GPS location to destination.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("destination" to PropertySchemaPayload("STRING", "Destination name or address")),
                    required = listOf("destination")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val dest = args.get("destination")?.asString ?: return ToolResult.failure("Missing destination")
                val res = navService.startNavigation(dest)
                return if (res.success) ToolResult.success(res.spoken) else ToolResult.failure(res.spoken)
            }
        })

        // 13. save_core_memory & wipe_memory
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "save_core_memory",
                description = "Saves user personal preference, routine, or core memory into persistent storage.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf("fact" to PropertySchemaPayload("STRING", "The fact or preference to remember")),
                    required = listOf("fact")
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val fact = args.get("fact")?.asString ?: return ToolResult.failure("Missing fact")
                val res = memoryRepo.remember(fact)
                return res.fold(
                    onSuccess = { id -> ToolResult.success("Remembered fact (ID $id).") },
                    onFailure = { e -> ToolResult.failure("Failed to save memory: ${e.message}") }
                )
            }
        })

        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "wipe_memory",
                description = "Wipes all saved core memories permanently upon user request.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                return try {
                    memoryRepo.clear()
                    ToolResult.success("All saved memories have been wiped.")
                } catch (e: Exception) {
                    ToolResult.failure("Failed to clear memories: ${e.message}")
                }
            }
        })

        // 14. manage_notification_listener
        registerTool(object : IrisTool {
            override val declaration = FunctionDeclarationPayload(
                name = "manage_notification_listener",
                description = "Controls automated replies to incoming notifications or reads notifications.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "auto_reply_enabled" to PropertySchemaPayload("BOOLEAN", "Enable or disable auto-reply"),
                        "reply_message" to PropertySchemaPayload("STRING", "Custom reply message")
                    )
                )
            )
            override suspend fun execute(args: JsonObject): ToolResult {
                val enable = args.get("auto_reply_enabled")?.asBoolean
                val msg = args.get("reply_message")?.asString
                return if (enable != null) {
                    val res = autoResponseService.setAutoResponse(enable, msg)
                    ToolResult.success(res.spoken)
                } else {
                    val res = deviceManager.executeAsync(com.jarvis.assistant.util.DeviceCommand.ReadNotifications(null))
                    ToolResult.success(res.spoken)
                }
            }
        })
    }
}
