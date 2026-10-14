$ErrorActionPreference = 'Stop'
$desktopRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $desktopRoot
try {
    & .\scripts\run.ps1 build
    if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed' }
    $nodeExe = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
    if ((& $nodeExe --version) -notmatch '^v24\.') { throw 'Verified Node 24 required' }
    $stage = Join-Path $desktopRoot ('.cache\desktop-stage\' + [guid]::NewGuid().ToString())
    $backend = Join-Path $stage 'backend'
    New-Item -ItemType Directory -Path $backend,(Join-Path $stage 'desktop'),(Join-Path $backend 'dist') -Force | Out-Null
    Copy-Item -LiteralPath desktop/main.cjs,desktop/preload.cjs,desktop/index.html,desktop/style.css,desktop/renderer.js -Destination (Join-Path $stage 'desktop')
    Copy-Item -LiteralPath dist/src -Destination (Join-Path $backend 'dist') -Recurse
    Copy-Item -LiteralPath $nodeExe -Destination (Join-Path $backend 'node.exe')
    $vendorSource = '.cache\codex-runtime\node_modules\@openai\codex-win32-x64\vendor'
    $vendorTarget = Join-Path $backend '.cache\codex-runtime\node_modules\@openai\codex-win32-x64'
    New-Item -ItemType Directory -Path $vendorTarget,(Join-Path $backend '.cache\speech\models'),(Join-Path $backend '.cache\speech\whisper-b5454') -Force | Out-Null
    Copy-Item -LiteralPath $vendorSource -Destination $vendorTarget -Recurse
    Copy-Item -LiteralPath .cache/speech/whisper-b5454/Release -Destination (Join-Path $backend '.cache\speech\whisper-b5454') -Recurse
    Copy-Item -LiteralPath .cache/speech/models/ggml-tiny.en.bin -Destination (Join-Path $backend '.cache\speech\models')
    Copy-Item -LiteralPath docs/licenses -Destination $backend -Recurse
    New-Item -ItemType Directory -Path (Join-Path $backend 'scripts') -Force|Out-Null
    Copy-Item -LiteralPath scripts/session-owners.ps1 -Destination (Join-Path $backend 'scripts')
    Copy-Item -LiteralPath package.json,package-lock.json -Destination $backend
    $npmCli = Join-Path (Split-Path (Get-Command npm.cmd).Source -Parent) 'node_modules/npm/bin/npm-cli.js'
    & $nodeExe $npmCli ci --prefix $backend --omit=dev --ignore-scripts --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Production bridge dependency packaging failed' }
    [IO.File]::WriteAllText((Join-Path $stage 'package.json'), '{"name":"codex-voice-desktop","productName":"Codex Voice","version":"1.1.2","main":"desktop/main.cjs","private":true}')
    # Original code mark drawn as an app asset, never a copied proprietary graphic.
    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object Drawing.Bitmap(256,256)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.Clear([Drawing.Color]::FromArgb(245,245,247))
    $pen = New-Object Drawing.Pen([Drawing.Color]::FromArgb(35,35,38),16)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.DrawLines($pen,[Drawing.Point[]]@((New-Object Drawing.Point(88,75)),(New-Object Drawing.Point(40,128)),(New-Object Drawing.Point(88,181))))
    $graphics.DrawLines($pen,[Drawing.Point[]]@((New-Object Drawing.Point(168,75)),(New-Object Drawing.Point(216,128)),(New-Object Drawing.Point(168,181))))
    $graphics.DrawLine($pen,144,62,112,194)
    $memory = New-Object IO.MemoryStream; $bitmap.Save($memory,[Drawing.Imaging.ImageFormat]::Png); $png=$memory.ToArray()
    $iconPath = Join-Path $stage 'desktop\icon.ico'
    $icon = [IO.File]::Create($iconPath); $writer = New-Object IO.BinaryWriter($icon)
    $writer.Write([uint16]0);$writer.Write([uint16]1);$writer.Write([uint16]1);$writer.Write([byte]0);$writer.Write([byte]0);$writer.Write([byte]0);$writer.Write([byte]0);$writer.Write([uint16]1);$writer.Write([uint16]32);$writer.Write([uint32]$png.Length);$writer.Write([uint32]22);$writer.Write($png)
    $writer.Dispose();$icon.Dispose();$memory.Dispose();$pen.Dispose();$graphics.Dispose();$bitmap.Dispose()
    foreach($trayState in @('running','stopped')){
        $trayBitmap=New-Object Drawing.Bitmap(32,32);$trayGraphics=[Drawing.Graphics]::FromImage($trayBitmap);$trayGraphics.Clear([Drawing.Color]::Transparent)
        $trayPen=New-Object Drawing.Pen([Drawing.Color]::White,3);$trayGraphics.DrawLines($trayPen,[Drawing.Point[]]@((New-Object Drawing.Point(10,7)),(New-Object Drawing.Point(3,15)),(New-Object Drawing.Point(10,23))))
        $trayGraphics.DrawLines($trayPen,[Drawing.Point[]]@((New-Object Drawing.Point(20,7)),(New-Object Drawing.Point(27,15)),(New-Object Drawing.Point(20,23))))
        $trayBrush=New-Object Drawing.SolidBrush($(if($trayState -eq 'running'){[Drawing.Color]::FromArgb(48,185,99)}else{[Drawing.Color]::FromArgb(142,142,147)}))
        $trayGraphics.FillEllipse($trayBrush,21,22,10,10);$trayBitmap.Save((Join-Path $stage "desktop\tray-$trayState.png"),[Drawing.Imaging.ImageFormat]::Png)
        $trayBrush.Dispose();$trayPen.Dispose();$trayGraphics.Dispose();$trayBitmap.Dispose()
    }
    $output = Join-Path $desktopRoot ('.runtime\desktop-builds\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    & $nodeExe node_modules/@electron/packager/bin/electron-packager.mjs $stage 'Codex Voice' --no-asar --platform=win32 --arch=x64 --electron-version=44.7.0 "--out=$output" "--icon=$iconPath" --app-version=1.1.2 --build-version=1.1.2 --win32metadata.CompanyName='Codex Voice' --win32metadata.FileDescription='Private desktop and Android voice companion'
    if ($LASTEXITCODE -ne 0) { throw 'Packaged desktop build failed' }
    $executable = Join-Path $output 'Codex Voice-win32-x64\Codex Voice.exe'
    if (-not (Test-Path -LiteralPath $executable)) { throw 'Desktop EXE missing' }
    [IO.File]::WriteAllText((Join-Path $desktopRoot '.runtime\desktop-executable-path.txt'),$executable)
    Write-Output "Desktop EXE: $executable"
} finally { Pop-Location }
