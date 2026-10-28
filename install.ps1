# FireFrontend installer for Windows.
#
#   irm https://raw.githubusercontent.com/jackSeigerman/FireFrontend/main/install.ps1 | iex
#
# Finds adb (or downloads Google's platform-tools), connects to your Fire TV over the network,
# installs the latest FireFrontend release and turns on the Home button redirect.

# 'Continue' rather than 'Stop': Windows PowerShell 5.1 treats adb's stderr chatter as errors otherwise.
# Web requests below opt into -ErrorAction Stop individually.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
# GitHub requires TLS 1.2, which Windows PowerShell 5.1 doesn't always enable by default.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Repo = 'jackSeigerman/FireFrontend'
$ApkUrl = "https://github.com/$Repo/releases/latest/download/FireFrontend.apk"
$PlatformToolsUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$WorkDir = Join-Path $env:LOCALAPPDATA 'FireFrontend'

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
# throw, not exit: under `irm | iex`, exit would close the user's PowerShell window.
function Fail($text) { throw $text }

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# 1. adb
Step 'Looking for adb'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = Join-Path $WorkDir 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) {
        Write-Host 'adb not found - downloading Android platform-tools from Google...'
        $zip = Join-Path $WorkDir 'platform-tools.zip'
        Invoke-WebRequest -Uri $PlatformToolsUrl -OutFile $zip -UseBasicParsing -ErrorAction Stop
        Expand-Archive -Path $zip -DestinationPath $WorkDir -Force
        Remove-Item $zip
    }
}
Write-Host "Using $adb"

# 2. Connect
Step 'Connecting to your Fire TV'
Write-Host 'Find the IP on your Fire TV: Settings > My Fire TV > About > Network.'
$ip = if ($env:FIREFRONTEND_IP) { $env:FIREFRONTEND_IP } else { Read-Host 'Fire TV IP address (e.g. 192.168.1.50)' }
$ip = $ip.Trim()
if (-not $ip) { Fail 'No IP address entered.' }
if ($ip -notmatch ':') { $ip = "${ip}:5555" }

& $adb connect $ip | Out-Host
Write-Host 'If your TV shows "Allow USB debugging?", tick "Always allow from this computer" and press OK.' -ForegroundColor Yellow
$state = ''
for ($i = 0; $i -lt 40; $i++) {
    $state = (& $adb -s $ip get-state 2>$null) -join ''
    if ($state -eq 'device') { break }
    if ($i -eq 10) { & $adb disconnect $ip | Out-Null; & $adb connect $ip | Out-Null }
    Start-Sleep -Seconds 3
}
if ($state -ne 'device') {
    Fail "Couldn't connect to $ip. Check the IP, that ADB debugging is on, and that you accepted the prompt on the TV."
}
Write-Host 'Connected.' -ForegroundColor Green

# 3. Download + install
Step 'Downloading the latest FireFrontend'
if ($env:FIREFRONTEND_APK) {
    # For testing a local build instead of the latest release.
    $apk = $env:FIREFRONTEND_APK
    Write-Host "Using local APK $apk"
} else {
    $apk = Join-Path $WorkDir 'FireFrontend.apk'
    Invoke-WebRequest -Uri $ApkUrl -OutFile $apk -UseBasicParsing -ErrorAction Stop
}

Step 'Installing'
$out = (& $adb -s $ip install -r $apk 2>&1) -join "`n"
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Write-Host 'An older test build is installed with a different signature - replacing it (your FireFrontend settings will reset).'
    & $adb -s $ip uninstall dev.firefrontend | Out-Null
    $out = (& $adb -s $ip install $apk 2>&1) -join "`n"
}
if ($out -notmatch 'Success') { Fail "Install failed:`n$out" }
Write-Host 'Installed.' -ForegroundColor Green

# 4. Home button
# Two layers: the accessibility service (instant on Fire OS 8, covers the wait on Fire OS 7) and the
# usage-stats watcher (fallback for firmware that withholds accessibility events).
Step 'Turning on the Home button redirect'
& $adb -s $ip shell appops set dev.firefrontend GET_USAGE_STATS allow | Out-Null
if ($env:FIREFRONTEND_NO_ACCESSIBILITY -ne '1') {
    $service = 'dev.firefrontend/dev.firefrontend.HomeRedirectService'
    $current = ((& $adb -s $ip shell settings get secure enabled_accessibility_services) -join '').Trim()
    # Keep any accessibility services that are already on; just add ours.
    if (-not $current -or $current -eq 'null') { $updated = $service }
    elseif ($current.Split(':') -contains $service) { $updated = $current }
    else { $updated = "${current}:$service" }
    # Off then on, so Fire OS rereads the service's settings after an update.
    $without = ($updated.Split(':') | Where-Object { $_ -and $_ -ne $service }) -join ':'
    if (-not $without) { $without = 'null' }
    & $adb -s $ip shell settings put secure enabled_accessibility_services $without | Out-Null
    Start-Sleep -Seconds 1
    & $adb -s $ip shell settings put secure enabled_accessibility_services $updated | Out-Null
    & $adb -s $ip shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 3
    if (((& $adb -s $ip shell dumpsys accessibility) -join "`n") -notmatch 'FireFrontend') {
        Write-Host "Fire OS hasn't started the Home button service yet. Restart your Fire TV to finish" -ForegroundColor Yellow
        Write-Host '(Settings > My Fire TV > Restart). Home still works in the meantime, just more slowly.' -ForegroundColor Yellow
    }
}
& $adb -s $ip shell am start -n dev.firefrontend/.MainActivity | Out-Null

Write-Host "`nDone! FireFrontend is open on your TV." -ForegroundColor Green
Write-Host 'Pressing Home now returns to FireFrontend: almost instantly on Fire OS 8, after a short wait on older Fire OS.'
Write-Host 'Set your city for weather in FireFrontend > Settings.'
