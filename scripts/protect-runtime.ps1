$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -eq 'Core') {
    & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -File $PSCommandPath
    if ($LASTEXITCODE -ne 0) { throw 'Runtime ACL protection failed.' }
    return
}
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime'))
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$runtimeItems = @((Get-Item -LiteralPath $runtimeRoot)) + @(Get-ChildItem -LiteralPath $runtimeRoot -Recurse -Force)
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
foreach ($item in $runtimeItems) {
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or (-not $item.FullName.StartsWith($runtimeRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and $item.FullName -ne $runtimeRoot)) { throw 'Runtime path/reparse check failed; no ACL changes applied.' }
}
$changes = @($runtimeItems | Where-Object {
    $acl = Get-Acl -LiteralPath $_.FullName
    -not $acl.AreAccessRulesProtected -or @($acl.Access | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value }).Count -gt 0
})
if ($changes.Count -eq 0) { Write-Output 'Runtime files already restricted to the current Windows user.'; return }
$backupDirectory = Join-Path $env:USERPROFILE '.codex-voice\security'
& (Join-Path $PSScriptRoot 'protect-firebase-credentials.ps1') -Directory $backupDirectory | Out-Null
$records = @($changes | ForEach-Object { @{relativePath=$_.FullName.Substring($runtimeRoot.Length);sddl=(Get-Acl -LiteralPath $_.FullName).Sddl} })
$backupPath = Join-Path $backupDirectory ('runtime-acl-before-' + [guid]::NewGuid().ToString() + '.json')
[IO.File]::WriteAllText($backupPath, (ConvertTo-Json -InputObject $records -Depth 4))
foreach ($item in $changes) {
    if ($item.PSIsContainer) {
        $acl = New-Object Security.AccessControl.DirectorySecurity
        $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false)
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
        [IO.Directory]::SetAccessControl($item.FullName, $acl)
    } else {
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false)
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'Allow')))
        [IO.File]::SetAccessControl($item.FullName, $acl)
    }
}
Write-Output 'Runtime ACLs restricted to the current Windows user. Original ACLs saved outside Git under .codex-voice/security; history contents unchanged.'
