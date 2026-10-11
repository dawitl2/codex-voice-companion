$ErrorActionPreference = 'Stop'
$integrationRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $integrationRoot
$previousFixture = $env:CODEX_VOICE_PROTOCOL_FIXTURE
$previousJava = $env:JAVA_HOME
$fixtureProcess = $null
try {
  & .\scripts\run.ps1 build
  if ($LASTEXITCODE -ne 0) { throw 'TypeScript build failed' }
  $integrationNodes = @($env:CODEX_VOICE_NODE_PATH,
    "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe",
    (Get-Command node -ErrorAction SilentlyContinue).Source) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
  $integrationNode = $integrationNodes | Where-Object { (& $_ --version) -match '^v24\.' } | Select-Object -First 1
  if (-not $integrationNode) { throw 'Node 24 required' }
  $fixturePath = Join-Path $integrationRoot '.runtime\protocol-integration\fixture.json'
  if (Test-Path -LiteralPath $fixturePath) { throw 'Old integration fixture exists; inspect its owned process before retrying' }
  $fixtureProcess = Start-Process -FilePath $integrationNode -ArgumentList 'dist/scripts/protocol-harness.js' -WorkingDirectory $integrationRoot -WindowStyle Hidden -PassThru
  $fixtureDeadline = [DateTime]::UtcNow.AddSeconds(20)
  while (-not (Test-Path -LiteralPath $fixturePath)) {
    if ($fixtureProcess.HasExited -or [DateTime]::UtcNow -gt $fixtureDeadline) { throw 'Offline fixture did not start' }
    Start-Sleep -Milliseconds 100
  }
  $env:CODEX_VOICE_PROTOCOL_FIXTURE = $fixturePath
  if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
  Push-Location -LiteralPath (Join-Path $integrationRoot 'android')
  try {
    & .\gradlew.bat :app:testDebugUnitTest --tests com.codexvoice.app.ProtocolIntegrationTest --offline --console=plain --rerun-tasks
    $integrationExit = $LASTEXITCODE
  } finally { Pop-Location }
  if ($integrationExit -ne 0) { throw "Protocol integration failed (exit $integrationExit)" }
} finally {
  if ($fixtureProcess -and -not $fixtureProcess.HasExited) {
    $controlPath = Join-Path $integrationRoot '.runtime\protocol-integration\control.txt'
    [IO.File]::WriteAllText($controlPath, 'quit')
    if (-not $fixtureProcess.WaitForExit(5000)) { Stop-Process -Id $fixtureProcess.Id -Force }
  }
  $env:CODEX_VOICE_PROTOCOL_FIXTURE = $previousFixture; $env:JAVA_HOME = $previousJava
  Pop-Location
}
