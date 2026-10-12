param([switch]$MobileData, [switch]$Ui, [switch]$RealSnapshot, [switch]$Notifications, [switch]$ProcessAbsent, [switch]$Calls, [switch]$QuickAudio, [switch]$Speech)
$ErrorActionPreference = 'Stop'
$deviceRoot = Split-Path $PSScriptRoot -Parent
$tsExe = 'C:\Program Files\Tailscale\tailscale.exe'
$adbExe = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$previousRemote = $env:CODEX_VOICE_REMOTE_URL
$previousJava = $env:JAVA_HOME
$previousDeviceReal = $env:CODEX_VOICE_DEVICE_REAL
$previousDevicePush = $env:CODEX_VOICE_DEVICE_PUSH
$previousDeviceCall = $env:CODEX_VOICE_DEVICE_CALL
$previousDeviceSpeech = $env:CODEX_VOICE_DEVICE_SPEECH
$previousFirebaseProject = $env:CODEX_VOICE_FIREBASE_PROJECT_ID
$previousFirebaseCredentials = $env:GOOGLE_APPLICATION_CREDENTIALS
$previousSparkConfirmation = $env:CODEX_VOICE_FIREBASE_SPARK_CONFIRMED
$fixtureProcess = $null; $instrumentProcess = $null; $routeChanged = $false
Push-Location -LiteralPath $deviceRoot
try {
  if ($Speech) {
    if (-not $RealSnapshot -or $Notifications -or $MobileData -or $QuickAudio) { throw '-Speech requires -RealSnapshot and a focused foreground session' }
    $Calls = $true
  }
  if ($Notifications -and ($Ui -or $RealSnapshot)) { throw 'Notification fixture is separate from UI/real-history fixtures' }
  if ($ProcessAbsent -and -not $Notifications) { throw '-ProcessAbsent requires -Notifications' }
  if ($Calls -and -not $Speech -and (-not $Notifications -or $ProcessAbsent)) { throw '-Calls requires -Notifications and is separate from process-absence tests' }
  if ($QuickAudio -and (-not $Calls -or $MobileData)) { throw '-QuickAudio requires -Calls and omits the mobile network matrix' }
  if ($Notifications) {
    $configuration = Get-Content -LiteralPath 'android/app/google-services.json' -Raw | ConvertFrom-Json
    $env:CODEX_VOICE_FIREBASE_PROJECT_ID = $configuration.project_info.project_id
    $env:GOOGLE_APPLICATION_CREDENTIALS = "$env:USERPROFILE\.codex-voice\firebase\service-account.json"
    # This test flag requires the user's prior explicit Spark/no-billing confirmation.
    if ($env:CODEX_VOICE_FIREBASE_SPARK_CONFIRMED -ne '1') { throw 'Confirm Spark with billing unlinked before enabling physical FCM tests' }
    & .\scripts\protect-firebase-credentials.ps1
  }
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
  function Install-VerifiedArtifact([string]$Package, [string]$Apk) {
    $packagePath = ((& $adbExe -s $serial shell pm path $Package | Select-Object -First 1) -replace '^package:', '').Trim()
    $localHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
    if ($packagePath -match '^/data/app/[A-Za-z0-9_~+/.=-]+/base\.apk$') {
      $installedHash = ((& $adbExe -s $serial shell sha256sum $packagePath) -split '\s+')[0]
      if ($installedHash -eq $localHash) { Write-Output "Installed $Package APK already matches the verified build."; return }
    }
    & $adbExe -s $serial install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw 'Fixture APK installation failed' }
  }
  Install-VerifiedArtifact 'com.codexvoice.app' 'android/app/build/outputs/apk/debug/app-debug.apk'
  Install-VerifiedArtifact 'com.codexvoice.app.test' 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
  $deviceDirectory = Join-Path $deviceRoot $(if ($RealSnapshot) { '.runtime\device-verification-real' } else { '.runtime\device-verification' })
  $fixturePath = Join-Path $deviceDirectory 'fixture.json'
  if (Test-Path -LiteralPath $fixturePath) { throw 'Previous owned fixture must be inspected' }
  $nodeExe = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
  $env:CODEX_VOICE_REMOTE_URL = "wss://$dnsName/remote"
  $env:CODEX_VOICE_DEVICE_REAL = if ($RealSnapshot) { '1' } else { '0' }
  $env:CODEX_VOICE_DEVICE_PUSH = if ($Notifications) { '1' } else { '0' }
  $env:CODEX_VOICE_DEVICE_CALL = if ($Calls) { '1' } else { '0' }
  $env:CODEX_VOICE_DEVICE_SPEECH = if ($Speech) { '1' } else { '0' }
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
  $outputPath = Join-Path $deviceDirectory $(if ($Speech) { 'speech-session.txt' } elseif ($Calls) { 'audio-session.txt' } elseif ($Notifications) { 'firebase-delivery.txt' } elseif ($Ui) { 'monitor-ui.txt' } elseif ($RealSnapshot) { 'real-snapshot.txt' } else { 'private-route.txt' })
  $errorPath = Join-Path $deviceDirectory 'instrumentation-errors.txt'
  $mobileFlag = if ($MobileData) { 'true' } else { 'false' }
  $testClass = if ($Speech) { 'com.codexvoice.app.SpeechCallTest' } elseif ($Calls) { 'com.codexvoice.app.AudioSessionTest' } elseif ($ProcessAbsent) { 'com.codexvoice.app.FirebaseProcessTest' } elseif ($Notifications) { 'com.codexvoice.app.FirebaseDeliveryTest' } elseif ($RealSnapshot) { 'com.codexvoice.app.RealSnapshotTest' } elseif ($Ui) { 'com.codexvoice.app.PrivateMonitorUiTest' } else { 'com.codexvoice.app.PrivateRouteTest' }
  if ($Ui -or $Notifications -or $Speech) {
    # Wake for UI testing. Android enforces any required secure unlock; no PIN is supplied.
    & $adbExe -s $serial shell input keyevent KEYCODE_WAKEUP
    & $adbExe -s $serial shell wm dismiss-keyguard
  }
  $instrumentArguments = @('-s',$serial,'shell','am','instrument','-w','-e','class',$testClass,'-e','privateRoute','true','-e','mobileData',$mobileFlag)
  if ($Calls) { $audioRunId = [guid]::NewGuid().ToString(); $instrumentArguments += @('-e','audioRunId',$audioRunId) }
  if ($QuickAudio) { $instrumentArguments += @('-e','quickAudio','true') }
  if ($ProcessAbsent) { $instrumentArguments += @('-e','processPhase','setup') }
  $instrumentArguments += 'com.codexvoice.app.test/androidx.test.runner.AndroidJUnitRunner'
  $instrumentProcess = Start-Process -FilePath $adbExe -ArgumentList $instrumentArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $outputPath -RedirectStandardError $errorPath
  $deviceDeadline = [DateTime]::UtcNow.AddMinutes($(if ($Notifications) { 12 } else { 6 }))
  while (-not $instrumentProcess.HasExited) {
    if ([DateTime]::UtcNow -gt $deviceDeadline) { throw 'Physical-route test deadline exceeded' }
    if (($Notifications -or $Speech) -and (Test-Path -LiteralPath (Join-Path $deviceDirectory 'push-status.json'))) {
      & $adbExe -s $serial push (Join-Path $deviceDirectory 'push-status.json') /data/local/tmp/codex-voice-push-status.json 2>$null | Out-Null
      & $adbExe -s $serial shell chmod 600 /data/local/tmp/codex-voice-push-status.json
    }
    $deviceControl = & $adbExe -s $serial shell run-as com.codexvoice.app cat no_backup/device-verification/control.txt 2>$null
    if (-not $deviceControl) { Start-Sleep -Milliseconds 300; continue }
    $command = ([string]$deviceControl).Trim()
    if ($command -in @('offer','complete','failure','waiting','interrupt','approval','decline','restart','revoke','offline','online')) {
      & $adbExe -s $serial shell run-as com.codexvoice.app rm no_backup/device-verification/control.txt
      [IO.File]::WriteAllText((Join-Path $deviceDirectory 'control.txt'), $command)
      Write-Output "Physical route check: $command"
    }
    Start-Sleep -Milliseconds 300
  }
  $instrumentOutput = Get-Content -LiteralPath $outputPath -Raw
  Write-Output $instrumentOutput
  if ($instrumentProcess.ExitCode -ne 0 -or $instrumentOutput -notmatch 'OK \(1 test\)' -or $instrumentOutput -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Physical private-route test failed' }
  if ($Speech) {
    $speechProof = & $adbExe -s $serial shell run-as com.codexvoice.app cat no_backup/device-verification/speech-result.json 2>$null
    if (-not $speechProof -or ($speechProof | ConvertFrom-Json).runId -ne $audioRunId) { throw 'Speech proof does not match this execution' }
    $speechProof | Set-Content -LiteralPath (Join-Path $deviceDirectory 'speech-result.json')
  } elseif ($Calls) {
    $audioProof = & $adbExe -s $serial shell run-as com.codexvoice.app cat no_backup/device-verification/audio-result.json 2>$null
    if (-not $audioProof) { throw 'Audio checks did not execute; a personal pairing may have prevented fixture testing' }
    if (($audioProof | ConvertFrom-Json).runId -ne $audioRunId) { throw 'Audio proof does not match this execution' }
    $audioProof | Set-Content -LiteralPath (Join-Path $deviceDirectory 'audio-result.json')
  }
  if ($ProcessAbsent) {
    $proof = & $adbExe -s $serial shell run-as com.codexvoice.app cat no_backup/device-verification/process-test-proof.json 2>$null
    if (-not $proof) { throw 'Process fixture did not prepare; personal pairing may have prevented testing' }
    & $adbExe -s $serial shell am kill com.codexvoice.app
    $absenceDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
      $phoneProcesses = @(& $adbExe -s $serial shell ps -A -o NAME | Where-Object { $_ -match '^com\.codexvoice\.app(?::.+)?$' })
      if ($phoneProcesses.Count -eq 0) { break }
      Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $absenceDeadline)
    if ($phoneProcesses.Count -ne 0) { throw 'App process remains present; force-stop will not be used' }
    Write-Output 'Verified: app process absent before FCM send; no force-stop.'
    [IO.File]::WriteAllText((Join-Path $deviceDirectory 'control.txt'), 'complete')
    $receiptDeadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
      $status = Get-Content -LiteralPath (Join-Path $deviceDirectory 'push-status.json') -Raw | ConvertFrom-Json
      & $adbExe -s $serial push (Join-Path $deviceDirectory 'push-status.json') /data/local/tmp/codex-voice-push-status.json 2>$null | Out-Null
      $newProcess = & $adbExe -s $serial shell pidof com.codexvoice.app
      if ($newProcess -and @($status.deliveries | Where-Object status -eq accepted).Count -gt 0) { break }
      Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $receiptDeadline)
    if (-not $newProcess) { throw 'FCM did not start the absent app within the deadline' }
    Write-Output 'Verified: Android started a new app process after the targeted FCM send.'
    $verifyOutput = & $adbExe -s $serial shell am instrument -w -e class $testClass -e privateRoute true -e processPhase verify com.codexvoice.app.test/androidx.test.runner.AndroidJUnitRunner
    $verifyOutput | Tee-Object -FilePath (Join-Path $deviceDirectory 'firebase-process-verify.txt')
    if (($verifyOutput -join "`n") -notmatch 'OK \(1 test\)' -or ($verifyOutput -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Absent-process receipt/notification verification failed' }
  }
} finally {
  if ($instrumentProcess -and -not $instrumentProcess.HasExited) { Stop-Process -Id $instrumentProcess.Id -Force; & $adbExe -s $serial shell am force-stop com.codexvoice.app }
  if ($ProcessAbsent -and $serial) {
    & $adbExe -s $serial shell am instrument -w -e class com.codexvoice.app.FirebaseProcessTest -e privateRoute true -e processPhase cleanup com.codexvoice.app.test/androidx.test.runner.AndroidJUnitRunner | Out-Null
  }
  if ($fixtureProcess -and -not $fixtureProcess.HasExited) {
    [IO.File]::WriteAllText((Join-Path $deviceDirectory 'control.txt'), 'quit')
    if (-not $fixtureProcess.WaitForExit(10000)) { Stop-Process -Id $fixtureProcess.Id -Force }
  }
  if ($routeChanged) { & $tsExe serve --bg --https=443 http://127.0.0.1:8787 }
  if ($serial) {
    & $adbExe -s $serial shell rm -f /data/local/tmp/codex-voice-fixture.json /data/local/tmp/codex-voice-control.txt /data/local/tmp/codex-voice-push-status.json
    if ($MobileData -or $Notifications) {
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
  $env:CODEX_VOICE_DEVICE_PUSH = $previousDevicePush
  $env:CODEX_VOICE_DEVICE_CALL = $previousDeviceCall
  $env:CODEX_VOICE_DEVICE_SPEECH = $previousDeviceSpeech
  $env:CODEX_VOICE_FIREBASE_PROJECT_ID = $previousFirebaseProject
  $env:GOOGLE_APPLICATION_CREDENTIALS = $previousFirebaseCredentials
  $env:CODEX_VOICE_FIREBASE_SPARK_CONFIRMED = $previousSparkConfirmation
  Pop-Location
}
