$ErrorActionPreference = "Stop"

$PackageDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$QuPathUserDir = if ($env:QUPATH_USER_DIR) { $env:QUPATH_USER_DIR } else { Join-Path $env:USERPROFILE "QuPath\v0.6" }
$ExtensionsDir = Join-Path $QuPathUserDir "extensions"
$SupportDir = Join-Path $QuPathUserDir "timestamp"
$RuntimeDir = Join-Path $SupportDir "runtime"
$VenvDir = Join-Path $RuntimeDir ".venv"
$ToolsDir = Join-Path $SupportDir "tools"
$ModelCacheDir = Join-Path $SupportDir "model-cache"
$UvBin = Join-Path $ToolsDir "uv.exe"
$UvVersion = "0.12.5"
$RequirementsFile = Join-Path $PackageDir "requirements-doctor.txt"
$ChecksumsFile = Join-Path $PackageDir "CHECKSUMS-SHA256.txt"
$HelperFile = Join-Path $PackageDir "live_whisper_demo.py"

Write-Host "TimeStamp doctor installation for Windows"
Write-Host "QuPath folder: $QuPathUserDir"
Write-Host ""
Write-Host "QuPath 0.6 must be opened once to complete its first-time setup, then closed."
Write-Host ""

if (Get-Process -Name "QuPath*" -ErrorAction SilentlyContinue) {
    throw "Please close QuPath completely, then run this installer again."
}
if (-not (Test-Path -LiteralPath $RequirementsFile -PathType Leaf)) { throw "Missing $RequirementsFile" }
if (-not (Test-Path -LiteralPath $ChecksumsFile -PathType Leaf)) { throw "Missing $ChecksumsFile" }
if (-not (Test-Path -LiteralPath $HelperFile -PathType Leaf)) { throw "Missing $HelperFile" }

$JarFile = Get-ChildItem -LiteralPath $PackageDir -Filter "TimeStamp-*.jar" -File |
    Where-Object { $_.Name -notmatch "-(javadoc|sources)\.jar$" } |
    Select-Object -First 1
if (-not $JarFile) { throw "The TimeStamp extension JAR is missing from this package." }

Write-Host "Verifying the TimeStamp package..."
$ExpectedChecksums = @{}
Get-Content -LiteralPath $ChecksumsFile | ForEach-Object {
    if ($_ -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') { $ExpectedChecksums[$Matches[2]] = $Matches[1].ToLowerInvariant() }
}
foreach ($FileName in @($JarFile.Name, "requirements-doctor.txt", "live_whisper_demo.py")) {
    if (-not $ExpectedChecksums.ContainsKey($FileName)) { throw "Missing checksum for $FileName" }
    $Actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PackageDir $FileName)).Hash.ToLowerInvariant()
    if ($Actual -ne $ExpectedChecksums[$FileName]) { throw "Checksum verification failed for $FileName" }
    Write-Host "$FileName`: OK"
}

New-Item -ItemType Directory -Force -Path $ExtensionsDir, $RuntimeDir, $ToolsDir, $ModelCacheDir | Out-Null

if (-not (Test-Path -LiteralPath $UvBin -PathType Leaf)) {
    $Architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    switch ($Architecture) {
        "X64" {
            $UvPlatform = "x86_64-pc-windows-msvc"
            $UvSha256 = "4c4d49d8738847d9b71ba319e49a5688c93eac0fe6204b1df24e98528dddf39a"
        }
        "Arm64" {
            $UvPlatform = "aarch64-pc-windows-msvc"
            $UvSha256 = "724279317fee6e5fa8ad1908e4eba2bbe764ef1ece5b3f4597927b62b1fe562a"
        }
        default { throw "Unsupported Windows architecture: $Architecture" }
    }
    $TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("timestamp-uv-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $TempDir | Out-Null
    try {
        $Archive = Join-Path $TempDir "uv.zip"
        Write-Host "Downloading the private TimeStamp setup tool..."
        Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/astral-sh/uv/releases/download/$UvVersion/uv-$UvPlatform.zip" -OutFile $Archive
        $ActualUvSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Archive).Hash.ToLowerInvariant()
        if ($ActualUvSha256 -ne $UvSha256) { throw "The downloaded setup tool failed checksum verification." }
        Expand-Archive -LiteralPath $Archive -DestinationPath $TempDir -Force
        $DownloadedUv = Get-ChildItem -LiteralPath $TempDir -Filter "uv.exe" -File -Recurse | Select-Object -First 1
        if (-not $DownloadedUv) { throw "The downloaded setup tool did not contain uv.exe." }
        Copy-Item -LiteralPath $DownloadedUv.FullName -Destination "$UvBin.new" -Force
        Move-Item -LiteralPath "$UvBin.new" -Destination $UvBin -Force
    }
    finally {
        Remove-Item -LiteralPath $TempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$env:UV_CACHE_DIR = Join-Path $SupportDir "download-cache"
$env:UV_PYTHON_INSTALL_DIR = Join-Path $RuntimeDir "python"
$env:HF_HOME = $ModelCacheDir

$PythonBin = Join-Path $VenvDir "Scripts\python.exe"
Write-Host "Preparing the private Python runtime..."
if (-not (Test-Path -LiteralPath $PythonBin -PathType Leaf)) {
    & $UvBin venv --python 3.12 --managed-python $VenvDir
    if ($LASTEXITCODE -ne 0) { throw "Private Python setup failed." }
}
Write-Host "Installing the recorder and speech-to-text libraries..."
& $UvBin pip install --python $PythonBin --requirements $RequirementsFile
if ($LASTEXITCODE -ne 0) { throw "Recorder dependency installation failed." }

if ($env:TIMESTAMP_SKIP_MODEL_DOWNLOAD -ne "1") {
    Write-Host "Downloading the live transcription model..."
    & $PythonBin -c 'from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-small.en")'
    if ($LASTEXITCODE -ne 0) { throw "Live model download failed." }
    Write-Host "Downloading the final high-accuracy model (this is the largest download)..."
    & $PythonBin -c 'from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-large-v3")'
    if ($LASTEXITCODE -ne 0) { throw "Final model download failed." }
}

Write-Host "Verifying microphone and transcription support..."
& $PythonBin -c 'import faster_whisper, numpy, sounddevice; devices=sounddevice.query_devices(); print(f"Recorder ready; {len(devices)} audio device(s) detected")'
if ($LASTEXITCODE -ne 0) { throw "Recorder verification failed." }
Write-Host "Testing the microphone for 3 seconds. Speak normally now..."
& $PythonBin $HelperFile --check-audio --check-seconds 3
if ($LASTEXITCODE -ne 0) {
    Write-Warning "The microphone test could not open an input. Installation will finish; use Test microphone in QuPath after checking Windows privacy permissions."
}

$InstallTarget = Join-Path $ExtensionsDir $JarFile.Name
Copy-Item -LiteralPath $JarFile.FullName -Destination "$InstallTarget.new" -Force
Get-ChildItem -LiteralPath $ExtensionsDir -Filter "TimeStamp-*.jar" -File | ForEach-Object {
    if ($_.FullName -ne $InstallTarget) { Remove-Item -LiteralPath $_.FullName -Force }
}
if (Test-Path -LiteralPath $InstallTarget -PathType Leaf) {
    Copy-Item -LiteralPath "$InstallTarget.new" -Destination $InstallTarget -Force
    Remove-Item -LiteralPath "$InstallTarget.new" -Force
} else {
    Move-Item -LiteralPath "$InstallTarget.new" -Destination $InstallTarget
}

$Manifest = [ordered]@{
    runtime = $PythonBin
    modelCache = $ModelCacheDir
    liveModel = "Systran/faster-whisper-small.en"
    finalModel = "Systran/faster-whisper-large-v3"
    installedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
}
$Manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $SupportDir "doctor-runtime.json") -Encoding UTF8

Write-Host ""
Write-Host "TimeStamp is ready for the doctor."
Write-Host "Open QuPath 0.6, allow microphone access, then open the TimeStamp monitor."
