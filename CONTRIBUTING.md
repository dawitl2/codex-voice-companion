# Contributing

Keep the Windows bridge and Android app in this repository. Start with [Building](docs/BUILDING.md) and [Architecture](docs/ARCHITECTURE.md).

Use small focused changes and meaningful Conventional Commits. Preserve explicit microphone consent, supported Codex authentication, strict phone protocol validation and Codex approvals. Demo behavior must remain clearly simulated. Never add paid API fallback or expose raw app-server RPC.

Before opening a pull request, review every staged file for credentials and personal state. Use generic fixtures, never real pairing codes, private conversation exports or device identifiers. Keep generated packages, caches and signing material out of Git.

Describe the resulting behavior, relevant build/runtime evidence and checks not run. Report bridge, Android unit, instrumentation and physical-device checks separately. Existing tests should remain available. Keep UI changes concise, accessible and consistent across light/dark themes.
