param(
    [ValidateSet('check','start','status')][string]$Action = 'start',
    [ValidateSet('real','mock')][string]$Mode = 'real',
    [switch]$SparkConfirmed, [switch]$WithoutNotifications,
    [string]$CredentialPath = "$env:USERPROFILE\.codex-voice\firebase\service-account.json"
)
$ErrorActionPreference = 'Stop'
$companionRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $companionRoot
try {
    $nodeCandidates = @($env:CODEX_VOICE_NODE_PATH, "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe", (Get-Command node -ErrorAction SilentlyContinue).Source)
    $nodeExe = $nodeCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) -and (& $_ --version) -match '^v24\.' } | Select-Object -First 1
    if (-not $nodeExe) { throw 'Node 24 is required. Set CODEX_VOICE_NODE_PATH to your installed node.exe.' }
    if (-not (Test-Path -LiteralPath 'node_modules/typescript')) { throw 'Run .\scripts\run.ps1 install first.' }
    $tsExe = 'C:\Program Files\Tailscale\tailscale.exe'
    if (-not (Test-Path -LiteralPath $tsExe)) { throw 'Install Tailscale and sign in; ordinary launch does not install or elevate it.' }
    $tailnet = & $tsExe status --json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $tailnet.BackendState -ne 'Running' -or -not $tailnet.Self.DNSName) { throw 'Sign in to Tailscale on the PC and phone.' }
    $serve = & $tsExe serve status --json | ConvertFrom-Json
    $hostPort = $tailnet.Self.DNSName.TrimEnd('.') + ':443'
    if (@($serve.Web.PSObject.Properties).Count -ne 1 -or @($serve.TCP.PSObject.Properties).Count -ne 1 -or $serve.TCP.'443'.HTTPS -ne $true -or $serve.AllowFunnel -or
        @($serve.Web.$hostPort.Handlers.PSObject.Properties).Count -ne 1 -or $serve.Web.$hostPort.Handlers.'/'.Proxy -ne 'http://127.0.0.1:8787') {
        throw 'Expected sole private HTTPS Serve route to http://127.0.0.1:8787 and no Funnel. Review Tailscale settings manually.'
    }
    $listeners = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 8787)
    if ($Action -eq 'status') {
        [pscustomobject]@{node24=$true; tailscaleRunning=$true; privateServe=$true; port8787Listening=($listeners.Count -gt 0); loopbackOnly=($listeners.Count -gt 0 -and @($listeners | Where-Object { $_.Address.ToString() -ne '127.0.0.1' }).Count -eq 0); mode=$Mode}
        Write-Output 'Listener presence does not prove bridge authentication or task health. Use status, diagnostics, notification-status and call-status in its terminal.'
        return
    }
    if ($listeners.Count -gt 0) { throw 'Port 8787 is occupied. Use exit in the owning bridge terminal; inspect the listener PID before any manual process termination.' }
    & .\scripts\protect-runtime.ps1
    $codexExe = Join-Path $companionRoot '.cache\codex-runtime\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe'
    if ($Mode -eq 'real') {
        if (-not (Test-Path -LiteralPath $codexExe) -or (& $codexExe --version).Trim() -ne 'codex-cli 0.160.0') { throw 'Run .\scripts\setup-codex.ps1 for the verified local Codex 0.160.0 runtime.' }
        # Codex writes successful login status to stderr; Windows PowerShell 5 treats it as an ErrorRecord.
        $savedErrorPreference = $ErrorActionPreference
        try { $ErrorActionPreference = 'Continue'; $loginState = (& $codexExe login status 2>&1 | Out-String); $loginExit = $LASTEXITCODE }
        finally { $ErrorActionPreference = $savedErrorPreference }
        if ($loginExit -ne 0 -or $loginState -notmatch 'Logged in using ChatGPT') { throw 'Run the local codex.exe login interactively with your existing ChatGPT account. API-key mode is unsupported.' }
        $loginState = $null
    }
    $modelPath = Join-Path $companionRoot '.cache\speech\models\ggml-tiny.en.bin'
    $whisperPath = Join-Path $companionRoot '.cache\speech\whisper-b5454\Release\whisper-cli.exe'
    if (-not (Test-Path -LiteralPath $whisperPath) -or -not (Test-Path -LiteralPath $modelPath)) { throw 'Run .\scripts\setup-speech.ps1 to prepare the pinned local speech assets.' }
    $modelStream = [IO.File]::OpenRead($modelPath); $modelHasher = [Security.Cryptography.SHA256]::Create()
    try { $modelDigest = [BitConverter]::ToString($modelHasher.ComputeHash($modelStream)).Replace('-', '').ToLowerInvariant() }
    finally { $modelStream.Dispose(); $modelHasher.Dispose() }
    if ($modelDigest -ne '921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f') { throw 'Speech model verification failed. Restore the pinned model using setup-speech.ps1.' }
    if (-not $WithoutNotifications) {
        if (-not $SparkConfirmed) { throw 'Use -SparkConfirmed after your Firebase Spark/no-billing confirmation, or -WithoutNotifications.' }
        $credentialAbsolute = [IO.Path]::GetFullPath($CredentialPath)
        if ($credentialAbsolute.StartsWith($companionRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Server credentials must be outside the Git repository.' }
        if (-not (Test-Path -LiteralPath $credentialAbsolute) -or -not (Test-Path -LiteralPath 'android/app/google-services.json')) { throw 'Provide the protected server JSON outside Git and the ignored android/app/google-services.json.' }
        $serverConfiguration = Get-Content -LiteralPath $credentialAbsolute -Raw | ConvertFrom-Json
        $clientConfiguration = Get-Content -LiteralPath 'android/app/google-services.json' -Raw | ConvertFrom-Json
        if ($serverConfiguration.type -ne 'service_account' -or -not $serverConfiguration.private_key -or $serverConfiguration.project_id -ne $clientConfiguration.project_info.project_id) { throw 'Firebase server and Android project configuration do not match.' }
        if (@($clientConfiguration.client | Where-Object { $_.client_info.android_client_info.package_name -eq 'com.codexvoice.app' }).Count -ne 1) { throw 'Firebase Android package mismatch.' }
        $credentialAcl = if ($PSVersionTable.PSEdition -eq 'Core') { Get-Acl -LiteralPath $credentialAbsolute } else { [IO.File]::GetAccessControl($credentialAbsolute) }
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        foreach ($entry in $credentialAcl.Access) {
            if ($entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @($currentSid, 'S-1-5-18', 'S-1-5-32-544')) { throw 'Firebase credentials permit another principal. Review protect-firebase-credentials.ps1 before launch.' }
        }
        $serverConfiguration = $null; $clientConfiguration = $null
    }
    & .\scripts\run.ps1 build
    if ($LASTEXITCODE -ne 0) { throw 'TypeScript build failed.' }
    if ($Mode -eq 'real') {
        # Auth, model availability and isolated settings are checked through supported app-server methods; no inference.
        & .\scripts\run.ps1 check:connection
        if ($LASTEXITCODE -ne 0) { throw 'Codex app-server authentication/configuration check failed. No turn was submitted.' }
    }
    Write-Output 'Preflight passed: local runtime, speech model, private route, available port and selected configuration. No inference submitted.'
    if ($Action -eq 'check') { return }
    if (-not $WithoutNotifications) { & .\scripts\start-notifications.ps1 -Mode $Mode -SparkConfirmed -Controls -CredentialPath $CredentialPath }
    else {
        $priorUrl = $env:CODEX_VOICE_REMOTE_URL; $priorPort = $env:CODEX_VOICE_REMOTE_PORT
        try {
            $env:CODEX_VOICE_REMOTE_URL = 'wss://' + $tailnet.Self.DNSName.TrimEnd('.') + '/remote'; $env:CODEX_VOICE_REMOTE_PORT = '8787'
            & .\scripts\run.ps1 start "--$Mode" --remote --calls --speech --conversation --controls
        } finally { $env:CODEX_VOICE_REMOTE_URL = $priorUrl; $env:CODEX_VOICE_REMOTE_PORT = $priorPort }
    }
    if ($LASTEXITCODE -ne 0) { throw 'Bridge exited with an error; use the fixed local diagnostic.' }
} finally { Pop-Location }
