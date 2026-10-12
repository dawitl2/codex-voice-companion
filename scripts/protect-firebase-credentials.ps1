param([string]$Directory = "$env:USERPROFILE\.codex-voice\firebase")
$ErrorActionPreference = 'Stop'
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
Set-Acl -LiteralPath $credentialDirectory -AclObject $directoryAcl
foreach ($credentialFile in Get-ChildItem -LiteralPath $credentialDirectory -File) {
    if ($credentialFile.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point refused' }
    $fileAcl = [Security.AccessControl.FileSecurity]::new()
    $fileAcl.SetOwner($currentSid)
    $fileAcl.SetAccessRuleProtection($true, $false)
    $fileAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($currentSid, 'FullControl', 'Allow'))
    Set-Acl -LiteralPath $credentialFile.FullName -AclObject $fileAcl
}
Write-Host 'Firebase credential directory restricted to the current Windows user.'
