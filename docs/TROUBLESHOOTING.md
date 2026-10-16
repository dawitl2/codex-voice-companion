# Troubleshooting

## Companion will not start

Check that the pinned Codex runtime is installed and signed in through ChatGPT. API keys and custom provider/auth environment variables are refused. Confirm your chosen project exists and Tailscale is running with the single private Serve route described in [Setup](SETUP.md).

If task alerts are enabled, select a protected Firebase service-account file and confirm Spark with billing unlinked. Disable optional alerts to use the companion without Firebase.

## “Malformed app-server protocol; connection closed”

Older desktop builds rejected valid original conversation responses above 4 MiB. Update to 1.1.6 or later. The local transport now permits bounded frames up to 32 MiB and reports an explicit size error beyond that limit. If the conversation exceeds the limit, select a smaller conversation in Codex and restart Companion. Do not delete Codex history or weaken remote protocol validation.

## Phone remains disconnected

Keep Tailscale connected on both devices and check tailnet access rules. The desktop must show Companion running and Codex connected. Enter the private endpoint exactly as displayed during pairing. Expired invitations require a new pairing request. Revoke an old device before replacing it; do not post invitations or tokens in an issue.

## Alerts are missing

The phone and desktop must use the same Firebase project. Import the Android client on a generic build, enable notifications and allow Android notification permission. Check the desktop's Firebase status. Battery optimization, force-stop, network availability and notification channel settings affect delivery.

## Calls show a banner

On Android 14+, review Full-screen calls under Background & calls. Enable call notifications and review background battery settings for Codex Voice and Tailscale. Android may deliberately use a banner when the phone is unlocked and another app is visible. A cellular Phone permission does not change that behavior.

## Speech or sending is unavailable

Install the local speech assets on Windows and an offline English voice on Android. Check microphone permission. A conversation owned by another Codex client must be open in that client to process its native queued instruction. Do not resend an uncertain instruction automatically; inspect the original conversation first.

## Updating the app

Quit Completely on Windows before installing a new desktop package. On Android use an update signed with the same key to preserve pairing. Clearing app storage removes pairing and configuration. A source-only public copy never contains your local state, credentials or personal configured packages.
