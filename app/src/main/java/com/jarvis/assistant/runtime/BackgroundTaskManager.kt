package com.jarvis.assistant.runtime

import com.jarvis.assistant.data.model.contract.LunaEvent
import com.jarvis.assistant.data.model.contract.LunaEventTypes
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Canonical task execution states conforming to FR-BG-1.
 */
enum class TaskState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Representation of a tracked background execution task.
 */
data class BackgroundTask(
    val id: String,
    val name: String,
    val state: TaskState,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val progress: Float = 0f,
    val result: Any? = null,
    val errorMessage: String? = null
)

/**
 * Background Task Manager conforming to FR-BG-1, FR-BG-2, and FR-BG-3.
 * Prevents UI thread blocking, handles timeouts, supports cooperative cancellation,
 * and maintains task state lifecycle without orphan coroutines or worker leaks.
 */
object BackgroundTaskManager : LunaLifecycle {
    private const val TAG = "BackgroundTaskManager"

    private var managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeTasks = ConcurrentHashMap<String, BackgroundTask>()
    private val taskJobs = ConcurrentHashMap<String, Job>()
    private val taskCounter = AtomicLong(0)

    @Volatile
    private var isRunning = true

    /**
     * Submit a background operation with an optional timeout.
     */
    fun submit(
        name: String,
        timeoutMs: Long = 60_000L,
        taskId: String = "task_${System.currentTimeMillis()}_${taskCounter.incrementAndGet()}",
        block: suspend (updateProgress: (Float) -> Unit) -> Any?
    ): String {
        if (!isRunning) {
            LunaLogger.w(TAG, "TaskManager stopped, cannot accept task: $name")
            return ""
        }

        val initialTask = BackgroundTask(
            id = taskId,
            name = name,
            state = TaskState.QUEUED
        )
        activeTasks[taskId] = initialTask
        LunaLogger.i(TAG, "Task submitted: $name [id=$taskId]")

        val job = managerScope.launch {
            try {
                // Update to RUNNING
                val runningTask = initialTask.copy(
                    state = TaskState.RUNNING,
                    startedAt = System.currentTimeMillis()
                )
                activeTasks[taskId] = runningTask

                LunaEventBus.publish(
                    LunaEvent(
                        type = LunaEventTypes.TOOL_STARTED,
                        payload = mapOf(
                            "taskId" to taskId,
                            "name" to name,
                            "state" to TaskState.RUNNING.name
                        )
                    )
                )

                // Execute with timeout
                val result = withTimeout(timeoutMs) {
                    block { progress ->
                        activeTasks[taskId]?.let { current ->
                            activeTasks[taskId] = current.copy(progress = progress.coerceIn(0f, 1f))
                        }
                    }
                }

                // Completed
                val completedTask = (activeTasks[taskId] ?: runningTask).copy(
                    state = TaskState.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    progress = 1.0f,
                    result = result
                )
                activeTasks[taskId] = completedTask

                LunaEventBus.publish(
                    LunaEvent(
                        type = LunaEventTypes.TOOL_COMPLETED,
                        payload = mapOf(
                            "taskId" to taskId,
                            "name" to name,
                            "state" to TaskState.COMPLETED.name,
                            "success" to true
                        )
                    )
                )
                LunaLogger.i(TAG, "Task completed successfully: $name [id=$taskId]")

            } catch (e: TimeoutCancellationException) {
                LunaLogger.w(TAG, "Task timed out after ${timeoutMs}ms: $name [id=$taskId]")
                val failedTask = (activeTasks[taskId] ?: initialTask).copy(
                    state = TaskState.FAILED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = "Task timeout: Operation timed out after ${timeoutMs}ms"
                )
                activeTasks[taskId] = failedTask

                LunaEventBus.publish(
                    LunaEvent(
                        type = LunaEventTypes.TOOL_COMPLETED,
                        payload = mapOf(
                            "taskId" to taskId,
                            "name" to name,
                            "state" to TaskState.FAILED.name,
                            "error" to "TIMEOUT"
                        )
                    )
                )
            } catch (e: CancellationException) {
                LunaLogger.i(TAG, "Task was cancelled: $name [id=$taskId]")
                val cancelledTask = (activeTasks[taskId] ?: initialTask).copy(
                    state = TaskState.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = "Task cancelled by user or system"
                )
                activeTasks[taskId] = cancelledTask
            } catch (e: Exception) {
                LunaLogger.e(TAG, "Task failed: $name [id=$taskId]: ${e.message}", e)
                val failedTask = (activeTasks[taskId] ?: initialTask).copy(
                    state = TaskState.FAILED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = e.message ?: "Unknown task execution error"
                )
                activeTasks[taskId] = failedTask

                LunaEventBus.publish(
                    LunaEvent(
                        type = LunaEventTypes.TOOL_COMPLETED,
                        payload = mapOf(
                            "taskId" to taskId,
                            "name" to name,
                            "state" to TaskState.FAILED.name,
                            "error" to (e.message ?: "EXECUTION_ERROR")
                        )
                    )
                )
            } finally {
                taskJobs.remove(taskId)
            }
        }

        taskJobs[taskId] = job
        return taskId
    }

    /**
     * Cancel an ongoing task.
     */
    fun cancel(taskId: String): Boolean {
        val job = taskJobs.remove(taskId)
        return if (job != null && job.isActive) {
            job.cancel()
            activeTasks[taskId]?.let {
                activeTasks[taskId] = it.copy(
                    state = TaskState.CANCELLED,
                    completedAt = System.currentTimeMillis()
                )
            }
            LunaLogger.i(TAG, "Cancelled task: $taskId")
            true
        } else {
            false
        }
    }

    /**
     * Get a snapshot of a task by ID.
     */
    fun getTask(taskId: String): BackgroundTask? = activeTasks[taskId]

    /**
     * List all currently tracked tasks.
     */
    fun listTasks(): List<BackgroundTask> = activeTasks.values.toList()

    /**
     * Cancel all active tasks and clean up workers (prevents orphan processes).
     */
    fun cancelAll() {
        LunaLogger.i(TAG, "Cancelling all ${taskJobs.size} active background tasks")
        taskJobs.values.forEach { it.cancel() }
        taskJobs.clear()
    }

    // -------------------------------------------------------------------------
    // LunaLifecycle Implementation
    // -------------------------------------------------------------------------

    override fun start() {
        if (!managerScope.isActive) {
            managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        isRunning = true
        LunaLogger.i(TAG, "BackgroundTaskManager started")
    }

    override fun pause() {
        LunaLogger.d(TAG, "BackgroundTaskManager paused")
    }

    override fun resume() {
        LunaLogger.d(TAG, "BackgroundTaskManager resumed")
    }

    override fun stop() {
        cancelAll()
        LunaLogger.i(TAG, "BackgroundTaskManager stopped")
    }

    override fun health(): HealthStatus {
        val activeCount = taskJobs.values.count { it.isActive }
        return HealthStatus(
            component = "BackgroundTaskManager",
            healthy = isRunning && managerScope.isActive,
            status = if (isRunning) "ACTIVE" else "STOPPED",
            details = mapOf(
                "activeWorkers" to activeCount,
                "totalTasksSubmitted" to taskCounter.get(),
                "trackedTaskCount" to activeTasks.size
            )
        )
    }

    override fun shutdown() {
        isRunning = false
        cancelAll()
        activeTasks.clear()
        managerScope.cancel()
        LunaLogger.i(TAG, "BackgroundTaskManager permanently shutdown")
    }
}
