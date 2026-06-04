# Google Pixel 9 Target

Duddy Translator is now configured first for a physical Google Pixel 9.

## Device Profile

- Device: Google Pixel 9
- Display: 6.3 inch 20:9 OLED
- Resolution: 1080 x 2424
- Density: about 422 ppi
- Refresh: 60-120 Hz
- CPU ABI: arm64-v8a
- Audio hardware: stereo speakers, three microphones, Bluetooth 5.3
- Android floor: Pixel 9 launched with Android 14 and supports newer Android releases
- App build floor: Android 14 / API 34

## Recommended Development Flow

1. Enable Developer options on the Pixel 9.
2. Enable USB debugging.
3. Connect the Pixel 9 by USB.
4. Accept the USB debugging authorization prompt on the phone.
5. Start the FastAPI backend on the PC.
6. Run the Pixel 9 install script.

```powershell
cd C:\dev\DuddyTranslator
.\scripts\run-backend.ps1
```

Open a second PowerShell window:

```powershell
cd C:\dev\DuddyTranslator
.\scripts\install-pixel9-debug.ps1
```

The install script builds the debug APK, verifies the connected device, runs:

```powershell
adb reverse tcp:8001 tcp:8001
```

Then it installs and launches the debug app.

## Backend URL

For a physical Pixel 9 over USB, keep the app backend URL set to:

```text
http://127.0.0.1:8001
```

That works because `adb reverse` maps the Pixel 9 loopback port to the PC backend.

For an Android emulator, use:

```text
http://10.0.2.2:8000
```

For Wi-Fi testing on a real Pixel 9, bind the backend to the PC network interface and use the PC LAN IP address. In production, use HTTPS.

## UI Notes

- Portrait orientation is locked for one-handed travel use.
- Conversation controls wrap for the Pixel 9's 20:9 portrait screen.
- Speaker buttons resize to the available device width.
- Local cleartext HTTP is allowed only for loopback development addresses.
- The stable Pixel 9 path uses Android speech recognition, backend text translation, and Android Text-to-Speech.
- A second realtime engine (Settings > Realtime Engine > OpenAI Realtime Voice)
  streams microphone audio to the OpenAI Realtime API over a WebSocket using Android
  AudioRecord/AudioTrack. It replaces the removed native WebRTC client, which crashed
  on the Pixel 9 network thread.
