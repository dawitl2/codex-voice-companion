# Set up your devices

## 1. Sign in on Windows

Install/build the apps as described in [Building](BUILDING.md). In the desktop app, choose **Set up this PC → Sign in to Codex** and complete the official browser sign-in if requested. An existing ChatGPT login is reused. Authentication stays in Codex's local credential storage; the app does not collect your password or forward credentials to the phone. API-key authentication is deliberately refused.

## 2. Connect privately

Use **Open Tailscale** on the desktop to open the installed app or its download page. Install Tailscale on Android as well, sign in, and allow the phone to reach the PC under your tailnet access policy. Then choose **Set up private connection** in Codex Voice. The app configures a private background Serve route only when there are no conflicting routes; an existing correct route is reused. It does not reset unrelated routes or enable public Funnel. Tailscale retains the private route across ordinary app restarts.

For manual troubleshooting, inspect existing routes before changing them:

```powershell
& 'C:\Program Files\Tailscale\tailscale.exe' status
& 'C:\Program Files\Tailscale\tailscale.exe' serve status
& 'C:\Program Files\Tailscale\tailscale.exe' funnel status
```

The companion expects one private HTTPS Serve route to `http://127.0.0.1:8787`, with no Funnel and no unrelated Serve routes on this node. On a dedicated node with no conflicting routes:

```powershell
& 'C:\Program Files\Tailscale\tailscale.exe' serve --bg http://127.0.0.1:8787
```

Do not reset routes used by other applications. Resolve conflicts first. Never expose the app-server or enable public Funnel for this app. Both devices must remain connected to Tailscale for private task/media access.

## 3. Choose your workspace

Open Codex Voice on Windows. Choose the project folder. Leave coding authorization off for read-only work, or explicitly authorize workspace writes for that project. Select **Start Companion**. The desktop verifies authentication and protocol settings before reporting Connected.

Use Conversations to select an original local Codex chat or create a companion-managed conversation. Cloud-only ChatGPT conversations are not discoverable through the local Codex server. Original chats owned by another Codex client use its native user-message queue; that client must process queued instructions.

## 4. Pair Android

On the desktop, open **Phone & calls → Pair phone**. In Android **Settings → Connection**, enter the displayed private endpoint and one-time invitation. The invitation expires after two minutes. Keep it private.

Pairing credentials stay on the devices. Revoke a phone from the desktop before replacing it. Forgetting a PC on Android removes its local credential; it does not revoke the PC's stored pairing.

## 5. Optional task alerts

Create your own Firebase project on Spark with billing unlinked, then register an Android app with package `com.codexvoice.app`.

- Download the Android `google-services.json` client file. Transfer it privately to your phone and choose **Settings → Task notifications → Import Android Firebase client** in a generic build. This imports client identifiers only. Turn notifications off/on afterward to register. An already active different Firebase client requires a generic build with cleared application storage and new pairing.
- Download a service-account JSON for that project. On Windows, stop Companion and choose **Choose Firebase credentials**. The app copies it into a dedicated current-user protected profile folder. Replacing an existing account requires confirmation in the app. Never put this server file on the phone or in Git.
- Confirm Spark/billing unlinked on the desktop before enabling task alerts. The phone and PC must use the same Firebase project. Grant Android notification permission and enable the desired task categories.

Client identifiers are saved in Android private, backup-excluded application storage. Every new APK build is generic: local `google-services.json` files are not embedded. Import the Android client through Settings after installing. The Windows and Android setup persists locally across ordinary restarts; no source-code edit or rebuild is needed for a user's initial configuration.

## 6. Calls and speech

Answering or placing an audio call requests microphone permission. Calls use Android's self-managed VoIP support; a cellular Phone permission is not required. On Android 14+, enable **Full-screen calls** in the app's Background & calls settings if desired. This is special access, separate from microphone permission.

Use an installed offline English text-to-speech voice. Recognition requires the downloaded local speech model on Windows. Battery optimization, notification channels, full-screen access, and manufacturer settings can affect incoming calls. Enable background use for both Codex Voice and Tailscale when needed.

Hang up ends microphone/media use; accepted Codex work can continue. **Stop all** ends the companion connection and owned services. Closing the desktop window keeps it in the tray; **Quit completely** exits it.
