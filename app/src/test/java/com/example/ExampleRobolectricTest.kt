package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.AssistantMode
import com.example.model.PermissionStatus
import com.example.tools.ToolExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches Zoya Assistant`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Zoya Assistant", appName)
    }

    @Test
    fun `tool execution engine handles missing permissions gracefully`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ToolExecutionEngine(context)

        val result = engine.searchAndCallContact("Alex")
        assertNotNull(result)
        // Without granted contacts permission, should return sassy permission request
        assertTrue(result.sassySpokenResponse.isNotEmpty())
    }

    @Test
    fun `assistant mode enum values are correct`() {
        assertEquals(4, AssistantMode.values().size)
        val perms = PermissionStatus(audioRecordGranted = true)
        assertTrue(perms.coreGranted)
    }
}
