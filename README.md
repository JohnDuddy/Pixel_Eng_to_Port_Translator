# Duddy Translator

Duddy Translator is an Android Studio project for real-time English (US) and Brazilian Portuguese voice interpretation, plus a FastAPI backend that keeps the OpenAI API key server-side.

The earlier local web translator prototype is still in this folder, but the primary project is now:

- `android/` - Pixel 9-first Kotlin app, Jetpack Compose, Material 3, Room, encrypted settings, WebRTC realtime client
- `backend/` - FastAPI service for OpenAI Realtime session credentials

## Android App

This project is configured first for a physical Google Pixel 9.

Open the Android project:

```text
C:\dev\DuddyTranslator\android
```

In Android Studio, choose **File > Open** and select that folder.

To install directly on a USB-connected Pixel 9:

```powershell
cd C:\dev\DuddyTranslator
.\scripts\install-pixel9-debug.ps1
```

Pixel 9 setup details are in `docs\pixel-9.md`.

## Backend

Set your API key in Windows:

```powershell
setx OPENAI_API_KEY "your_api_key_here"
```

Then open a new PowerShell window before starting the backend. You can also copy
`backend\.env.example` to `backend\.env` and put the key there.

Start the FastAPI backend:

```powershell
cd C:\dev\DuddyTranslator
.\scripts\run-backend.ps1
```

Backend health:

```text
http://127.0.0.1:8001/health
```

The physical Pixel 9 reaches the PC backend through USB `adb reverse` at:

```text
http://127.0.0.1:8001
```

That is the default backend URL in the Android app settings.

To use the Pixel 9 without the USB cable, start the backend in LAN mode instead:

```cmd
cd C:\dev\DuddyTranslator
scripts\run-backend-lan.cmd
```

The script prints one or more `http://<PC LAN IP>:8001` URLs. Build/install the
debug APK for that LAN URL once while the phone is plugged in:

```cmd
cd C:\dev\DuddyTranslator
scripts\install-pixel9-lan.cmd
```

The LAN URL is baked into the debug APK, and old localhost debug settings are
automatically migrated to it. Keep the phone on the same Wi-Fi as the PC, and
allow Python/FastAPI through Windows Firewall on the Private network if Windows
asks.

For travel, the Android app also has **Settings > Prefer Cellular Data**. When
enabled, public backend URLs and direct OpenAI realtime sockets are opened over
the phone's cellular network when Android exposes one. Local development URLs
such as `127.0.0.1`, `10.0.2.2`, and `192.168.x.x` intentionally stay on their
local route, because forcing those over 5G would disconnect the app from the PC
backend. For true away-from-Wi-Fi use, deploy the backend at a public HTTPS URL
and set that as the app's Backend URL.

For the quick travel setup from this repo, start a public HTTPS tunnel to the
local backend:

```cmd
cd C:\dev\DuddyTranslator
scripts\run-backend-travel.cmd
```

The first run downloads `cloudflared` into `.tools\cloudflared`, verifies
`OPENAI_API_KEY`, and replaces the development `dev-local-token` with generated
travel-safe backend secrets if needed. It prints and saves the public
`https://...trycloudflare.com` backend URL. Keep that window open while using the
translator over 5G.

In a second PowerShell window, plug in the Pixel once and install the travel
build:

```cmd
cd C:\dev\DuddyTranslator
scripts\install-pixel9-travel.cmd
```

That build bakes in the public HTTPS backend URL, defaults **Prefer Cellular
Data** to on, and migrates old local backend settings such as `192.168.x.x` to
the travel URL. For a permanent deployment, host `backend/` at your own stable
HTTPS URL and run `scripts\install-pixel9-travel.ps1 -BackendUrl https://...`.

For São Paulo or any trip where the PC cannot be the backend, use the permanent
Cloud Run setup instead. It deploys the FastAPI backend to Google Cloud Run in
`southamerica-east1` and stores secrets in Secret Manager:

```cmd
cd C:\dev\DuddyTranslator
scripts\deploy-backend-cloudrun.cmd -ProjectId YOUR_GOOGLE_CLOUD_PROJECT_ID
scripts\install-pixel9-cloudrun.cmd
```

This is the required setup for Google Fi use far away from home Wi-Fi: the phone
talks to a stable public HTTPS backend, not to your PC or home router. The quick
Cloudflare tunnel is only a temporary test path.

The Android emulator reaches the host machine at:

```text
http://10.0.2.2:8001
```

## Pixel 9 Troubleshooting

If **Start Conversation** shows **Needs attention**, check these first:

- `.\scripts\run-backend.ps1` is still running on the PC.
- USB mode: the Pixel 9 is connected, USB debugging is authorized, and
  `adb reverse tcp:8001 tcp:8001` has been run, or rerun
  `.\scripts\install-pixel9-debug.ps1`.
- Wi-Fi mode: `.\scripts\run-backend.ps1 -Lan` is running, the phone is on the
  same Wi-Fi as the PC, and the APK was installed with
  `.\scripts\install-pixel9-debug.ps1 -Lan`.
- 5G mode: **Prefer Cellular Data** is enabled and the Backend URL is a public
  HTTPS backend, not a local PC/LAN address. For the quick tunnel setup,
  `scripts\run-backend-travel.cmd` must still be running on the PC.
- `backend\.env` contains `OPENAI_API_KEY`.

Realtime startup errors are shown inside the app instead of closing the app.

## Voice Engine

The Pixel 9 app uses the stable Android speech path:

- Android `SpeechRecognizer` hears English or Brazilian Portuguese.
- Partial transcripts are shown while the user is speaking.
- The PC backend translates the recognized text with the OpenAI API.
- Android Text-to-Speech speaks the translated phrase aloud.
- Each translated row can be replayed, retried, or loaded into the backup phrase
  field for correction.
- Conversation history is saved locally by default and can be cleared from History.

## Critical Medical Travel Checklist

The Android app covers the required travel/medical workflow:

- Continuous two-way conversation: **Continuous** mode alternates speakers after
  each spoken translation.
- Push-to-talk mode: **Push-to-Talk**, **Travel**, and **Medical Precision** use
  explicit speaker buttons and **Finish Listening**.
- Medical terminology preservation: the backend prompt and offline phrasebook
  preserve SCC/Mohs terms such as `Carcinoma espinocelular (CEC)`, `Cirurgia de
  Mohs`, `Biópsia`, `Margens livres`, `Invasão perineural`, `Metástase`,
  `Enxerto de pele`, and `Retalho`.
- Text transcript logging: local Room history is enabled by default and can be
  searched or cleared from **Conversation History**.
- Offline fallback: **Settings > Offline Medical Fallback** is on by default.
  If Cloud Run/OpenAI/cellular data fails in Medical or Travel mode, the app uses
  an on-device English/Portuguese medical phrasebook and glossary. In Medical
  Mode, the backup phrase field also has explicit offline translation buttons.

The offline fallback is deliberately conservative. It is for known medical terms,
likely SCC/Mohs questions, and emergency interpreter phrases. It does not replace
a full online interpreter for arbitrary speech. If the Pixel speech recognizer
cannot hear/transcribe without data, type into **Backup phrase** and use the
offline buttons.

An experimental **Streaming Speech Engine** is available in Settings. It uses:

- Android `AudioRecord` PCM capture
- Client-side voice activity detection
- OpenAI Realtime WebSocket audio streaming with ephemeral credentials
- PCM audio playback through Android `AudioTrack`

Keep it off for normal use until it has been validated on the Pixel 9 with real
conversation tests. Push-to-talk remains the production-default path.

The earlier native WebRTC client remains removed because the `org.webrtc` native
library crashed on the Pixel 9 network thread.

The backend realtime endpoints remain experimental scaffolding only. The current
production-default path is push-to-talk with Android `SpeechRecognizer`, backend
text translation, and Android Text-to-Speech. A future streaming path should use
`AudioRecord`, VAD, partial transcripts, commit thresholds, and short-lived
backend-issued session credentials before it is exposed in the app.

## Modes

- Push-to-Talk Interpreter
- Continuous Conversation - hands-free; after each translation is spoken the
  microphone re-arms and alternates language for the next speaker.
- Travel Mode
- Medical Precision Mode

Medical mode stores and displays original speech, literal translation, and polished clinical translation. It also shows an in-app disclaimer: the app is an automated aid, not a certified medical interpreter.

## Security

- Never place `OPENAI_API_KEY` in the Android app.
- The backend reads the standard API key and creates temporary Realtime client credentials.
- Android uses the app token only to request a short-lived backend session token.
- Translation and realtime session requests use `Authorization: Bearer <session_token>`.
- Android stores the app token with encrypted preferences; new installs leave it blank until configured in Settings.
- Conversation history is stored locally with Room.
- The default `ALLOWED_APP_TOKEN` is `dev-local-token`, intended only for the
  USB `adb reverse` localhost setup. Change it in `backend\.env` and in the app's
  **Settings > App Token** before exposing the backend on Wi-Fi or any other real
  network. The backend compares tokens with a constant-time check.
- `scripts\run-backend-travel.ps1` automatically replaces `dev-local-token` and
  weak session secrets before exposing the backend through a public tunnel.

## Validation

Available local checks:

```powershell
cd C:\dev\DuddyTranslator
npm test
npm run build
npm run lint

cd C:\dev\DuddyTranslator\backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pytest

cd C:\dev\DuddyTranslator\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```
