param([string]$Executable)
$ErrorActionPreference='Stop'
if(-not $Executable){$Executable=Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime\desktop-executable-path.txt') -Raw}
$source=Split-Path ([IO.Path]::GetFullPath($Executable)) -Parent
if(-not(Test-Path -LiteralPath (Join-Path $source 'Codex Voice.exe'))){throw 'Build the desktop package first'}
$destination=Join-Path $env:LOCALAPPDATA 'Programs\Codex Voice'
$running=@(Get-Process -Name 'Codex Voice' -ErrorAction SilentlyContinue|Where-Object {$_.Path -eq (Join-Path $destination 'Codex Voice.exe')})
if($running.Count){throw 'Quit Completely in Codex Voice before updating it'}
New-Item -ItemType Directory -Path $destination -Force|Out-Null
Get-ChildItem -LiteralPath $source -Force|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination $destination -Recurse -Force}
$executablePath=Join-Path $destination 'Codex Voice.exe'
$shortcutPath=Join-Path ([Environment]::GetFolderPath('Desktop')) 'Codex Voice.lnk'
$shortcut=(New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath=$executablePath;$shortcut.WorkingDirectory=$destination;$shortcut.Description='Codex Voice private desktop companion';$shortcut.Save()
Write-Output "Installed EXE: $executablePath"
Write-Output 'Desktop shortcut created. No autostart, tray service or credential changes.'
