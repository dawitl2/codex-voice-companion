param(
  [ValidateSet('start','dev','build','typecheck','test','test:real','check:connection','install')]
  [string]$Action = 'start',
  [Parameter(ValueFromRemainingArguments=$true)] [string[]]$BridgeArgs
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
$runtimeCandidates = @($env:CODEX_VOICE_NODE_PATH,
  "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe",
  (Get-Command node -ErrorAction SilentlyContinue).Source) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
$nodeRuntime = $null
foreach ($candidate in $runtimeCandidates) {
  $detectedVersion = & $candidate --version
  if ($detectedVersion -match '^v24\.') { $nodeRuntime = $candidate; break }
}
if (-not $nodeRuntime) { throw 'Node 24 LTS is required. Install it from https://nodejs.org/en/download or set CODEX_VOICE_NODE_PATH; system Node was not changed.' }
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCommand) { throw 'npm is missing; use the official Node 24 distribution.' }
$npmCli = Join-Path (Split-Path $npmCommand.Source -Parent) 'node_modules\npm\bin\npm-cli.js'
if (-not (Test-Path $npmCli)) { throw 'Cannot resolve npm CLI beside npm.cmd.' }
$env:PATH = (Split-Path $nodeRuntime -Parent) + ';' + $env:PATH
if ($Action -eq 'install') { & $nodeRuntime $npmCli ci --ignore-scripts }
else { & $nodeRuntime $npmCli run $Action -- @BridgeArgs }
exit $LASTEXITCODE
