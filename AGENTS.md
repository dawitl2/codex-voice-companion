# Codex Voice development

Work on the requested scope. Keep the Windows bridge and native Android app in
this repository. Identify demo features as simulated. Use supported Codex
authentication and free dependencies; never add a paid API fallback, bypass
approvals or expose the privileged app-server to the network.

Keep user configuration in local application storage. Do not commit credentials,
pairing data, local.properties, signing keys, runtime history, personal acceptance
reports, recordings, caches or generated applications. Review staged files and
published history for privacy before publication.

Use meaningful Conventional Commits. Preserve source evolution. Never force-push
or rewrite published history without explicit owner authorization. Publish only
to the repository and visibility authorized by its owner.

Use focused verification appropriate to the change and respect the owner's
requested test scope. Bridge regression is `.\scripts\run.ps1 test`; Android
checks are documented in `docs/BUILDING.md`. Report unit tests, instrumentation,
actual device behavior and checks not run separately.

Keep the native light/dark interface concise: neutral colors, a centered code
mark, green/red call controls, simple tabs, grouped settings and restrained corner
radii. Preserve explicit microphone consent and distinguish incoming ringing
from the PC diagnostic audio tone.
