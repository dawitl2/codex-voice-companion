# Security and privacy

## Trust boundaries

Codex Voice trusts the signed-in Windows account, the paired phone, and the private Tailscale connection. It is not a protection against malware running as that Windows user, a compromised phone, or an administrator with local access.

- The Codex app-server stays on private stdio. Only the narrow companion protocol is reachable through private Tailscale HTTPS. Loopback binding, host/proxy validation, pairing and application authentication remain required. Public Funnel and direct public bridge endpoints are unsupported.
- An invitation is one-use and expires after two minutes. Pairing attempts are bounded. The Windows registry uses CurrentUser DPAPI; Android credentials use Keystore-backed encryption and backup-excluded storage. Desktop revocation ends access.
- The owned Codex child uses eligible ChatGPT authentication and refuses API-key/custom-provider billing paths. Inherited integrations are disabled. New managed work defaults to read-only; project write authorization and action approvals are separate decisions. Original observed chats retain their original client's permissions.
- Phone approval requires desktop nomination of an eligible exact action, a compared trusted reviewer key and fresh Android system authentication. Voice cannot approve execution. Uncertain submissions are not automatically replayed.

## Audio and notifications

Microphone capture requires an explicit visible call action and Android permission. Foreground indicators remain visible during capture. Audio travels through the private call connection to local English recognition; the recognition worker receives an in-memory WAV rather than a recording file. Android uses installed offline TTS voices. Hang-up releases media resources; accepted Codex tasks may continue.

Optional Firebase alerts carry signed minimal event/category/state metadata. They do not carry microphone audio, full prompts, commands, transcripts or response bodies. Details require authenticated retrieval from the PC. Firebase still processes delivery metadata, and Codex still communicates with OpenAI under the user's account.

Android controls background execution and incoming-call presentation. Full-screen call access does not guarantee takeover while another app is being used. No accessibility service, cellular call-log permission or overlay privilege is required.

## Local data

Windows settings and history live under the current user's `.codex-voice` folder; development runs may also use ignored `.runtime` storage. Device registries are DPAPI protected. Task-history JSON is plaintext in a current-user restricted directory. Use normal Windows account and disk protection and secure your backups.

The desktop Firebase importer creates a dedicated current-user protected profile folder and copies a service-account file there. Replacing an existing account requires confirmation while Companion is stopped. Android imports only its own Firebase client identifiers, never a server service-account key. Backup is disabled; client identifiers are local application data. Every APK build is generic. Switching an active Firebase project requires clearing app data and pairing again.

Do not commit or share Codex authentication, service accounts, Android client configuration, local.properties, signing keys, pairing invitations, device registries, runtime history, recordings, screenshots of private conversations, or personal configured packages. Ignore rules reduce accidents; review staged changes and history before publishing.

## Reporting a problem

Use a private security reporting channel offered by your repository host when available. Do not put credentials, pairing codes, personal transcripts or full logs in a public issue. Revoke exposed phone pairings and rotate leaked credentials at their issuing service. Removing a file from a later commit does not remove it from history.

No blanket security certification is claimed. Source review, builds, automated tests and actual device checks provide different evidence and should be reported separately.
