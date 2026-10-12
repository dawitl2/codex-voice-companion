# Installs one pinned upstream CPU runtime and one English model into ignored local cache.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$cacheRoot = Join-Path $projectRoot '.cache\speech'
$binaryDirectory = Join-Path $cacheRoot 'whisper-b5454'
$modelDirectory = Join-Path $cacheRoot 'models'
New-Item -ItemType Directory -Force -Path $cacheRoot,$binaryDirectory,$modelDirectory | Out-Null
function Get-VerifiedFile([string]$Url, [string]$Path, [string]$Sha256, [long]$Size) {
  if (Test-Path -LiteralPath $Path) {
    if ((Get-Item -LiteralPath $Path).Length -eq $Size -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256) { return }
    throw 'Existing speech artifact differs from the pinned distribution; inspect it before replacement'
  }
  $partial = "$Path.download"
  Invoke-WebRequest -Uri $Url -OutFile $partial -TimeoutSec 300
  if ((Get-Item -LiteralPath $partial).Length -ne $Size -or (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) { throw 'Speech artifact checksum mismatch; artifact not installed' }
  Move-Item -LiteralPath $partial -Destination $Path
}
$archive = Join-Path $cacheRoot 'whisper-b5454-cpu-x64.zip'
Get-VerifiedFile 'https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-bin-x64.zip' $archive '6ba69e3482d7826214f90a6a9c84ca07782aec1e1d0c6a7c30c994fd5d816ccb' 8928640
if (-not (Test-Path -LiteralPath (Join-Path $binaryDirectory 'Release\whisper-cli.exe'))) { Expand-Archive -LiteralPath $archive -DestinationPath $binaryDirectory }
Get-VerifiedFile 'https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.en.bin' (Join-Path $modelDirectory 'ggml-tiny.en.bin') '921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f' 77704715
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\licenses\whisper.cpp-LICENSE.txt') -Destination (Join-Path $binaryDirectory 'LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\licenses\Whisper-LICENSE.txt') -Destination (Join-Path $modelDirectory 'LICENSE.txt')
Write-Output 'Pinned whisper.cpp b5454 (v1.9.5) CPU runtime and tiny.en model verified in ignored .cache/speech.'
