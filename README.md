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

The Android emulator reaches the host machine at:

```text
http://10.0.2.2:8000
```

## Pixel 9 Troubleshooting

If **Start Conversation** shows **Needs attention**, check these first:

- `.\scripts\run-backend.ps1` is still running on the PC.
- The Pixel 9 is connected by USB and USB debugging is authorized.
- `adb reverse tcp:8001 tcp:8001` has been run, or rerun `.\scripts\install-pixel9-debug.ps1`.
- `backend\.env` contains `OPENAI_API_KEY`.

Realtime startup errors are shown inside the app instead of closing the app.

## Voice Engine

The Pixel 9 app uses the stable Android speech path:

- Android `SpeechRecognizer` hears English or Brazilian Portuguese.
- The PC backend translates the recognized text with the OpenAI API.
- Android Text-to-Speech speaks the translated phrase aloud.

The earlier native WebRTC client remains removed because the `org.webrtc` native
library crashed on the Pixel 9 network thread.

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
- Android uses a configurable app token stored with encrypted preferences.
- Conversation history is stored locally with Room.
- The default `ALLOWED_APP_TOKEN` is `dev-local-token`, intended only for the
  USB `adb reverse` localhost setup. Change it in `backend\.env` and in the app's
  **Settings > App Token** before exposing the backend on any real network. The
  backend compares tokens with a constant-time check.

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
