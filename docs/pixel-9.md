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

```cmd
cd C:\dev\DuddyTranslator
scripts\run-backend-lan.cmd
```

The script prints one or more URLs like:

```text
http://192.168.1.50:8001
```

Put that URL in the app's **Settings > Backend URL**. Keep the Pixel 9 and PC on
the same Wi-Fi. If Windows Firewall prompts, allow Python/FastAPI on the Private
network.

For the debug APK, the easier path is to install with LAN mode while the phone is
temporarily plugged in:

```cmd
cd C:\dev\DuddyTranslator
scripts\install-pixel9-lan.cmd
```

That bakes the PC LAN URL into `BuildConfig.DEFAULT_BACKEND_URL` and migrates old
localhost debug settings to the LAN URL.

For 5G-first use, turn on **Settings > Prefer Cellular Data** in the app and use
a public HTTPS backend URL. The setting avoids local/private backend URLs
including USB loopback, emulator, Wi-Fi LAN, and phone-hotspot addresses because
those routes must remain local to reach the PC backend.

The quickest travel path uses a public HTTPS tunnel to the local backend:

```cmd
cd C:\dev\DuddyTranslator
scripts\run-backend-travel.cmd
```

Keep that window open. Then plug in the Pixel once and install the travel build
from a second PowerShell window:

```cmd
cd C:\dev\DuddyTranslator
scripts\install-pixel9-travel.cmd
```

The travel installer uses the saved public tunnel URL, turns the build's default
cellular preference on, and migrates old private backend URLs to the public HTTPS
travel URL. A stable hosted backend can be used instead with:

```powershell
.\scripts\install-pixel9-travel.ps1 -BackendUrl https://your-backend.example.com
```

For São Paulo / Google Fi travel, use the stable Cloud Run path rather than the
temporary tunnel:

```cmd
cd C:\dev\DuddyTranslator
scripts\deploy-backend-cloudrun.cmd -ProjectId YOUR_GOOGLE_CLOUD_PROJECT_ID
scripts\install-pixel9-cloudrun.cmd
```

The Cloud Run deploy script defaults to `southamerica-east1`, stores
`OPENAI_API_KEY`, `ALLOWED_APP_TOKEN`, and `SESSION_TOKEN_SECRET` in Secret
Manager, saves the stable backend URL to `logs\cloudrun-backend-url.txt`, and
the Pixel installer migrates old `192.168.x.x` or `trycloudflare.com` settings to
that stable HTTPS URL.

## UI Notes

- Portrait orientation is locked for one-handed travel use.
- Conversation controls wrap for the Pixel 9's 20:9 portrait screen.
- Speaker buttons resize to the available device width.
- Local cleartext HTTP is allowed only for loopback development addresses in
  release-like builds. The debug build also allows private LAN HTTP for
  cable-free Pixel 9 testing.
- Prefer Cellular Data routes eligible public backend/OpenAI sockets through the
  Pixel's cellular network when available.
- The stable Pixel 9 path uses Android speech recognition, backend text translation, and Android Text-to-Speech.
- Offline Medical Fallback is enabled by default. It uses an on-device
  SCC/Mohs-focused English/Portuguese phrasebook for typed backup phrases and for
  backend/network failures. This keeps critical phrases available without Wi-Fi
  or cellular data, but it is not general-purpose offline AI translation.
- The removed native WebRTC client stays out of the app because it crashed on the
  Pixel 9 network thread.
