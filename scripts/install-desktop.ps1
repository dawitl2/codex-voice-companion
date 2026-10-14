param([string]$Executable)
$ErrorActionPreference='Stop'
if(-not $Executable){$Executable=Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime\desktop-executable-path.txt') -Raw}
$source=Split-Path ([IO.Path]::GetFullPath($Executable)) -Parent
if(-not(Test-Path -LiteralPath (Join-Path $source 'Codex Voice.exe'))){throw 'Build the desktop package first'}
$destination=Join-Path $env:LOCALAPPDATA 'Programs\Codex Voice'
$running=@(Get-Process -Name 'Codex Voice' -ErrorAction SilentlyContinue|Where-Object {$_.Path -eq (Join-Path $destination 'Codex Voice.exe')})
if($running.Count){throw 'Quit Completely in Codex Voice before updating it'}
New-Item -ItemType Directory -Path $destination -Force|Out-Null
& robocopy.exe $source $destination /E /MT:8 /R:1 /W:1 /XJ /NFL /NDL /NJH /NJS /NP | Out-Null
if($LASTEXITCODE -ge 8){throw 'Desktop package copy failed; retained configuration was not changed'}
$executablePath=Join-Path $destination 'Codex Voice.exe'
$shortcutPath=Join-Path ([Environment]::GetFolderPath('Desktop')) 'Codex Voice.lnk'
$shortcut=(New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath=$executablePath;$shortcut.WorkingDirectory=$destination;$shortcut.Description='Codex Voice private desktop companion';$shortcut.Save()
Write-Output "Installed EXE: $executablePath"
Write-Output 'Desktop shortcut updated. Companion tray runs only after launch. No autostart or credential changes.'
