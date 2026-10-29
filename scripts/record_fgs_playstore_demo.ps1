<#
.SYNOPSIS
    Records a 20-second Google Play Console demonstration video for FOREGROUND_SERVICE_MICROPHONE.
.DESCRIPTION
    Uses `adb shell screenrecord` to capture the user-initiated recording flow in NoteFlow AI,
    including backgrounding the app, showing the persistent "NoteFlow AI — Recording" notification,
    and stopping the recording. Pulls the resulting MP4 to `docs/fgs_microphone_demo.mp4`.
#>

param(
    [int]$DurationSeconds = 20,
    [string]$OutputFile = "docs\fgs_microphone_demo.mp4"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " NoteFlow AI — Play Store FGS Microphone Demo Recorder" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Verify adb is available and a device/emulator is connected
$adbDevices = & adb devices
if ($LASTEXITCODE -ne 0 -or ($adbDevices -notmatch "\bdevice\b")) {
    Write-Error "No connected Android device or emulator found via 'adb devices'. Connect a device or start an emulator first."
    exit 1
}

Write-Host ""
Write-Host "Follow these 4 steps on the device during the $DurationSeconds-second recording:" -ForegroundColor Yellow
Write-Host "  1. [00s-04s] Open NoteFlow AI -> go to the Voice Recorder screen."
Write-Host "  2. [04s-08s] Tap the Record (Mic) button so the timer/waveform starts."
Write-Host "  3. [08s-14s] Press Home, then swipe down the Notification Shade to show 'NoteFlow AI — Recording'."
Write-Host "  4. [14s-20s] Tap 'Stop' on the notification (or tap the notification and stop recording)."
Write-Host ""

Read-Host "Press ENTER when you are ready to start recording ($DurationSeconds seconds)"

$remotePath = "/sdcard/noteflow_fgs_demo.mp4"
Write-Host "Recording screen on device for $DurationSeconds seconds..." -ForegroundColor Green

& adb shell "screenrecord --time-limit $DurationSeconds --bit-rate 6000000 $remotePath"

$resolvedOut = Join-Path (Get-Location) $OutputFile
$outDir = Split-Path $resolvedOut -Parent
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

Write-Host "Pulling video from device to $resolvedOut ..." -ForegroundColor Cyan
& adb pull $remotePath $resolvedOut
& adb shell "rm -f $remotePath"

if (Test-Path $resolvedOut) {
    $sizeMB = [Math]::Round((Get-Item $resolvedOut).Length / 1MB, 2)
    Write-Host ""
    Write-Host "SUCCESS! Demo video saved to: $resolvedOut ($sizeMB MB)" -ForegroundColor Green
    Write-Host "Upload this file to Google Drive (Anyone with link) or YouTube (Unlisted) and paste the link into Google Play Console -> App Content -> Foreground service permissions." -ForegroundColor Cyan
} else {
    Write-Error "Failed to pull recording from $remotePath"
}
