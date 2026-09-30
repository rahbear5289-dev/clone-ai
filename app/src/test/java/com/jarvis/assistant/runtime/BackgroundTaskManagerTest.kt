package com.jarvis.assistant.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [BackgroundTaskManager] — FR-BG-1 (task states), FR-BG-2 (background execution),
 * FR-BG-3 (no orphan workers or crashes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTaskManagerTest {

    @Before
    fun setup() {
        BackgroundTaskManager.start()
    }

    @After
    fun tearDown() {
        BackgroundTaskManager.shutdown()
    }

    @Test
    fun `submitted task transitions from queued to completed`() = runTest(UnconfinedTestDispatcher()) {
        val taskId = BackgroundTaskManager.submit("test:simple") { _ ->
            delay(10)
            "result"
        }

        assertNotNull("Task ID should not be null", taskId)
        delay(200)

        val task = BackgroundTaskManager.getTask(taskId)
        assertNotNull(task)
        assertEquals(TaskState.COMPLETED, task!!.state)
        assertEquals("result", task.result)
    }

    @Test
    fun `task reports progress updates`() = runTest(UnconfinedTestDispatcher()) {
        val taskId = BackgroundTaskManager.submit("test:progress") { updateProgress ->
            updateProgress(0.25f)
            delay(10)
            updateProgress(0.75f)
            delay(10)
            updateProgress(1.0f)
        }

        delay(200)

        val task = BackgroundTaskManager.getTask(taskId)
        assertNotNull(task)
        assertEquals(TaskState.COMPLETED, task!!.state)
    }

    @Test
    fun `failed task transitions to FAILED state`() = runTest(UnconfinedTestDispatcher()) {
        val taskId = BackgroundTaskManager.submit("test:failing") { _ ->
            delay(10)
            throw RuntimeException("Simulated test failure")
        }

        delay(200)

        val task = BackgroundTaskManager.getTask(taskId)
        assertNotNull(task)
        assertEquals(TaskState.FAILED, task!!.state)
        assertNotNull("Error message should be set", task.errorMessage)
        assertTrue(task.errorMessage!!.contains("Simulated test failure"))
    }

    @Test
    fun `cancelled task transitions to CANCELLED state`() = runTest(UnconfinedTestDispatcher()) {
        val taskId = BackgroundTaskManager.submit("test:long_running", timeoutMs = 30_000L) { _ ->
            delay(10_000L) // Long delay to allow cancellation
        }

        delay(50)
        val cancelled = BackgroundTaskManager.cancel(taskId)
        assertTrue("Cancel should return true", cancelled)

        delay(100)

        val task = BackgroundTaskManager.getTask(taskId)
        assertNotNull(task)
        assertEquals(TaskState.CANCELLED, task!!.state)
    }

    @Test
    fun `timed out task transitions to FAILED state`() = runTest(UnconfinedTestDispatcher()) {
        val taskId = BackgroundTaskManager.submit("test:timeout", timeoutMs = 50L) { _ ->
            delay(500L) // Exceeds timeout
        }

        delay(300)

        val task = BackgroundTaskManager.getTask(taskId)
        assertNotNull(task)
        assertEquals(TaskState.FAILED, task!!.state)
        assertNotNull(task.errorMessage)
        assertTrue("Error should mention timeout", task.errorMessage!!.lowercase().contains("timeout"))
    }

    @Test
    fun `multiple concurrent tasks run independently`() = runTest(UnconfinedTestDispatcher()) {
        val ids = (1..5).map { i ->
            BackgroundTaskManager.submit("test:concurrent:$i") { _ ->
                delay(50)
                "result_$i"
            }
        }

        delay(500)

        val tasks = ids.mapNotNull { BackgroundTaskManager.getTask(it) }
        assertEquals(5, tasks.size)
        tasks.forEach { task ->
            assertEquals("All tasks should complete", TaskState.COMPLETED, task.state)
        }
    }

    @Test
    fun `cancelAll stops all active workers`() = runTest(UnconfinedTestDispatcher()) {
        repeat(3) { i ->
            BackgroundTaskManager.submit("test:bulk:$i", timeoutMs = 30_000L) { _ ->
                delay(10_000L)
            }
        }

        delay(50)
        BackgroundTaskManager.cancelAll()
        delay(100)

        // After cancelAll, no new tasks should be running (taskJobs is cleared)
        // Health should still be healthy (manager still alive)
        val health = BackgroundTaskManager.health()
        assertEquals(0, health.details["activeWorkers"] as Int)
    }

    @Test
    fun `health check reports ACTIVE status`() {
        val health = BackgroundTaskManager.health()
        assertTrue("BackgroundTaskManager should be healthy", health.healthy)
        assertEquals("ACTIVE", health.status)
        assertEquals("BackgroundTaskManager", health.component)
    }

    @Test
    fun `listTasks returns tracked tasks`() = runTest(UnconfinedTestDispatcher()) {
        BackgroundTaskManager.submit("test:list") { _ -> delay(10) }
        delay(200)

        val tasks = BackgroundTaskManager.listTasks()
        assertTrue("listTasks should return at least one task", tasks.isNotEmpty())
    }
}
