# 启动 / 操作 Android 模拟器（GTool 预览与调试用）
#
# 用法:
#   pwsh scripts/emulator.ps1 -Action start
#   pwsh scripts/emulator.ps1 -Action install
#   pwsh scripts/emulator.ps1 -Action launch
#   pwsh scripts/emulator.ps1 -Action screenshot -Name tuner
#   pwsh scripts/emulator.ps1 -Action logcat
#   pwsh scripts/emulator.ps1 -Action status
#   pwsh scripts/emulator.ps1 -Action stop

param(
    [ValidateSet("start", "stop", "status", "install", "launch", "screenshot", "logcat", "tap", "swipe")]
    [string]$Action = "status",
    [string]$Name = "screen",
    [string]$X = "540",
    [string]$Y = "1200",
    [string]$X2 = "540",
    [string]$Y2 = "600",
    [switch]$Windowed
)

$ErrorActionPreference = "Stop"
$Root = Join-Path $env:USERPROFILE ".android-dev"
$Adb = Join-Path $Root "sdk\platform-tools\adb.exe"
$Emulator = Join-Path $Root "sdk\emulator\emulator.exe"
$Serial = "emulator-5554"
$env:ANDROID_SDK_ROOT = Join-Path $Root "sdk"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:ANDROID_AVD_HOME = Join-Path $Root "android-home\avd"
$Repo = Split-Path -Parent $PSScriptRoot

function Wait-Boot([int]$TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $raw = & $Adb -s $Serial shell getprop sys.boot_completed 2>$null
        $boot = if ($null -ne $raw) { ("$raw").Trim() } else { "" }
        if ($boot -eq "1") { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

switch ($Action) {
    "start" {
        $running = (& $Adb devices) -match "$Serial\s+device"
        if ($running) { Write-Host "模拟器已在运行"; break }
        if ($Windowed) {
            Write-Host "启动模拟器（带窗口）..."
            $emuArgs = @(
                "-avd", "GTool_API36",
                "-no-audio", "-no-boot-anim", "-no-snapshot",
                "-gpu", "auto",
                "-camera-back", "none", "-camera-front", "none"
            )
        } else {
            Write-Host "启动模拟器（无窗口）..."
            $emuArgs = @(
                "-avd", "GTool_API36",
                "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot",
                "-gpu", "swiftshader_indirect",
                "-camera-back", "none", "-camera-front", "none"
            )
        }
        Start-Process -FilePath $Emulator -ArgumentList $emuArgs
        if (Wait-Boot) { Write-Host "已启动并就绪（$Serial）" } else { Write-Warning "启动超时，请检查 $Root\android-home\avd" }
    }
    "stop" {
        & $Adb -s $Serial emu kill 2>$null
        Write-Host "已发送关闭指令"
    }
    "status" {
        & $Adb devices -l
        if ((& $Adb devices) -match "$Serial\s+device") {
            Write-Host "boot_completed=$((& $Adb -s $Serial shell getprop sys.boot_completed).Trim())"
            Write-Host "Android $((& $Adb -s $Serial shell getprop ro.build.version.release).Trim())"
        }
    }
    "install" {
        $apk = Get-ChildItem (Join-Path $Repo "dist") -Filter "GTool-*.apk" |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $apk) { throw "dist 下没有 APK，请先构建" }
        Write-Host "安装 $($apk.Name)"
        & $Adb -s $Serial install -r $apk.FullName
    }
    "launch" {
        & $Adb -s $Serial shell am start -n com.gtool/.MainActivity
    }
    "screenshot" {
        $dir = Join-Path $Repo "docs\screenshots"
        New-Item -ItemType Directory -Force $dir | Out-Null
        & $Adb -s $Serial shell screencap -p "/sdcard/$Name.png" | Out-Null
        & $Adb -s $Serial pull "/sdcard/$Name.png" (Join-Path $dir "$Name.png") | Out-Null
        Write-Host "已保存 docs\screenshots\$Name.png"
    }
    "logcat" {
        $rawPid = & $Adb -s $Serial shell pidof com.gtool 2>$null
        $pidValue = if ($null -ne $rawPid) { ("$rawPid").Trim() } else { "" }
        if ($pidValue) {
            & $Adb -s $Serial logcat -d --pid=$pidValue -v time
        } else {
            & $Adb -s $Serial logcat -d -v time AndroidRuntime:E *:S
        }
    }
    "tap" {
        & $Adb -s $Serial shell input tap $X $Y
        Write-Host "tap $X,$Y"
    }
    "swipe" {
        & $Adb -s $Serial shell input swipe $X $Y $X2 $Y2 300
        Write-Host "swipe $X,$Y -> $X2,$Y2"
    }
}
