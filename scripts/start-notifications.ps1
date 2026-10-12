param([ValidateSet('mock','real')][string]$Mode = 'mock', [switch]$SparkConfirmed, [switch]$Calls, [switch]$Speech, [switch]$Conversation, [switch]$WorkspaceWrite,
    [string]$CredentialPath = "$env:USERPROFILE\.codex-voice\firebase\service-account.json")
$ErrorActionPreference = 'Stop'
$notificationRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $notificationRoot
$previousNotificationEnvironment = @{}
foreach ($variableName in @('CODEX_VOICE_FIREBASE_PROJECT_ID','GOOGLE_APPLICATION_CREDENTIALS','CODEX_VOICE_FIREBASE_SPARK_CONFIRMED','CODEX_VOICE_REMOTE_URL','CODEX_VOICE_REMOTE_PORT')) {
    $previousNotificationEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
}
try {
    if (-not $SparkConfirmed) { throw 'Use -SparkConfirmed only after confirming Spark, billing unlinked and Analytics disabled in your Firebase project.' }
    $clientPath = Join-Path $notificationRoot 'android\app\google-services.json'
    if (-not (Test-Path -LiteralPath $clientPath)) { throw 'Place matching local client configuration at android/app/google-services.json first' }
    if (-not (Test-Path -LiteralPath $CredentialPath)) { throw 'Server service-account JSON is missing; finish the manual Firebase setup first' }
    $clientConfiguration = Get-Content -LiteralPath $clientPath -Raw | ConvertFrom-Json
    if (@($clientConfiguration.client | Where-Object { $_.client_info.android_client_info.package_name -eq 'com.codexvoice.app' }).Count -ne 1) { throw 'Android Firebase package does not match' }
    & .\scripts\protect-firebase-credentials.ps1 -Directory (Split-Path ([IO.Path]::GetFullPath($CredentialPath)) -Parent)
    $tsExecutable = 'C:\Program Files\Tailscale\tailscale.exe'
    $tailnet = & $tsExecutable status --json | ConvertFrom-Json
    if ($tailnet.BackendState -ne 'Running' -or -not $tailnet.Self.DNSName) { throw 'Sign in to Tailscale first' }
    $env:CODEX_VOICE_FIREBASE_PROJECT_ID = $clientConfiguration.project_info.project_id
    $env:GOOGLE_APPLICATION_CREDENTIALS = [IO.Path]::GetFullPath($CredentialPath)
    $env:CODEX_VOICE_FIREBASE_SPARK_CONFIRMED = '1'
    $env:CODEX_VOICE_REMOTE_URL = 'wss://' + $tailnet.Self.DNSName.TrimEnd('.') + '/remote'
    $env:CODEX_VOICE_REMOTE_PORT = '8787'
    & .\scripts\run.ps1 build
    if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed' }
    $bridgeArguments = @("--$Mode", '--remote', '--notifications')
    if ($Calls -or $Speech -or $Conversation) { $bridgeArguments += '--calls' }
    if ($Speech -or $Conversation) { $bridgeArguments += '--speech' }
    if ($Conversation) { $bridgeArguments += '--conversation' }
    if ($WorkspaceWrite) { $bridgeArguments += '--workspace-write' }
    & .\scripts\run.ps1 start @bridgeArguments
    if ($LASTEXITCODE -ne 0) { throw 'Notification bridge startup failed; inspect the fixed diagnostic above' }
} finally {
    foreach ($variableName in $previousNotificationEnvironment.Keys) { [Environment]::SetEnvironmentVariable($variableName, $previousNotificationEnvironment[$variableName], 'Process') }
    Pop-Location
}
