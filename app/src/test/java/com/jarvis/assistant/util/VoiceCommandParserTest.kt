package com.jarvis.assistant.util

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun testParseSimpleAppLaunch() {
        val cmd = VoiceCommandParser.parse("Open Chrome")
        assertNotNull(cmd)
        assertTrue(cmd is DeviceCommand.OpenApp)
        assertEquals("chrome", (cmd as DeviceCommand.OpenApp).appName.lowercase())
    }

    @Test
    fun testParseYouTubePlay() {
        val cmd = VoiceCommandParser.parse("Play MrBeast latest video on YouTube")
        assertNotNull(cmd)
        assertTrue(cmd is DeviceCommand.YouTubePlay)
    }

    @Test
    fun testParseTorch() {
        val cmdOn = VoiceCommandParser.parse("Turn on torch")
        assertNotNull(cmdOn)
        assertTrue(cmdOn is DeviceCommand.Torch)
        assertTrue((cmdOn as DeviceCommand.Torch).on)

        val cmdOff = VoiceCommandParser.parse("Torch off karo")
        assertNotNull(cmdOff)
        assertTrue(cmdOff is DeviceCommand.Torch)
        assertFalse((cmdOff as DeviceCommand.Torch).on)
    }

    @Test
    fun testParseHindiHinglishCommand() {
        val cmd = VoiceCommandParser.parse("Camera kholo")
        assertNotNull(cmd)
        assertTrue(cmd is DeviceCommand.OpenApp)
        assertEquals("camera", (cmd as DeviceCommand.OpenApp).appName.lowercase())
    }

    @Test
    fun testParseCancelCommand() {
        val cmd = VoiceCommandParser.parse("ruk jao")
        assertNotNull(cmd)
        assertTrue(cmd is DeviceCommand.CancelTask)
    }
}
