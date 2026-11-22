param(
    [Parameter(Mandatory = $true)][string]$KeyStore,
    [Parameter(Mandatory = $true)][string]$Alias,
    [string]$SdkPath,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$release = Join-Path $project 'release'
$keyPath = (Resolve-Path -LiteralPath $KeyStore).Path
if ([IO.Path]::GetExtension($keyPath) -eq '.arguments') {
    throw 'Pass the actual JKS or PKCS12 keystore, rather than the arguments file.'
}

if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -and $env:LOCALAPPDATA) {
    $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not $SdkPath) { throw 'Pass -SdkPath with the Android SDK directory.' }
$tools = Join-Path $SdkPath 'build-tools\36.0.0'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
foreach ($file in @($zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Missing Android SDK Build Tools 36.0.0: $file"
    }
}

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome -and $env:ProgramFiles) {
    $studioRuntime = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioRuntime 'bin\jarsigner.exe')) {
        $JavaHome = $studioRuntime
    }
}
if ($JavaHome) {
    $jarsigner = Join-Path $JavaHome 'bin\jarsigner.exe'
    if (-not (Test-Path -LiteralPath $jarsigner)) { throw "jarsigner missing: $jarsigner" }
    $env:JAVA_HOME = $JavaHome
}
else {
    $jarsigner = (Get-Command jarsigner.exe -ErrorAction Stop).Source
}

$unsignedApk = Join-Path $release 'vision-simulator-1.4-unsigned.apk'
$unsignedAab = Join-Path $release 'vision-simulator-1.4-unsigned.aab'
$signedApk = Join-Path $release 'vision-simulator-1.4-release.apk'
$signedAab = Join-Path $release 'vision-simulator-1.4-release.aab'
foreach ($file in @($unsignedApk, $unsignedAab)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Unsigned file missing: $file" }
}
foreach ($file in @($signedApk, $signedAab)) {
    if (Test-Path -LiteralPath $file) { throw "Output already exists: $file" }
}

$stage = Join-Path $release ([Guid]::NewGuid().ToString('N'))
New-Item -Path $stage -ItemType Directory | Out-Null
try {
    $stagedAab = Join-Path $stage 'signed.aab'
    $stagedApk = Join-Path $stage 'signed.apk'

    Write-Host 'Sign the AAB. Enter the keystore password at the local prompt.'
    & $jarsigner -keystore $keyPath -signedjar $stagedAab $unsignedAab $Alias
    if ($LASTEXITCODE -ne 0) { throw 'AAB signing failed.' }
    & $jarsigner -verify $stagedAab
    if ($LASTEXITCODE -ne 0) { throw 'AAB signature verification failed.' }

    & $zipalign -c -P 16 4 $unsignedApk
    if ($LASTEXITCODE -ne 0) { throw 'Unsigned APK is not aligned.' }
    Write-Host 'Sign the APK. Enter the keystore password at the local prompt.'
    & $apksigner sign --ks $keyPath --ks-key-alias $Alias --v4-signing-enabled false --out $stagedApk $unsignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $apksigner verify --verbose --print-certs $stagedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

    Move-Item -LiteralPath $stagedAab -Destination $signedAab
    Move-Item -LiteralPath $stagedApk -Destination $signedApk
}
finally {
    $releaseRoot = [IO.Path]::GetFullPath($release).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $stagePath = [IO.Path]::GetFullPath($stage)
    $expectedParent = [IO.Path]::GetDirectoryName($stagePath)
    if (-not [string]::Equals($expectedParent, $releaseRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($stagePath) -notmatch '^[0-9a-f]{32}$') {
        throw 'Refusing to clean a staging directory outside the release directory.'
    }
    $stageItem = Get-Item -LiteralPath $stagePath -ErrorAction SilentlyContinue
    if ($stageItem) {
        if ($stageItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Refusing to recursively clean a linked staging directory.'
        }
        Remove-Item -LiteralPath $stagePath -Recurse
    }
}
Write-Host "Signed files are in $release"
