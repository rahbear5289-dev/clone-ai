package com.jarvis.assistant.util

import java.util.Locale

/**
 * Turns spoken text into a [DeviceCommand].
 * Optimized for Hindi, Hinglish, and English voice commands.
 */
object VoiceCommandParser {

    fun parse(raw: String): DeviceCommand? {
        val text = raw.lowercase(Locale.ROOT)
            .replace(Regex("[,;!?:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return null

        parseCancel(text)?.let { return it }
        parseCamera(text)?.let { return it }
        parseScreen(text)?.let { return it }
        parseNotifications(text)?.let { return it }
        parseCoding(text)?.let { return it }
        parseDeviceStatus(text)?.let { return it }
        parseTorchVolumeBrightness(text)?.let { return it }
        parseConnectivity(text)?.let { return it }
        parseAlarmReminder(text)?.let { return it }
        parseWhatsApp(text)?.let { return it }
        parseYouTube(text)?.let { return it }
        parseSpotify(text)?.let { return it }
        parseMediaControls(text)?.let { return it }
        parseCall(text)?.let { return it }
        parseInstall(text)?.let { return it }
        parseBrowser(text)?.let { return it }
        parseMultiWebsites(text)?.let { return it }
        parseBulkApps(text)?.let { return it }
        parseNav(text)?.let { return it }
        parseWebSearch(text)?.let { return it }
        parseOpenClose(text)?.let { return it }
        return null
    }

    fun parseSequence(raw: String): List<DeviceCommand> {
        val pieces = raw.split(Regex("\\s+(?:aur phir|and then|then|phir|aur|and)\\s+|\\s*,\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (pieces.size <= 1) {
            val one = parse(raw)
            return if (one != null) listOf(one) else emptyList()
        }
        val out = ArrayList<DeviceCommand>()
        for (p in pieces) {
            parse(p)?.let { out.add(it) }
        }
        return out
    }

    private fun parseCancel(text: String): DeviceCommand? {
        if (Regex("^(?:stop|cancel|ruk jao|ruk ja|task cancel karo|abhi jo kar rahe ho band karo)$").matches(text) ||
            text == "cancel task" || text == "stop task" || text == "ruk jao"
        ) return DeviceCommand.CancelTask
        return null
    }

    private fun parseScreen(text: String): DeviceCommand? {
        if (text.contains("screen visualization off") || text.contains("screen dekhna band") ||
            text == "screen off karo" || text.contains("stop looking at my screen") ||
            text.contains("screen visualization band")
        ) return DeviceCommand.ScreenOff

        if (text.contains("kya likha") || text.contains("text dikh raha") ||
            text.contains("read the screen") || text.contains("screen par kya likha") ||
            text.contains("is page par kya likha") || text.contains("top par kya likha") ||
            text.contains("ye button kya bol") || text.contains("ocr")
        ) return DeviceCommand.ScreenOcr

        if (text.contains("product") && (text.contains("screen dekho") || text.contains("ye product kaisa"))) {
            return DeviceCommand.ScreenAnalyze("Analyze visible product on screen: name, brand, price, rating, and key specs. Distinguish visible from researched info.")
        }

        if (text.contains("screen dekho aur jo video") || text.contains("video chal raha hai usko play")) {
            return DeviceCommand.ScreenAnalyze("Identify the video player visible on screen and start playback.")
        }

        if (text.contains("meri screen dekho") || text.contains("meri feed dekho") ||
            text.contains("mere screen par kya") || text.contains("screen pe kya") ||
            text.contains("screen par kya chal") || text.contains("look at my screen") ||
            text.contains("what is on my screen") || text.contains("screen dekho")
        ) {
            val prompt = "Describe what is currently visible on this phone screen. Mention the current app, main content, visible buttons, or playing media."
            return DeviceCommand.ScreenAnalyze(prompt)
        }
        return null
    }

    private fun parseCoding(text: String): DeviceCommand? {
        if (text.contains("notepad kholo aur") || text.contains("code editor kholo aur") ||
            (text.contains("code likho") && (text.contains("notepad") || text.contains("editor")))
        ) {
            val editor = if (text.contains("notepad")) "notepad" else "editor"
            val prompt = text.replace(Regex(".*(?:notepad|editor) (?:kholo aur |pe )?"), "").trim()
            return DeviceCommand.CodeAutomation(prompt, editor)
        }
        if (text.contains("code likho") || text.contains("mein program") || text.contains("mein calculator ka code") ||
            (text.contains("mein") && (text.contains("python") || text.contains("react") || text.contains("javascript") || text.contains("html")))
        ) {
            return DeviceCommand.CodeAutomation(text)
        }
        return null
    }

    private fun parseNotifications(text: String): DeviceCommand? {
        if (text.contains("message kya aaya") || text.contains("kisne message") ||
            text.contains("notification") || text.contains("whatsapp ka message read") ||
            text.contains("read my messages") || text.contains("instagram notification")
        ) {
            val hint = when {
                text.contains("whatsapp") -> "whatsapp"
                text.contains("instagram") -> "instagram"
                text.contains("telegram") -> "telegram"
                text.contains("gmail") -> "gmail"
                else -> null
            }
            return DeviceCommand.ReadNotifications(hint)
        }
        return null
    }

    private fun parseDeviceStatus(text: String): DeviceCommand? {
        if (text.contains("battery") || text.contains("charging hai") || text.contains("charge ho")) {
            return DeviceCommand.BatteryStatus
        }
        if (text.contains("weather") || text.contains("mausam")) return DeviceCommand.WeatherNow
        if (text.contains("location") || text.contains("kahan hoon") || text.contains("kahan hun") ||
            text.contains("main kahan") || text.contains("device ki location")
        ) return DeviceCommand.LocationNow
        if (text.contains("kal ki date") || text.contains("aaj ki date") || text == "date batao" || text.contains("date kya")) {
            return DeviceCommand.DateNow
        }
        if (text.contains("time") || text.contains("kitna baja") || text == "abhi time kya hai") {
            return DeviceCommand.TimeNow
        }
        return null
    }

    private fun parseTorchVolumeBrightness(text: String): DeviceCommand? {
        if (text.contains("torch") || text.contains("flashlight") || text.contains("flash light")) {
            val off = text.contains("off") || text.contains("band")
            return DeviceCommand.Torch(!off)
        }
        Regex("(?:volume|awaaz) (?:ko )?(?:set |karo )?(\\d+)").find(text)?.let {
            return DeviceCommand.VolumeSet(it.groupValues[1].toIntOrNull())
        }
        if (text.contains("volume mute") || (text.contains("silent karo") && text.contains("volume"))) {
            return DeviceCommand.VolumeMute
        }
        if (text.contains("volume badhao") || text.contains("volume up") || text.contains("awaaz badhao")) {
            return DeviceCommand.VolumeAdjust(true)
        }
        if (text.contains("volume kam") || text.contains("volume down") || text.contains("awaaz kam")) {
            return DeviceCommand.VolumeAdjust(false)
        }
        if (text.contains("volume full") || text.contains("media volume full")) {
            return DeviceCommand.VolumeSet(100)
        }
        Regex("brightness (?:ko )?(?:set |karo )?(\\d+)").find(text)?.let {
            return DeviceCommand.BrightnessSet(it.groupValues[1].toIntOrNull())
        }
        if (text.contains("brightness badhao") || text.contains("brightness up")) {
            return DeviceCommand.BrightnessAdjust(true)
        }
        if (text.contains("brightness kam") || text.contains("brightness down")) {
            return DeviceCommand.BrightnessAdjust(false)
        }
        if (text.contains("brightness full") || text.contains("screen brightness full")) {
            return DeviceCommand.BrightnessSet(100)
        }
        return null
    }

    private fun parseConnectivity(text: String): DeviceCommand? {
        if (text.contains("wi-fi") || text.contains("wifi")) {
            return DeviceCommand.OpenSettingsPanel("wifi")
        }
        if (text.contains("bluetooth")) return DeviceCommand.OpenSettingsPanel("bluetooth")
        if (text.contains("mobile data") || text.contains("cellular")) {
            return DeviceCommand.OpenSettingsPanel("data")
        }
        if (text.contains("do not disturb") || text.contains("dnd")) {
            return DeviceCommand.OpenSettingsPanel("dnd")
        }
        return null
    }

    private fun parseAlarmReminder(text: String): DeviceCommand? {
        if (text.contains("alarm hata") || text.contains("cancel alarm") || text.contains("saare alarms")) {
            return DeviceCommand.OpenSettingsPanel("alarms")
        }

        // 1. Timer Parsing (Countdown)
        if (text.contains("timer") || text.contains("countdown") || text.contains("ghante ka timer") || text.contains("minute ka timer")) {
            val duration = parseDurationToSeconds(text)
            if (duration != null) {
                val msg = text.replace(Regex(".*(timer|countdown)\\s*(lagao|set karo|set|laga do)?\\s*"), "").trim()
                return DeviceCommand.SetTimer(duration, msg.ifBlank { null })
            }
        }

        // 2. Alarm Parsing (Absolute Time)
        val alarm = Regex("(?:(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?)?.*alarm").containsMatchIn(text) ||
            text.contains("alarm lagao") || text.contains("alarm set") || text.contains("utha dena") || text.contains("wake me up")
        if (alarm && (text.contains("alarm") || text.contains("utha") || text.contains("wake"))) {
            val parsed = parseClock(text)
            if (parsed != null) {
                return DeviceCommand.SetAlarm(
                    parsed.first, parsed.second,
                    tomorrow = text.contains("kal") || text.contains("tomorrow"),
                    recurring = text.contains("roz") || text.contains("everyday") || text.contains("daily"),
                    message = null
                )
            }
        }

        // 3. Reminder Parsing (Absolute Time + Task)
        if (text.contains("reminder") || text.contains("yaad dilana") || text.contains("yaad dila")) {
            val parsed = parseClock(text)
            val mins = parseDurationToSeconds(text)
            val msg = text.replace(Regex(".*(reminder|yaad dilana|yaad dila)\\s*(karo|lagao|set karo|laga do)?\\s*"), "").trim()
            return DeviceCommand.SetReminder(msg.ifBlank { "Reminder" }, parsed?.first, parsed?.second, if (mins != null) mins / 60 else null)
        }
        return null
    }

    private fun parseDurationToSeconds(text: String): Int? {
        var totalSeconds = 0
        var found = false

        // Parse hours
        Regex("(\\d+)\\s*(?:hour|ghante|ghanta)").find(text)?.let {
            totalSeconds += it.groupValues[1].toIntOrNull()?.times(3600) ?: 0
            found = true
        }
        // Parse minutes
        Regex("(\\d+)\\s*(?:minute|min|minutes)").find(text)?.let {
            totalSeconds += it.groupValues[1].toIntOrNull()?.times(60) ?: 0
            found = true
        }
        // Parse seconds
        Regex("(\\d+)\\s*(?:second|sec|seconds)").find(text)?.let {
            totalSeconds += it.groupValues[1].toIntOrNull() ?: 0
            found = true
        }

        return if (found) totalSeconds else null
    }

    private fun parseClock(text: String): Pair<Int, Int>? {
        Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?").find(text)?.let {
            var h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            val ap = it.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return h to m
        }
        Regex("(\\d{1,2})\\s*(am|pm)").find(text)?.let {
            var h = it.groupValues[1].toInt()
            val ap = it.groupValues[2]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return h to 0
        }
        Regex("(\\d{1,2})\\s*baje").find(text)?.let {
            return it.groupValues[1].toInt() to 0
        }
        return null
    }

    private fun parseWhatsApp(text: String): DeviceCommand? {
        if (text.contains("whatsapp") && (text.contains("first video") || text.contains("pehla video") || text.contains("ka first video"))) {
            return DeviceCommand.WhatsAppFirstVideo
        }
        Regex("(?:whatsapp|whats\\s*app) (?:par|pe|me|mai|per) (.+?) ko (?:message|msg|text) (?:bhejo|karo|bhej\\s*do|send\\s*karo) (?:ki |saying |with |: )?(.+)").find(text)?.let {
            return DeviceCommand.WhatsAppMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("(.+?) ko (?:whatsapp|whats\\s*app) (?:par|pe|me|mai|per) (?:message|msg|text) (?:bhejo|karo|bhej\\s*do|send\\s*karo) (?:ki |saying |with |: )?(.+)").find(text)?.let {
            return DeviceCommand.WhatsAppMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("(.+?) ko (?:whatsapp|whats\\s*app) (?:message )?(?:karo|bhejo|kardo)(?:\\s*:\\s*|\\s+)?(.+)").find(text)?.let {
            val rest = it.groupValues[2].trim()
            if (rest.isNotEmpty() && rest !in listOf("kholo", "open")) {
                return DeviceCommand.WhatsAppMessage(it.groupValues[1].trim(), rest)
            }
        }
        Regex("send (?:a |an )?(?:whatsapp )?(?:message|msg|text) to (.+?) (?:saying|that says|which says|and say|with (?:the )?(?:message|text)|with|:) (.+)").find(text)?.let {
            return DeviceCommand.WhatsAppMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("(?:whatsapp|whats\\s*app) (?:par|pe|me|mai)? ?(voice|audio|video)? ?call (?:to |karo )?(.+)").find(text)?.let {
            val isVideo = it.groupValues[1] == "video" || text.contains("video")
            val target = it.groupValues[2].replace(Regex("\\b(karo|kar do)\\b"), "").trim()
            if (target.isNotBlank()) return DeviceCommand.WhatsAppCall(target, isVideo)
        }
        Regex("open (?:whatsapp )?chat (?:with|of|for) (.+)").find(text)?.let {
            return DeviceCommand.WhatsAppOpenChat(it.groupValues[1].trim())
        }
        Regex("(?:whatsapp|whats\\s*app) (?:par|pe|me|mai|per) (.+?) (?:se chat karo|ki chat kholo|chat kholo)").find(text)?.let {
            return DeviceCommand.WhatsAppOpenChat(it.groupValues[1].trim())
        }
        return null
    }

    private fun parseYouTube(text: String): DeviceCommand? {
        if (text.contains("second video play") || text.contains("doosra video play") || text.contains("second video chalao")) {
            return DeviceCommand.PlayNthVideo(1)
        }
        if ((text.contains("pehla video") || text.contains("first video") || text.contains("ye wala video")) &&
            (text.contains("play") || text.contains("chalao") || !text.contains("search"))
        ) {
            return DeviceCommand.PlayFirstVideo
        }
        Regex("youtube (?:par|pe|me|mai|per) (.+?) (?:search karo|search kar do|search|dhoondo|dekho)").find(text)?.let {
            val query = it.groupValues[1].trim()
            if (query.isNotEmpty()) return DeviceCommand.YouTubeSearch(query)
        }
        Regex("youtube (?:par|pe|me|mai|per) (?:search karo|search kar do|search|dhoondo) (.+)").find(text)?.let {
            val query = it.groupValues[1].trim()
            if (query.isNotEmpty()) return DeviceCommand.YouTubeSearch(query)
        }
        val searchPatterns = listOf(
            Regex("search (?:on |in |pe |par )?youtube (?:for |about |pe |par )?(.+)"),
            Regex("search for (.+) on youtube"),
            Regex("open youtube and search (?:for |about )?(.+)"),
            Regex("youtube search (?:for )?(.+)")
        )
        for (pattern in searchPatterns) {
            pattern.find(text)?.let {
                val query = it.groupValues[1].replace(Regex("\\b(karo|kar do)\\b"), "").trim()
                if (query.isNotEmpty()) return DeviceCommand.YouTubeSearch(query)
            }
        }
        val playPatterns = listOf(
            Regex("play (.+) (?:on|in|pe|par) youtube"),
            Regex("on youtube play (.+)"),
            Regex("youtube (?:pe|par|me|mai|per) (.+?) (?:chalao|play karo|chala do|play)")
        )
        for (pattern in playPatterns) {
            pattern.find(text)?.let {
                val query = it.groupValues[1].trim()
                if (query.isNotEmpty()) return DeviceCommand.YouTubePlay(query)
            }
        }
        if (text.contains("speed") && (text.contains("karo") || text.contains("x") || text.contains("slow") || text.contains("normal"))) {
            val label = Regex("(\\d(?:\\.\\d)?x|slow|normal|1\\.5x?|2x?)").find(text)?.value ?: "normal"
            return DeviceCommand.MediaSpeed(label)
        }
        if (text.contains("quality") || text.contains("1080") || text.contains("720") || text.contains("480")) {
            val q = Regex("(1080p?|720p?|480p?|low|high)").find(text)?.value ?: "high"
            return DeviceCommand.MediaQuality(q)
        }
        return null
    }

    private fun parseSpotify(text: String): DeviceCommand? {
        if (text.contains("random music") || text.contains("random song") || text.contains("random gana") ||
            text.contains("koi gaana chalao") || text.contains("koi gana chalao")
        ) {
            return DeviceCommand.RandomMusic
        }
        val mood = when {
            text.contains("peaceful") || text.contains("sukoon") -> "peaceful music"
            text.contains("love song") || text.contains("romantic") -> "love songs"
            text.contains("old song") || text.contains("purane gane") -> "old songs"
            text.contains("relax") -> "relaxing music"
            text.contains("lofi") || text.contains("lo-fi") -> "lofi"
            text.contains("workout") || text.contains("gym") -> "workout"
            text.contains("sleep") || text.contains("neend") -> "sleep music"
            text.contains("focus") || text.contains("study") -> "focus music"
            text.contains("instrumental") -> "instrumental"
            else -> null
        }
        val isSearchOnly = text.contains("search karo") || text.contains("search") && !text.contains("play") && !text.contains("chalao")
        if (mood != null && (text.contains("chalao") || text.contains("play") || text.contains("spotify") || text.contains("music"))) {
            return DeviceCommand.SpotifySearch(mood, autoPlay = !isSearchOnly)
        }
        if (text.contains("spotify") && (text.contains("search") || text.contains("chalao") || text.contains("play"))) {
            val q = text.replace(Regex(".*spotify( par| pe)?"), "").replace(Regex("(search karo|chalao|play karo|play)"), "").trim()
            if (q.isNotEmpty() && q != "kholo") return DeviceCommand.SpotifySearch(q, autoPlay = !isSearchOnly)
        }
        return null
    }

    private fun parseMediaControls(text: String): DeviceCommand? {
        Regex("(?:skip|jump|go|aage) (?:forward |ahead )?(\\d+)\\s*(seconds?|minute|min|minutes)?").find(text)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 10
            val unit = it.groupValues[2]
            val sec = if (unit.startsWith("min")) n * 60 else n
            if (text.contains("peeche") || text.contains("back") || text.contains("rewind")) {
                return DeviceCommand.MediaSeek(sec, false)
            }
            if (text.contains("skip") || text.contains("aage") || text.contains("forward")) {
                return DeviceCommand.MediaSeek(sec, true)
            }
        }
        Regex("(\\d+)\\s*(minute|min|minutes|seconds?)\\s*(skip|aage|peeche|back)").find(text)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 10
            val sec = if (it.groupValues[2].startsWith("min")) n * 60 else n
            val forward = !it.groupValues[3].contains("peeche") && it.groupValues[3] != "back"
            return DeviceCommand.MediaSeek(sec, forward)
        }
        if (Regex("^(?:next|skip)(?: (?:track|song|video|one|it|forward|ahead|to the next))*$").matches(text) ||
            text == "next song" || text == "agli gana" || text == "agli song" || text == "next"
        ) return DeviceCommand.MediaNext

        if (Regex("^(?:previous|go back|skip back(?:ward)?)(?: (?:track|song|video|one|it))?$").matches(text) ||
            text == "previous song" || text == "previous"
        ) return DeviceCommand.MediaPrevious

        if (text == "stop media" || text.contains("media stop") || text == "stop playback" || text == "stop song") {
            return DeviceCommand.MediaStop
        }
        if (text == "pause" || text.startsWith("pause ") || text.contains("gana roko") ||
            text.contains("song pause") || text.contains("video roko") || text == "pause karo"
        ) return DeviceCommand.MediaPause

        if (Regex("^(?:play|resume)(?: the)? (?:music|song|video|media|last song|playlist)$").matches(text) ||
            text == "resume" || text == "continue" || text == "play" || text == "play karo"
        ) return DeviceCommand.MediaPlay

        return null
    }

    private fun parseCall(text: String): DeviceCommand? {
        if (text.contains("call receive") || text.contains("call uthao") || text.contains("answer call") ||
            text == "call receive karo"
        ) return DeviceCommand.AnswerCall

        if (text.contains("call end") || text.contains("call cut") || text.contains("phone rakh") ||
            text.contains("call reject") || text == "call end karo" || text == "phone kato"
        ) return DeviceCommand.EndCall

        if (text.contains("speaker par") || text.contains("speaker on") || text == "speakerphone") {
            return DeviceCommand.Speaker(true)
        }
        if (text.contains("speaker off")) return DeviceCommand.Speaker(false)
        if (text.contains("call mute") || text.contains("call unmute")) {
            return DeviceCommand.CallMute(!text.contains("unmute"))
        }

        Regex("^(?:call|dial|ring) (.+)$").find(text)?.let {
            return DeviceCommand.PhoneCall(it.groupValues[1].trim())
        }
        Regex("(.+?) ko (?:phone |call )?(?:karo|milao)").find(text)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotEmpty() && !target.contains("whatsapp") && target !in listOf("volume", "torch", "alarm")) {
                return DeviceCommand.PhoneCall(target)
            }
        }
        return null
    }

    private fun parseInstall(text: String): DeviceCommand? {
        if (text.contains("play store") && text.contains("search")) {
            val q = text.replace(Regex(".*(?:play store par|play store pe|on play store)"), "")
                .replace(Regex("search karo|search"), "").trim()
            if (q.isNotEmpty()) return DeviceCommand.PlayStoreSearch(q)
        }
        if (text.contains("install karo") || text.startsWith("install ")) {
            val app = text.replace(Regex("install karo|install"), "").trim()
            if (app.isNotEmpty()) return DeviceCommand.InstallApp(app)
        }
        if (text.contains("uninstall")) {
            val app = text.replace(Regex(".*uninstall( karo)?"), "").replace("ye app", "").trim()
                .ifBlank { "current" }
            return DeviceCommand.UninstallApp(app)
        }
        if (text.contains("latest photo") || text.contains("aakhri photo") || text.contains("pichli photo")) {
            return DeviceCommand.ManagePhotos("show_latest")
        }
        if (text.contains("photo delete") || text.contains("delete photo")) {
            return DeviceCommand.ManagePhotos("delete_latest")
        }
        if (text.contains("gallery kholo") || text == "open gallery" || text.contains("gallery open") || text == "gallery") {
            return DeviceCommand.ManagePhotos("open_gallery")
        }
        return null
    }

    private fun parseBrowser(text: String): DeviceCommand? {
        if (text.contains("first result") || text.contains("pehla result") || text.contains("first result kholo") ||
            text.contains("pehla result kholo")
        ) {
            return DeviceCommand.PlayFirstResult
        }
        if (text.contains("new tab")) return DeviceCommand.BrowserAction("new_tab")
        if (text.contains("close tab")) return DeviceCommand.BrowserAction("close_tab")
        if (text.contains("refresh") || text.contains("reload") || text.contains("page refresh")) {
            return DeviceCommand.BrowserAction("refresh")
        }
        if (text.contains("forward") && (text.contains("page") || text.contains("browser"))) {
            return DeviceCommand.BrowserAction("forward")
        }
        if (text.contains("scroll down") || text.contains("neeche scroll")) {
            return DeviceCommand.BrowserAction("scroll_down")
        }
        if (text.contains("scroll up") || text.contains("upar scroll")) {
            return DeviceCommand.BrowserAction("scroll_up")
        }
        return null
    }

    private fun parseMultiWebsites(text: String): DeviceCommand? {
        if (!(text.contains("kholo") || text.contains("open"))) return null
        val cleaned = text.replace(Regex("\\s+(kholo|open karo|open)$"), "")
        val parts = cleaned.split(Regex("\\s*,\\s*|\\s+aur\\s+|\\s+and\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val urls = parts.mapNotNull { WebsiteCatalog.urlFor(it) }
        if (urls.size >= 2) return DeviceCommand.OpenWebsites(parts)
        return null
    }

    private fun parseBulkApps(text: String): DeviceCommand? {
        if (Regex("(?:close|kill|clear|shut|stop) (?:all|every|each) (?:the |running |open |background )?apps?").containsMatchIn(text) ||
            Regex("(?:all|every|each|sab|sari|tamam) apps? (?:ko )?(?:close|kill|shut|stop|band)").containsMatchIn(text) ||
            (text.contains("background mein jo apps") && text.contains("band"))
        ) return DeviceCommand.CloseAllApps

        if (Regex("(?:all|sab|sari) apps? (?:ko )?(?:open|kholo)").containsMatchIn(text) ||
            text == "open all apps"
        ) return DeviceCommand.OpenAllApps

        if (text.contains("background mein kitne") || text.contains("running apps") ||
            text.contains("background apps") || text.contains("kitne apps chal")
        ) return DeviceCommand.ListRunningApps

        return null
    }

    private fun parseNav(text: String): DeviceCommand? {
        if (text.contains("split") || text.contains("half-half") || text.contains("half half") ||
            (text.contains("upar") && text.contains("neeche"))
        ) return DeviceCommand.SplitScreen

        if (Regex("^(?:go|come|take me)? ?(?:to |back to )?(?:the )?home(?: screen)?$").matches(text) ||
            text == "home par jao" || text == "ghar jao" || text == "go home" || text == "home"
        ) return DeviceCommand.OpenHome

        if (text == "back jao" || text == "go back" || text == "peeche jao" || text == "back") {
            return DeviceCommand.GoBack
        }

        if (text.contains("previous app") || text.contains("previous window") || text.contains("pichli app") ||
            text == "previous app par jao"
        ) return DeviceCommand.PreviousApp

        if (text.contains("recents") || text.contains("recent apps")) return DeviceCommand.OpenRecents

        Regex("(.+?) se (.+?) par (?:switch karo|jao)").find(text)?.let {
            return DeviceCommand.SwitchApp(it.groupValues[1].trim(), it.groupValues[2].trim())
        }

        if (text.contains("minimize") || text.contains("background mein bhejo") || text.contains("background mein rakho") ||
            text.contains("background mein chalao") || text.contains("background me chalao") || text.contains("background me jao") ||
            text.contains("background mein jao") || text.contains("background me daalo")
        ) {
            val app = text.replace(Regex("(ko )?(minimize karo|background mein bhejo|background mein rakho|background mein chalao|background me chalao|background me jao|background mein jao|current app )"), "").trim()
            val name = if (app.contains("current") || app.contains("is app") || app.isBlank()) null else app
            return DeviceCommand.MinimizeApp(name)
        }

        if (text.contains("foreground mein lao") || text.contains("foreground lao")) {
            val app = text.replace(Regex(" ko foreground mein lao| ko foreground lao"), "").trim()
            if (app.isNotBlank()) return DeviceCommand.OpenApp(app)
        }

        return null
    }

    private fun parseCamera(text: String): DeviceCommand? {
        if (text.contains("photo kheencho") || text.contains("photo lo") || text.contains("photo le lo") ||
            text.contains("take photo") || text.contains("click photo") || text.contains("picture click") ||
            text.contains("camera se photo") || text.contains("shutter click") || text.contains("tasveer lo") ||
            text.contains("camera click")
        ) {
            return DeviceCommand.CameraTakePhoto
        }
        if (text.contains("video record") || text.contains("video banao") || text.contains("video bnao") ||
            text.contains("record video") || text.contains("video capture")
        ) {
            val seconds = Regex("(\\d+)\\s*(?:second|sec|minute|min)?").find(text)?.let {
                val n = it.groupValues[1].toIntOrNull() ?: 30
                if (text.contains("min")) n * 60 else n
            } ?: 30
            return DeviceCommand.CameraRecordVideo(seconds)
        }
        if (text.contains("camera switch") || text.contains("lens switch") || text.contains("flip camera") ||
            text.contains("front camera") || text.contains("back camera") || text.contains("camera badlo") ||
            text.contains("switch camera")
        ) {
            return DeviceCommand.CameraSwitchLens
        }
        return null
    }

    private fun parseWebSearch(text: String): DeviceCommand? {
        val openFirst = text.contains("first result") || text.contains("pehla result")
        Regex("google (?:par|pe|per) (.+?) search karo").find(text)?.let {
            return DeviceCommand.WebSearch(it.groupValues[1].trim(), openFirst)
        }
        Regex("^search (?:google |the web |the internet |web )?(?:for )?(.+)$").find(text)?.let {
            if (it.groupValues[1].isNotBlank()) return DeviceCommand.WebSearch(it.groupValues[1].trim(), openFirst)
        }
        Regex("^google (.+)$").find(text)?.let {
            return DeviceCommand.WebSearch(it.groupValues[1].replace("search karo", "").trim(), openFirst)
        }
        return null
    }

    private fun parseOpenClose(text: String): DeviceCommand? {
        if (text == "app band karo" || text == "app close karo" || text == "close app" ||
            text == "is app ko band karo" || text == "is app ko close karo" ||
            text.contains("current app close") || text.contains("is app ko hata") || text.contains("current app band") ||
            text == "is app ko hata do" || text == "current app hata do"
        ) {
            return DeviceCommand.CloseApp("current")
        }
        if (text == "ai ko band karo" || text == "ai band karo" || text == "assistant band karo" ||
            text == "jarvis band karo" || text == "luna band karo" || text == "assistant close karo" ||
            text == "close assistant" || text == "stop assistant"
        ) {
            return DeviceCommand.StopAssistantSession
        }
        if (DeviceAutomationManager.COMMON_PACKAGES.containsKey(text) || text == "camera" || text == "dialer" || text == "gallery") {
            return if (text == "gallery") DeviceCommand.OpenGallery else DeviceCommand.OpenApp(text)
        }
        Regex("^(?:open|launch|start|chalu karo|open karo|shuru karo|kholo|khol do) (.+)$").find(text)?.let {
            val app = cleanSpokenApp(it.groupValues[1].trim())
            if (app.isNotEmpty()) return DeviceCommand.OpenApp(app)
        }
        Regex("^(?:close|quit|exit|kill|band karo|band kar do|close karo|hata do|hatao) (.+)$").find(text)?.let {
            val app = cleanSpokenApp(it.groupValues[1].trim())
            if (app.isNotEmpty()) return DeviceCommand.CloseApp(app)
        }
        Regex("(.+?) (?:kholo|khol do|open karo|open kar do|chalu karo|chalana hai|kholna hai)$").find(text)?.let {
            val app = cleanSpokenApp(it.groupValues[1].trim())
            if (app.isNotEmpty()) return DeviceCommand.OpenApp(app)
        }
        Regex("(.+?) (?:band karo|band kar do|close karo|close kar do|hata do|hatao|kill karo)$").find(text)?.let {
            val app = cleanSpokenApp(it.groupValues[1].trim())
            if (app.isNotEmpty()) return DeviceCommand.CloseApp(app)
        }
        return null
    }

    private fun cleanSpokenApp(name: String): String {
        return name
            .replace(Regex("^(?:the|my|please|app|application)\\s+"), "")
            .replace(Regex("\\s+(?:app|application|please|ko)$"), "")
            .trim()
    }
}
