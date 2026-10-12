param([string]$Directory = "$env:USERPROFILE\.codex-voice\firebase")
$ErrorActionPreference = 'Stop'
# Use the same Windows/.NET ACL API as protected registry storage. PowerShell Core's
# Set-Acl can request an unrelated SACL privilege even for this ordinary user DACL.
if ($PSVersionTable.PSEdition -eq 'Core') {
    & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -File $PSCommandPath -Directory $Directory
    if ($LASTEXITCODE -ne 0) { throw 'Credential ACL protection failed' }
    return
}
$credentialDirectory = [IO.Path]::GetFullPath($Directory)
$repositoryDirectory = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if ($credentialDirectory.StartsWith($repositoryDirectory + '\', [StringComparison]::OrdinalIgnoreCase) -or $repositoryDirectory.StartsWith($credentialDirectory.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or $credentialDirectory -eq $repositoryDirectory) { throw 'Use a dedicated credential folder outside the repository and its parent directories' }
New-Item -ItemType Directory -Path $credentialDirectory -Force | Out-Null
if ((Get-Item -LiteralPath $credentialDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point refused' }
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$directoryAcl = [Security.AccessControl.DirectorySecurity]::new()
$directoryAcl.SetOwner($currentSid)
$directoryAcl.SetAccessRuleProtection($true, $false)
$directoryAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($currentSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
[IO.Directory]::SetAccessControl($credentialDirectory, $directoryAcl)
foreach ($credentialFile in Get-ChildItem -LiteralPath $credentialDirectory -File) {
    if ($credentialFile.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point refused' }
    $fileAcl = [Security.AccessControl.FileSecurity]::new()
    $fileAcl.SetOwner($currentSid)
    $fileAcl.SetAccessRuleProtection($true, $false)
    $fileAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($currentSid, 'FullControl', 'Allow'))
    [IO.File]::SetAccessControl($credentialFile.FullName, $fileAcl)
}
Write-Host 'Firebase credential directory restricted to the current Windows user.'
