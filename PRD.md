You are a senior Android engineer, Java developer, AI agent architect, automation engineer, API integration specialist and debugging expert.

I have an existing Android Voice AI Assistant application written in Java.

I want you to upgrade the EXISTING application according to the complete specification below.

============================================================
IMPORTANT — EXISTING PROJECT RULE
============================================================

DO NOT create a new Android project.

DO NOT replace the entire existing architecture.

DO NOT remove existing working features.

DO NOT blindly rewrite existing files.

First inspect the complete existing codebase and understand:

- project structure
- Gradle configuration
- Android SDK versions
- Java version
- Activities
- Fragments
- Services
- BroadcastReceivers
- ViewModels
- repositories
- API clients
- AI implementation
- voice recognition
- text-to-speech
- WebView
- browser automation
- YouTube automation
- location system
- navigation system
- alarm system
- notification system
- permissions
- settings
- API configuration
- database/local storage
- background services
- UI architecture

Then create an implementation plan.

After understanding the existing architecture, implement the requested features using the existing architecture wherever possible.

============================================================
CORE GOAL
============================================================

Transform the current application into an autonomous Android Voice AI Assistant.

The assistant should be capable of:

1. Normal AI conversation
2. Automatic knowledge-gap detection
3. Automatic Deep Research
4. Tavily Web Search
5. Current information retrieval
6. Research UI
7. Google Maps navigation
8. Automatic route launching
9. Device alarm creation
10. Automatic Response Mode
11. Incoming notification/message detection
12. Caller detection
13. Contact name identification
14. Voice announcement of calls/messages
15. Website automation improvements
16. YouTube automation improvements
17. Robust background processing
18. Error handling
19. Permission management
20. API management
21. Performance optimization
22. Existing bug fixing

The assistant should feel autonomous and intelligent instead of requiring the user to manually activate every feature.

============================================================
1. NORMAL AI MODE
============================================================

The existing AI conversation system must continue working.

For normal questions, the assistant should answer normally.

Examples:

"What is coding?"

"Explain Java."

"What is the capital of India?"

"Write a romantic message."

"What is 2+2?"

These should NOT trigger Deep Research unnecessarily.

The assistant should use its existing AI model for normal knowledge.

============================================================
2. AUTOMATIC KNOWLEDGE GAP DETECTION
============================================================

Implement an intelligent decision layer.

Before answering, determine whether the user's query requires external web research.

Research should automatically activate when the query requires:

- current information
- latest information
- live information
- recent events
- sports results
- current matches
- current scores
- breaking news
- current prices
- product availability
- latest software releases
- latest technology information
- current weather
- current public information
- information unknown to the local AI
- factual verification
- multiple sources
- web pages
- recent announcements

Examples:

User:
"What is Java?"

NORMAL AI

User:
"Who won today's cricket match?"

DEEP RESEARCH

User:
"What cricket match is happening today?"

DEEP RESEARCH

User:
"What is the latest iPhone release?"

DEEP RESEARCH

User:
"What happened in today's football match?"

DEEP RESEARCH

User:
"Explain Java inheritance."

NORMAL AI

User:
"Find the latest news about SpaceX."

DEEP RESEARCH

The user should NOT need to say:

"Do deep research."

The assistant should automatically understand when research is necessary.

============================================================
3. DEEP RESEARCH PIPELINE
============================================================

Implement:

USER QUERY
     ↓
Intent Detection
     ↓
Knowledge Gap Detection
     ↓
Research Required?
     ↓
YES
     ↓
Research Query Generation
     ↓
Tavily Web Search
     ↓
Multiple Search Results
     ↓
Source Filtering
     ↓
Duplicate Removal
     ↓
Content Extraction
     ↓
Cross-Source Comparison
     ↓
Fact Verification
     ↓
AI Synthesis
     ↓
Final Answer
     ↓
Voice + Text Response

Do not use Deep Research for every query.

============================================================
4. TAVILY API
============================================================

Add a dedicated API section:

Settings
→ APIs
→ Tavily Web Search API

Fields:

Tavily API Key
[********************]

[TEST CONNECTION]

Status:
CONNECTED
or
NOT CONNECTED

Never hardcode the API key in Java source code.

Never expose the complete API key in logs.

Use secure storage where appropriate.

============================================================
5. TAVILY API ROLE
============================================================

Tavily is the WEB SEARCH / RESEARCH layer.

Tavily is NOT the main conversational AI.

Tavily should be responsible for:

- searching the web
- finding relevant pages
- retrieving recent information
- finding current sports information
- finding latest news
- discovering sources
- returning search results
- supporting Deep Research
- finding information unavailable to the local AI

The existing AI remains responsible for:

- understanding user intent
- deciding if research is needed
- interpreting results
- comparing sources
- summarizing information
- generating final answer

Create a clean architecture:

AIService
WebSearchService
TavilySearchService
DeepResearchService

Do not tightly couple Tavily directly to MainActivity.

============================================================
6. DEEP RESEARCH UI
============================================================

Use the uploaded reference image as the visual design reference.

The design should be:

- black/dark background
- futuristic
- minimal
- technical
- cyan/blue accents
- thin progress indicator
- AI-agent style
- professional
- smooth animation

The reference image contains:

AUTONOMOUS RAG AGENT

Replace this with:

AUTOMATIC RESEARCH AGENT

or:

AUTONOMOUS AI AGENT

Do NOT show:

RANDOMIZED

Remove:

IRIS and Tavily Neural Search Active...

Replace it with:

LINX AND TAVILY NEURAL ACTIVITY...

The actual user's query must appear dynamically.

Example:

ACTIVE QUERY

What cricket match is happening today?

Below:

LINX AND TAVILY NEURAL ACTIVITY...

Then an animated progress bar.

============================================================
7. RESEARCH UI STATES
============================================================

Research UI should support:

IDLE

DETECTING

SEARCHING

ANALYZING

VERIFYING

SYNTHESIZING

COMPLETED

FAILED

CANCELLED

Example:

ACTIVE QUERY

Who won today's cricket match?

STATUS

Detecting knowledge gap...

↓

Searching web...

↓

Analyzing sources...

↓

Verifying information...

↓

Generating answer...

↓

Research complete

The UI should automatically disappear/minimize after completion.

============================================================
8. RESEARCH ANIMATION
============================================================

The blue/cyan progress line should NOT be static.

Create a smooth animated progress indicator.

The animation should feel fast.

Use dynamic movement.

Use slightly randomized cyan/blue shades.

Do not make the animation extremely slow.

Do not artificially wait 10 seconds.

Target:

approximately 5–10 seconds for normal research when API/network conditions allow.

Actual API/network speed must determine completion.

Never fake research completion.

Never block the UI thread.

============================================================
9. DEEP RESEARCH PERFORMANCE
============================================================

Use:

- asynchronous networking
- ExecutorService / appropriate Java concurrency
- background processing
- connection reuse
- parallel search where appropriate
- limited result count
- query optimization
- caching
- deduplication
- request cancellation
- timeout handling

The UI must remain responsive.

No NetworkOnMainThreadException.

No ANR.

============================================================
10. SOURCE QUALITY
============================================================

Do not blindly trust the first search result.

Prefer:

- official websites
- official documentation
- government websites
- official sports sources
- primary sources
- trusted news organizations
- reliable databases

Filter:

- spam
- duplicate pages
- irrelevant results
- suspicious pages
- low-quality content

When possible, keep source URLs for citations/references.

If sources disagree, tell the user that sources disagree.

Do not invent information.

============================================================
11. RESEARCH CACHE
============================================================

Implement lightweight caching.

Recently researched stable information can use cache.

However, NEVER rely on stale cache for:

- live sports
- current scores
- breaking news
- current prices
- current weather
- live events
- real-time availability

These must perform fresh research.

============================================================
12. GOOGLE MAPS NAVIGATION
============================================================

The user has granted location permission.

Use the device's actual current GPS location.

Never hardcode the user's location.

Example:

User:

"Navigate to Mumbai Airport."

System:

Current GPS Location
       ↓
Destination Detection
       ↓
Mumbai Airport
       ↓
Google Maps Navigation

Open Google Maps navigation from current location to destination.

Use official Android-supported navigation Intent/URI mechanisms.

Example conceptually:

google.navigation:q=destination

Do not hardcode origin coordinates.

Google Maps should determine the current origin.

============================================================
13. NATURAL NAVIGATION COMMANDS
============================================================

Support commands such as:

"Navigate to Mumbai Airport."

"Take me to Ahmedabad."

"Open directions to Chandkheda."

"Start navigation to this place."

"How far is Mumbai Airport?"

"Show me the route to the airport."

"Navigate to home."

"Navigate to office."

Extract destination intelligently.

Handle:

- GPS unavailable
- location permission denied
- destination not found
- Google Maps unavailable
- network unavailable

Do not falsely claim navigation started.

============================================================
14. ALARM SYSTEM
============================================================

The current AI understands alarm commands but the actual device alarm is not being created correctly.

FIX THIS.

Examples:

"Set an alarm for 7 AM."

"Wake me up at 6:30 tomorrow."

"Set alarm at 9 PM."

"Set alarm for tomorrow morning."

The system must actually schedule the alarm.

Use proper Android APIs such as:

AlarmManager

and/or official Alarm Clock intents.

Handle Android version differences.

Handle exact alarm permissions where required.

Handle notification requirements.

Handle timezone changes.

Do not say:

"Alarm set."

unless the alarm was actually scheduled successfully.

============================================================
15. AUTO RESPONSE MODE
============================================================

Create:

AUTO RESPONSE MODE

The user can say:

"Turn on auto response."

"Enable auto response."

"Start auto response."

Example:

User:

"I am going to sleep.
Turn on auto response.
If someone messages me, tell them:
Sir is currently sleeping."

System:

AUTO RESPONSE ENABLED

Configured response:

"Sir is currently sleeping."

When a supported incoming message arrives:

Detect notification
     ↓
Identify application
     ↓
Identify sender
     ↓
Read message preview if permitted
     ↓
Check Auto Response rules
     ↓
Generate configured response
     ↓
Send reply if Android/app officially supports it

============================================================
16. AUTO RESPONSE RULE ENGINE
============================================================

Create a persistent configuration.

Example:

AutoResponseConfig:

enabled = true

responseText =
"Sir is currently sleeping."

activeUntil = optional

allowedApps =
WhatsApp, Messages, Telegram, etc.

replyToUnknownSenders =
true/false

replyToKnownContacts =
true/false

Do not create infinite reply loops.

Do not reply multiple times to the same notification.

Prevent duplicate responses.

============================================================
17. ANDROID AUTO RESPONSE LIMITATIONS
============================================================

Use Android's official:

NotificationListenerService

where appropriate.

For replying:

Use notification RemoteInput / available reply actions where supported.

IMPORTANT:

Not every messaging application allows third-party automatic replies.

If an app does not expose a supported reply action:

DO NOT pretend that the reply was sent.

Show/log:

"Automatic reply is not supported by this application."

Do not bypass Android security.

Do not use hidden/private APIs.

============================================================
18. INCOMING CALL DETECTION
============================================================

Detect incoming calls.

When a call comes:

Determine:

- incoming call
- phone number
- contact name if available

If saved contact:

"Rahul is calling."

If unknown:

"An unknown person is calling."

Do not unnecessarily announce the full phone number.

Use supported Android Telecom/phone APIs.

============================================================
19. CONTACT LOOKUP
============================================================

Use Contacts Provider where permission is granted.

If number matches saved contact:

Use contact name.

Example:

"Priya is calling."

If not found:

"An unknown number is calling."

Handle:

- country codes
- +91 numbers
- formatted numbers
- duplicate contacts
- multiple phone numbers
- private/blocked caller ID

Never invent contact names.

============================================================
20. MESSAGE ANNOUNCEMENT
============================================================

For supported notification-based messaging apps:

Detect:

- sender
- application
- message preview

Example:

"WhatsApp message from Rahul."

or:

"Message from an unknown contact."

If user has enabled voice notification reading:

Read it through TTS.

Do NOT automatically read private message content aloud unless the user has enabled this feature.

============================================================
21. PERMISSION MANAGER
============================================================

Create a central permission management system.

Potential permissions/services include:

Location
Notifications
Contacts
Phone state
Notification listener
Exact alarms
Telecom role where applicable

Do NOT request everything on first launch.

Request permission only when a feature requires it.

Create:

Settings
→ Permissions

Show:

Location
GRANTED / NOT GRANTED

Notifications
GRANTED / NOT GRANTED

Contacts
GRANTED / NOT GRANTED

Call Access
GRANTED / NOT GRANTED

Notification Listener
GRANTED / NOT GRANTED

Alarm Access
GRANTED / NOT GRANTED

Provide buttons to open the appropriate Android settings page.

============================================================
22. WEBSITE AUTOMATION
============================================================

The current website automation system has multiple bugs.

Perform a complete audit.

Inspect:

WebView

JavaScript bridge

page loading

URL handling

redirects

authentication

cookies/session

JavaScript execution

DOM interaction

clicks

text input

scrolling

dynamic content

popups

timeouts

permissions

navigation

state management

Fix root causes.

Do not create a second unrelated automation system.

============================================================
23. WEB AUTOMATION ROBUSTNESS
============================================================

Implement:

PageLoad detection

DOM readiness detection

Timeouts

Retries

Safe JavaScript execution

Error recovery

State tracking

Cancellation

Logging

Dynamic page handling

Do not rely only on fixed screen coordinates.

Prefer stable DOM selectors or officially supported APIs where available.

Never block the main thread.

============================================================
24. YOUTUBE AUTOMATION
============================================================

Audit and fix existing YouTube automation.

Check:

- WebView loading
- login/session state
- URL navigation
- search
- page transitions
- dynamic content
- JavaScript execution
- timing
- element detection
- authentication problems
- redirects
- errors

Do not use fragile fixed coordinates when avoidable.

Prefer official APIs where appropriate.

Respect YouTube's Terms of Service and platform restrictions.

If an operation is not officially or technically possible:

Do not fake success.

============================================================
25. BACKGROUND SERVICE ARCHITECTURE
============================================================

The following should be separated into dedicated components where possible:

AIService

TavilySearchService

DeepResearchService

NavigationService

LocationService

AlarmService

NotificationService

AutoResponseService

CallDetectionService

ContactService

WebAutomationService

YouTubeAutomationService

PermissionManager

Do not put all logic inside MainActivity.

============================================================
26. REQUEST MANAGEMENT
============================================================

Every AI/research request should have a unique ID.

Example:

Request #1001

If user asks another question:

Request #1002

Old research must not overwrite the new response.

Cancel old research where possible.

Only the latest valid request should update the active UI/conversation.

============================================================
27. ERROR HANDLING
============================================================

Fix:

NullPointerException

IllegalStateException

Network errors

JSON parsing errors

API errors

Timeouts

WebView errors

Permission errors

Service crashes

Activity lifecycle issues

Memory leaks

ANR

Threading problems

Race conditions

Duplicate listeners

Duplicate notifications

Duplicate responses

Do NOT use:

catch(Exception e) { }

with no handling.

Log useful technical information safely.

Never log:

API keys

passwords

tokens

private messages

sensitive personal data

============================================================
28. ANDROID LIFECYCLE
============================================================

Ensure services and listeners correctly handle:

onCreate

onStartCommand

onDestroy

Activity lifecycle

configuration changes

process recreation

background/foreground transitions

Do not leak:

Activity context

View references

Listeners

Receivers

WebViews

Threads

============================================================
29. API FAILURE HANDLING
============================================================

If Tavily fails:

If another configured search provider exists:
→ use fallback.

Otherwise:

"I couldn't access the web right now."

Do not fabricate current information.

If only one source is available:

Treat it as lower confidence.

If sources disagree:

Tell the user.

============================================================
30. UI REQUIREMENTS
============================================================

The application should maintain a modern futuristic AI design.

Deep Research screen:

Black background

Cyan/blue accents

Minimal text

Technical labels

Smooth animations

Actual user query

Research status

Animated progress line

The design must not look like a generic loading screen.

It should feel like:

"An autonomous AI agent is actively researching."

============================================================
31. SETTINGS STRUCTURE
============================================================

Settings should contain:

AI

APIs

Deep Research

Navigation

Alarm

Auto Response

Notifications

Call Detection

Permissions

Inside APIs:

AI API

Tavily Web Search API

Other existing APIs

Each API should have:

API Key

Connection Status

Test Connection

Save

Edit

Delete/Clear

Never expose full secrets.

============================================================
32. VOICE COMMANDS
============================================================

All major functions must support natural voice commands.

Examples:

"Search the latest cricket score."

"Who won today's football match?"

"Find the latest news about Apple."

"Navigate to Mumbai Airport."

"Set an alarm for 7 AM."

"Wake me up at 6 tomorrow."

"Turn on auto response."

"Turn off auto response."

"Tell me who is calling."

"Read my latest notification."

"Tell me who messaged me."

"Start navigation."

============================================================
33. SMART INTENT ROUTING
============================================================

Create a central intent router.

Example:

Voice Input
     ↓
Speech To Text
     ↓
Intent Router
     ↓
--------------------------------
|              |               |
Normal AI      Research        Action
               |               |
               Tavily          |
                              |
             ------------------
             |       |        |
          Maps     Alarm   AutoResponse
             |
          Device APIs
     ↓
Final Response
     ↓
TTS

The router must understand whether a command is:
CHAT
RESEARCH
NAVIGATION
ALARM
AUTO_RESPONSE
CALL
NOTIFICATION
WEB_AUTOMATION
YOUTUBE
SETTINGS
PERMISSION
SYSTEM_ACTION
============================================================
34. RESEARCH VS ACTION
============================================================
Important:
Not every request should trigger web search.
Example:
"Set alarm for 7 AM."
This is an ACTION.
Do not search Tavily.
Example:
"Where is Mumbai Airport?"
Could be RESEARCH.
Example:
"Navigate to Mumbai Airport."
This is NAVIGATION.
Example:
"Who won today's cricket match?"
This is RESEARCH.
The router must distinguish these intents.
============================================================
35. FAST RESPONSE STRATEGY
============================================================
For normal questions:
Answer immediately.
For actions:
Execute immediately.
For research:
Show research UI immediately.
Do not wait before showing the research UI.
Example:
User asks:
"Who won today's match?"
Immediately:
AUTOMATIC RESEARCH AGENT
ACTIVE QUERY
Who won today's match?
LINX AND TAVILY NEURAL ACTIVITY...
Then research runs in background.
============================================================
36. NO FAKE INFORMATION
============================================================
This is extremely important.
The assistant must NEVER fabricate:
- current sports scores
- current news
- current prices
- navigation status
- alarm status
- message sent status
- auto-response status
- API connection status
Only report an action as successful after actual success confirmation.
============================================================
37. TESTING REQUIREMENTS
============================================================
After implementation:
Build the project.
Resolve all compilation errors.
Check Gradle.
Check dependencies.
Check AndroidManifest.
Check permissions.
Check runtime behavior.
Test:
Normal AI
Deep Research
Tavily
Research UI
Navigation
GPS
Alarm
Auto Response
Notifications
Calls
Contacts
Website automation
YouTube automation
Background services
API errors
Network loss
Permission denial
App restart
Device reboot where relevant
============================================================
38. DEVICE COMPATIBILITY
============================================================
Ensure compatibility with modern Android versions.
Handle differences in:
Android 10
Android 11
Android 12
Android 13
Android 14
Android 15+
where relevant to the application's min/target SDK.
Do not use deprecated APIs without proper fallback.
============================================================
39. PERFORMANCE
============================================================
Avoid:
memory leaks
ANR
main-thread networking
heavy operations on UI thread
unnecessary polling
duplicate listeners
excessive API requests
excessive WebView instances
unnecessary background services
Use efficient lifecycle-aware/background architecture.
============================================================
40. SECURITY
============================================================
Never hardcode:
API keys
passwords
tokens
private credentials
Do not log sensitive information.
Do not bypass:
Android permissions
sandbox restrictions
security controls
website authentication
third-party app security
Use official Android APIs.
============================================================
41. FINAL CODE QUALITY
============================================================
Code should be:
clean
modular
maintainable
documented where necessary
thread-safe
error-tolerant
production-oriented
Do not create unnecessary classes.
Do not duplicate functionality.
Do not introduce a second implementation of an existing feature unless required.
Reuse existing code wherever it is stable.
============================================================
42. IMPLEMENTATION PROCESS
============================================================
Follow this exact process:
PHASE 1:
Inspect complete project.
PHASE 2:
Create architecture/bug report.
PHASE 3:
Identify existing implementations that can be reused.
PHASE 4:
Implement central Intent Router.
PHASE 5:
Implement automatic Knowledge Gap Detection.
PHASE 6:
Implement Tavily integration.
PHASE 7:
Implement Deep Research Service.
PHASE 8:
Implement Deep Research UI.
PHASE 9:
Implement navigation.
PHASE 10:
Fix alarm.
PHASE 11:
Implement Auto Response.
PHASE 12:
Implement call detection.
PHASE 13:
Implement notification/message detection.
PHASE 14:
Fix website automation.
PHASE 15:
Fix YouTube automation.
PHASE 16:
Fix remaining bugs.
PHASE 17:
Optimize performance.
PHASE 18:
Build and test.
============================================================
43. DO NOT BREAK EXISTING FEATURES
============================================================
Existing functionality must remain working unless it is explicitly replaced by a better implementation.
Before changing an existing component:
Understand why it exists.
Check dependencies.
Check where it is used.
Check lifecycle.
Then modify it safely.
============================================================
44. FINAL REPORT
============================================================
After completing implementation, provide a detailed report:
1. Architecture analyzed
2. Files modified
3. Files created
4. Files deleted, if any
5. Deep Research implementation
6. Tavily integration
7. Research UI implementation
8. Navigation implementation
9. Alarm fix
10. Auto Response implementation
11. Call detection
12. Message detection
13. Contact identification
14. Website automation fixes
15. YouTube automation fixes
16. Bugs fixed
17. Permissions added
18. Dependencies added
19. APIs added
20. Security improvements
21. Performance improvements
22. Testing performed
23. Remaining limitations
24. Android restrictions
25. Exact setup instructions
============================================================
FINAL IMPORTANT RULE
Do not just generate a theoretical answer.
Inspect the actual existing project.
Make the changes in the existing architecture.
Build the application.
Fix compilation errors.
Fix runtime problems.
Do not claim success without verification.
If a requested feature cannot be fully implemented because Android or a third-party application restricts it, implement the maximum officially supported functionality and clearly document the limitation.
The final result should feel like a real autonomous AI assistant rather than a collection of disconnected features.

### तुम्हारे ऐप का final architecture

```text
                    USER VOICE
                       ↓
                SPEECH TO TEXT
                       ↓
                 INTENT ROUTER
                       ↓
       ┌───────────────┼────────────────┐
       ↓               ↓                ↓
   NORMAL AI       DEEP RESEARCH       ACTION
       ↓               ↓                ↓
   AI MODEL          TAVILY          ACTION ROUTER
                       ↓                ↓
                  WEB SEARCH      ┌─────┼──────┐
                       ↓           ↓     ↓      ↓
                  ANALYSIS       MAPS  ALARM  AUTO RESPONSE
                       ↓
                  VERIFICATION
                       ↓
                   AI ANSWER
                       ↓
                  TEXT + TTS