param([switch]$MobileData, [switch]$Ui, [switch]$RealSnapshot)
$ErrorActionPreference = 'Stop'
$deviceRoot = Split-Path $PSScriptRoot -Parent
$tsExe = 'C:\Program Files\Tailscale\tailscale.exe'
$adbExe = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$previousRemote = $env:CODEX_VOICE_REMOTE_URL
$previousJava = $env:JAVA_HOME
$previousDeviceReal = $env:CODEX_VOICE_DEVICE_REAL
$fixtureProcess = $null; $instrumentProcess = $null; $routeChanged = $false
Push-Location -LiteralPath $deviceRoot
try {
  $devices = @(& $adbExe devices | Where-Object { $_ -match '^\S+\s+device$' })
  if ($devices.Count -ne 1) { throw 'Exactly one authorized Android device required' }
  $serial = ($devices[0] -split '\s+')[0]
  $wifiInitial = (& $adbExe -s $serial shell settings get global wifi_on).Trim()
  $dataInitial = (& $adbExe -s $serial shell settings get global mobile_data).Trim()
  if ($wifiInitial -notin @('0','1') -or $dataInitial -notin @('0','1')) { throw 'Cannot record original phone network settings' }
  $tsState = & $tsExe status --json | ConvertFrom-Json
  if ($tsState.BackendState -ne 'Running' -or -not $tsState.Self.DNSName) { throw 'Authenticated Tailscale required' }
  $dnsName = $tsState.Self.DNSName.TrimEnd('.')
  $serve = & $tsExe serve status --json | ConvertFrom-Json
  if (@($serve.Web.PSObject.Properties).Count -ne 1 -or @($serve.TCP.PSObject.Properties).Count -ne 1 -or
      $serve.TCP.'443'.HTTPS -ne $true -or $serve.AllowFunnel -or
      @($serve.Web."${dnsName}:443".Handlers.PSObject.Properties).Count -ne 1 -or
      $serve.Web."${dnsName}:443".Handlers.'/'.Proxy -ne 'http://127.0.0.1:8787') {
    throw 'Expected sole private Codex Voice Serve route; refusing unrelated configuration changes'
  }
  & .\scripts\run.ps1 build
  if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed' }
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  & .\android\gradlew.bat -p android :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
  if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
  & $adbExe -s $serial install -r android/app/build/outputs/apk/debug/app-debug.apk
  if ($LASTEXITCODE -ne 0) { throw 'App installation failed' }
  & $adbExe -s $serial install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
  $deviceDirectory = Join-Path $deviceRoot $(if ($RealSnapshot) { '.runtime\device-verification-real' } else { '.runtime\device-verification' })
  $fixturePath = Join-Path $deviceDirectory 'fixture.json'
  if (Test-Path -LiteralPath $fixturePath) { throw 'Previous owned fixture must be inspected' }
  $nodeExe = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
  $env:CODEX_VOICE_REMOTE_URL = "wss://$dnsName/remote"
  $env:CODEX_VOICE_DEVICE_REAL = if ($RealSnapshot) { '1' } else { '0' }
  $fixtureProcess = Start-Process -FilePath $nodeExe -ArgumentList 'dist/scripts/device-harness.js' -WorkingDirectory $deviceRoot -WindowStyle Hidden -PassThru
  $fixtureDeadline = [DateTime]::UtcNow.AddSeconds(20)
  while (-not (Test-Path -LiteralPath $fixturePath)) {
    if ($fixtureProcess.HasExited -or [DateTime]::UtcNow -gt $fixtureDeadline) { throw 'Device fixture failed to start' }
    Start-Sleep -Milliseconds 100
  }
  $metadata = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
  $metadata | Add-Member -NotePropertyName wifiInitiallyEnabled -NotePropertyValue ($wifiInitial -eq '1')
  $metadata | Add-Member -NotePropertyName dataInitiallyEnabled -NotePropertyValue ($dataInitial -eq '1')
  [IO.File]::WriteAllText($fixturePath, ($metadata | ConvertTo-Json -Compress))
  & $adbExe -s $serial push $fixturePath /data/local/tmp/codex-voice-fixture.json | Out-Null
  & $adbExe -s $serial shell chmod 600 /data/local/tmp/codex-voice-fixture.json
  & $tsExe serve --bg --https=443 http://127.0.0.1:8788
  if ($LASTEXITCODE -ne 0) { throw 'Scoped private fixture route failed' }
  $routeChanged = $true
  $outputPath = Join-Path $deviceDirectory $(if ($Ui) { 'monitor-ui.txt' } elseif ($RealSnapshot) { 'real-snapshot.txt' } else { 'private-route.txt' })
  $errorPath = Join-Path $deviceDirectory 'instrumentation-errors.txt'
  $mobileFlag = if ($MobileData) { 'true' } else { 'false' }
  $testClass = if ($RealSnapshot) { 'com.codexvoice.app.RealSnapshotTest' } elseif ($Ui) { 'com.codexvoice.app.PrivateMonitorUiTest' } else { 'com.codexvoice.app.PrivateRouteTest' }
  if ($Ui) {
    # Wake for UI testing. Android enforces any required secure unlock; no PIN is supplied.
    & $adbExe -s $serial shell input keyevent KEYCODE_WAKEUP
    & $adbExe -s $serial shell wm dismiss-keyguard
  }
  $instrumentProcess = Start-Process -FilePath $adbExe -ArgumentList @('-s',$serial,'shell','am','instrument','-w','-e','class',$testClass,'-e','privateRoute','true','-e','mobileData',$mobileFlag,'com.codexvoice.app.test/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -PassThru -RedirectStandardOutput $outputPath -RedirectStandardError $errorPath
  $deviceDeadline = [DateTime]::UtcNow.AddMinutes(6)
  while (-not $instrumentProcess.HasExited) {
    if ([DateTime]::UtcNow -gt $deviceDeadline) { throw 'Physical-route test deadline exceeded' }
    $deviceControl = & $adbExe -s $serial shell run-as com.codexvoice.app cat no_backup/device-verification/control.txt 2>$null
    if (-not $deviceControl) { Start-Sleep -Milliseconds 300; continue }
    $command = ([string]$deviceControl).Trim()
    if ($command -in @('complete','failure','waiting','interrupt','approval','decline','restart','revoke')) {
      & $adbExe -s $serial shell run-as com.codexvoice.app rm no_backup/device-verification/control.txt
      [IO.File]::WriteAllText((Join-Path $deviceDirectory 'control.txt'), $command)
      Write-Output "Physical route check: $command"
    }
    Start-Sleep -Milliseconds 300
  }
  $instrumentOutput = Get-Content -LiteralPath $outputPath -Raw
  Write-Output $instrumentOutput
  if ($instrumentProcess.ExitCode -ne 0 -or $instrumentOutput -notmatch 'OK \(1 test\)' -or $instrumentOutput -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Physical private-route test failed' }
} finally {
  if ($instrumentProcess -and -not $instrumentProcess.HasExited) { Stop-Process -Id $instrumentProcess.Id -Force; & $adbExe -s $serial shell am force-stop com.codexvoice.app }
  if ($fixtureProcess -and -not $fixtureProcess.HasExited) {
    [IO.File]::WriteAllText((Join-Path $deviceDirectory 'control.txt'), 'quit')
    if (-not $fixtureProcess.WaitForExit(10000)) { Stop-Process -Id $fixtureProcess.Id -Force }
  }
  if ($routeChanged) { & $tsExe serve --bg --https=443 http://127.0.0.1:8787 }
  if ($serial) {
    & $adbExe -s $serial shell rm -f /data/local/tmp/codex-voice-fixture.json /data/local/tmp/codex-voice-control.txt
    if ($MobileData) {
      & $adbExe -s $serial shell svc wifi $(if ($wifiInitial -eq '1') { 'enable' } else { 'disable' })
      & $adbExe -s $serial shell svc data $(if ($dataInitial -eq '1') { 'enable' } else { 'disable' })
      Start-Sleep -Seconds 2
      $wifiRestored = (& $adbExe -s $serial shell settings get global wifi_on).Trim()
      $dataRestored = (& $adbExe -s $serial shell settings get global mobile_data).Trim()
      if ($wifiRestored -ne $wifiInitial -or $dataRestored -ne $dataInitial) { Write-Warning 'Phone network preferences need manual review after restore' }
    }
  }
  $env:CODEX_VOICE_REMOTE_URL = $previousRemote; $env:JAVA_HOME = $previousJava
  $env:CODEX_VOICE_DEVICE_REAL = $previousDeviceReal
  Pop-Location
}
