# GTool 构建脚本：自动准备环境变量并调用 Gradle
# 用法:
#   powershell -File scripts/build.ps1              # debug APK
#   powershell -File scripts/build.ps1 -Variant release
#   powershell -File scripts/build.ps1 -Tasks "testDebugUnitTest"
param(
    [ValidateSet("debug", "release")] [string]$Variant = "debug",
    [string[]]$Tasks = @(),
    [string]$Root = (Join-Path $env:USERPROFILE ".gtool-tools")
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot

$jdk = Get-ChildItem $Root -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) { throw "未找到工具链，请先运行 scripts/setup-toolchain.ps1" }

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $Root "sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $Root "android-home"
$env:GRADLE_USER_HOME = Join-Path $Root "gradle-home"
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null

$gradle = Join-Path $Root "gradle-8.14.3\bin\gradle.bat"
if (-not (Test-Path $gradle)) { throw "未找到 Gradle: $gradle" }

if ($Tasks.Count -eq 0) {
    $Tasks = @(if ($Variant -eq "release") { ":app:assembleRelease" } else { ":app:assembleDebug" })
}

Push-Location $Repo
try {
    & $gradle @Tasks --no-daemon --console=plain
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
