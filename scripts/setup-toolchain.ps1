# GTool 构建工具链安装（幂等、可重复执行）
# 默认安装到 %USERPROFILE%\.android-dev（不在 OneDrive 内，不会被同步）
# 用法: powershell -ExecutionPolicy Bypass -File scripts/setup-toolchain.ps1
param(
    [string]$Root = (Join-Path $env:USERPROFILE ".android-dev"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$Repo = Split-Path -Parent $PSScriptRoot
$Node = (Get-Command node -ErrorAction Stop).Source
$Downloader = Join-Path $Repo "scripts\download.mjs"
$Sdk = Join-Path $Root "sdk"

New-Item -ItemType Directory -Force $Root | Out-Null
New-Item -ItemType Directory -Force $Sdk | Out-Null

function Ensure-File([string]$Url, [string]$Out) {
    if (-not $Force -and (Test-Path $Out) -and (Get-Item $Out).Length -gt 1MB) {
        Write-Host "  已存在: $(Split-Path -Leaf $Out)"
        return
    }
    Write-Host "  下载: $Url"
    & $Node $Downloader $Url $Out
    if ($LASTEXITCODE -ne 0) { throw "下载失败: $Url" }
}

function Expand-Into([string]$Zip, [string]$Dest) {
    New-Item -ItemType Directory -Force $Dest | Out-Null
    tar.exe -xf $Zip -C $Dest
    if ($LASTEXITCODE -ne 0) { throw "解压失败: $Zip" }
}

# ---------- JDK 21 ----------
$jdkDir = Get-ChildItem $Root -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdkDir) {
    Write-Host "[1/4] JDK 21"
    Ensure-File "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" (Join-Path $Root "jdk.zip")
    Expand-Into (Join-Path $Root "jdk.zip") $Root
    $jdkDir = Get-ChildItem $Root -Directory -Filter "jdk-21*" | Select-Object -First 1
}
$env:JAVA_HOME = $jdkDir.FullName
$env:PATH = "$($jdkDir.FullName)\bin;$env:PATH"
Write-Host "  JAVA_HOME = $env:JAVA_HOME"

# ---------- Android SDK（直链下载，sdkmanager 在本机不可用）----------
Write-Host "[2/4] Android SDK"
if (-not (Test-Path (Join-Path $Sdk "platform-tools\adb.exe"))) {
    Ensure-File "https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip" (Join-Path $Root "platform-tools.zip")
    Expand-Into (Join-Path $Root "platform-tools.zip") $Sdk
}
if (-not (Test-Path (Join-Path $Sdk "platforms\android-36\android.jar"))) {
    Ensure-File "https://dl.google.com/android/repository/platform-36_r02.zip" (Join-Path $Root "platform-36.zip")
    Expand-Into (Join-Path $Root "platform-36.zip") (Join-Path $Sdk "platforms")
}
if (-not (Test-Path (Join-Path $Sdk "build-tools\36.0.0\aapt2.exe"))) {
    Ensure-File "https://dl.google.com/android/repository/build-tools_r36_windows.zip" (Join-Path $Root "build-tools.zip")
    $tmp = Join-Path $Root "bt-tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Into (Join-Path $Root "build-tools.zip") $tmp
    New-Item -ItemType Directory -Force (Join-Path $Sdk "build-tools") | Out-Null
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (Test-Path (Join-Path $Sdk "build-tools\36.0.0")) { Remove-Item (Join-Path $Sdk "build-tools\36.0.0") -Recurse -Force }
    Move-Item $inner.FullName (Join-Path $Sdk "build-tools\36.0.0")
    Remove-Item $tmp -Recurse -Force
}

# SDK 许可（Gradle 校验）
$lic = Join-Path $Sdk "licenses"
New-Item -ItemType Directory -Force $lic | Out-Null
@(
    "8933bad161af4178b1185d1a37fbf41ea5269c55",
    "d56f5187479451eabf01fb78af6dfcb131a6481e",
    "24333f8a63b6825ea9c5514f83c2829b004d1fee"
) | Set-Content (Join-Path $lic "android-sdk-license") -Encoding ASCII
"84831b9409646a918e30573bab4c9c91346d8abd" | Set-Content (Join-Path $lic "android-sdk-preview-license") -Encoding ASCII

# ---------- Gradle ----------
Write-Host "[3/4] Gradle 8.14.3"
$gradleDir = Get-ChildItem $Root -Directory -Filter "gradle-8.14.3" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $gradleDir) {
    Ensure-File "https://services.gradle.org/distributions/gradle-8.14.3-bin.zip" (Join-Path $Root "gradle.zip")
    Expand-Into (Join-Path $Root "gradle.zip") $Root
    $gradleDir = Get-ChildItem $Root -Directory -Filter "gradle-8.14.3" | Select-Object -First 1
}

# ---------- local.properties ----------
Write-Host "[4/4] local.properties"
$sdkPosix = $Sdk.Replace('\', '/')
$escapedSdk = $sdkPosix -replace ":", "\:"
Set-Content -Path (Join-Path $Repo "local.properties") -Value "sdk.dir=$escapedSdk" -Encoding ASCII

Write-Host ""
Write-Host "==== 工具链就绪 ===="
Write-Host "ROOT        = $Root"
Write-Host "JAVA_HOME   = $env:JAVA_HOME"
Write-Host "ANDROID_SDK = $Sdk"
Write-Host "GRADLE      = $($gradleDir.FullName)\bin\gradle.bat"
& "$($jdkDir.FullName)\bin\java.exe" -version 2>&1 | Select-Object -First 1

