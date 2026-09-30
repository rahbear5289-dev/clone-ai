# Settings & System Permissions Redesign Implementation Plan

Redesign the Settings UI and implement the dedicated "Manage System Permissions" screen according to the 3 provided reference images (dark sci-fi theme, orange accent buttons, permission status indicators, BYOK Gemini API key card, Google AI Studio link), along with permission gating for AI device automation actions.

## User Review Required

> [!IMPORTANT]
> - **System Updates Section**: As requested, the "System Updates" card has been completely removed because it was deemed unnecessary.
> - **Manage System Permissions**: A dedicated screen (`PermissionsActivity`) will display all 5 system permissions with dynamic state: **Orange** (`GRANT`) when missing/disabled, and **Green** (`GRANTED` / `ENABLED`) when granted.
> - **AI Permission Gate**: If a command requires a permission that hasn't been granted (e.g. Display Over Other Apps for launching apps in background, Call permission for dialer/calls, Contacts for WhatsApp), Luna AI will politely announce: *"Permission is not allowed. Please grant the required permission first in Settings."*

---

## Proposed Changes

### UI & Resources

#### [NEW] `bg_card_red_outline.xml`, `bg_card_green_outline.xml`
- Drawables for the top status banners in `PermissionsActivity`:
  - Missing permissions banner: Dark reddish background `#200C0C`, red border `#FF3B30`, red alert shield icon.
  - All granted banner: Dark greenish background `#0A2014`, green border `#10B981`, green shield icon.

#### [NEW] `activity_permissions.xml`
- Complete layout matching Reference Image 2:
  - Header: Back button (`<- BACK`), signal bars  (or `LUNA-X`) tag.
  - Title: `SYSTEM PERMISSIONS`
  - Subtitle: "Manage hardware & OS access required for AI voice wake & background features"
  - Action Required Warning Banner with dynamic count (e.g. `ACTION REQUIRED: 3 PERMISSIONS MISSING`)
  - 5 Permission Cards:
    1. **Microphone Access** (Mic icon, subtitle, `GRANT` orange / `GRANTED` green)
    2. **Display Over Other Apps** (Layers icon, subtitle, `GRANT` orange / `GRANTED` green)
    3. **Push Notifications** (Bell icon, subtitle, `GRANT` orange / `GRANTED` green)
    4. **GPS & Location Access** (Location pin icon, subtitle, `GRANT` orange / `GRANTED` green)
    5. **Background Popups (MIUI)** (Phone icon, subtitle, `GRANT` orange / `ENABLED` green)
  - Bottom Action:
    - Primary Orange Button: `GRANT ALL REQUIRED PERMISSIONS` (with shield icon)
    - Secondary Button: `RE-CHECK PERMISSION STATUS` (with refresh icon)

#### [MODIFY] `activity_settings.xml`
- Redesign matching Reference Images 1 & 3:
  - Header: `<- BACK` and  / `LUNA-X` logo
  - Title: `SETTINGS`, subtitle "Configure your personal AI assistant hardware & system settings"
  - **System Permissions Card**:
    - Orange shield icon + `SYSTEM PERMISSIONS` title
    - Top-right red badge: `ACTION REQUIRED` (or green `ALL GRANTED`)
    - Subtitle description
    - Dark pill button: `MANAGE SYSTEM PERMISSIONS` with arrow icon
  - **Gemini API Status Card**:
    - Bullet indicator: `• STATUS: NO KEY CONFIGURED` (red) or `• STATUS: KEY CONFIGURED` (green)
  - **Bring Your Own Key (BYOK) Card**:
    - Key icon + `BRING YOUR OWN KEY (BYOK)`
    - Subtitle description
    - Input field with eye toggle to show/hide API key
    - Orange button: `SAVE KEY` (with checkmark icon)
  - **Need a Gemini API Key? Card**:
    - Subtitle: "Get a free Gemini API key directly from Google AI Studio in less than a minute."
    - Dark button with external link icon: `Get Key from Google AI Studio` -> opens `https://aistudio.google.com/app/apikey`

---

### Logic & Activities

#### [NEW] `com.jarvis.assistant.ui.settings.PermissionsActivity.kt`
- Handles checking real Android system permissions:
  - `RECORD_AUDIO`
  - `Settings.canDrawOverlays(context)`
  - `POST_NOTIFICATIONS` (Android 13+) / NotificationManagerCompat
  - `ACCESS_FINE_LOCATION`
  - MIUI Background Popup (MIUI intent / settings fallback)
- Real-time UI updates: updates buttons from Orange (`GRANT`) to Green (`GRANTED` / `ENABLED`), and updates the top banner dynamically.
- "GRANT ALL REQUIRED PERMISSIONS":
  - Triggers permission prompt sequence for ungranted permissions.
- "RE-CHECK PERMISSION STATUS":
  - Re-evaluates all permission states, re-requests any missing permissions, and shows feedback Toast.
- Individual item clicks:
  - Directly requests that specific permission or opens the specific system setting screen.

#### [MODIFY] `com.jarvis.assistant.ui.settings.SettingsActivity.kt`
- Manage System Permissions button opens `PermissionsActivity`.
- Updates the `SYSTEM PERMISSIONS` badge (`ACTION REQUIRED` vs `ALL GRANTED`) based on real status.
- Implements BYOK API Key save logic:
  - If empty: shows error message ("API key cannot be empty").
  - If non-empty: saves to `AppPreferences.apiKey`, updates Gemini API status indicator to green `STATUS: KEY CONFIGURED`, shows success toast/popup "API key saved successfully!".
  - Eye icon toggles password mask / plain text visibility.
- "Get Key from Google AI Studio" button opens `https://aistudio.google.com/app/apikey` in the web browser.

#### [MODIFY] `AndroidManifest.xml`
- Register `PermissionsActivity` (`com.jarvis.assistant.ui.settings.PermissionsActivity`).

#### [MODIFY] `JarvisConversationService.kt`
- Permission gating before executing device automation:
  - `open_app`, `close_app`, `close_all_apps`, `open_all_apps`: verify `Settings.canDrawOverlays(this)`. If false -> `speakFeedback("Display over other apps permission is required. Please grant it in Settings first.")` and abort action.
  - `make_phone_call`: verify `CALL_PHONE` permission. If false -> `speakFeedback("Call permission is not allowed. Please allow it first in Settings.")` and abort action.
  - `send_whatsapp_message`: verify `READ_CONTACTS` permission if resolving by name. If false -> inform user to allow permission.

---

## Verification Plan

### Automated Build Verification
Run standard Gradle debug build to ensure 0 compilation errors:
```powershell
$env:JAVA_HOME = "D:\Android\jdk17\jdk-17.0.12+7"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug --no-daemon
```

### Manual & Functional Verification
1. Verify `SettingsActivity` layout and UI matches reference images 1 & 3:
   - System Permissions card with badge and navigation button.
   - Status card showing "STATUS: NO KEY CONFIGURED" (red) or "STATUS: KEY CONFIGURED" (green).
   - BYOK input with working eye toggle and Save Key button with validation.
   - "Get Key from Google AI Studio" button properly intent-launches the URL.
2. Verify `PermissionsActivity` layout matches reference image 2:
   - 5 permission items with orange `GRANT` and green `GRANTED` states.
   - "Grant All Required Permissions" triggers request flows.
   - "Re-check Permission Status" updates states.
3. Verify AI voice gating:
   - Commands like "open YouTube" or "call X" check required permissions before executing, informing the user verbally if missing.
