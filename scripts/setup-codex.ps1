# Installs only the bridge's pinned CLI in its ignored project cache. No global replacement/login.
$ErrorActionPreference = 'Stop'
$setupRoot = Split-Path $PSScriptRoot -Parent
$setupNode = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
if (-not $setupNode) { $setupNode = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe" }
if ($env:CODEX_VOICE_NODE_PATH) { $setupNode = $env:CODEX_VOICE_NODE_PATH }
if (-not (Test-Path -LiteralPath $setupNode) -or (& $setupNode --version) -notmatch '^v24\.') { throw 'Set CODEX_VOICE_NODE_PATH to Node 24' }
$setupNpm = Join-Path (Split-Path (Get-Command npm.cmd).Source -Parent) 'node_modules\npm\bin\npm-cli.js'
$setupCache = Join-Path $setupRoot '.cache\codex-runtime'
& $setupNode $setupNpm install --prefix $setupCache --ignore-scripts --no-audit --no-fund --save-exact '@openai/codex@0.160.0'
if ($LASTEXITCODE -ne 0) { throw 'Pinned official npm package installation failed' }
$setupNative = Join-Path $setupCache 'node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe'
if (-not (Test-Path -LiteralPath $setupNative) -or (& $setupNative --version).Trim() -ne 'codex-cli 0.160.0') { throw 'Pinned native binary verification failed' }
Write-Output 'Pinned Codex 0.160.0 is available in the ignored project cache. Existing desktop/VS Code binaries and login are unchanged.'
