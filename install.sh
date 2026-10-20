#!/usr/bin/env bash
# FireFrontend installer for macOS and Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/jackSeigerman/FireFrontend/main/install.sh | bash
#
# Finds adb (or downloads Google's platform-tools), connects to your Fire TV over the network,
# installs the latest FireFrontend release and turns on the Home button redirect.
set -euo pipefail

REPO="jackSeigerman/FireFrontend"
APK_URL="https://github.com/$REPO/releases/latest/download/FireFrontend.apk"
WORK_DIR="${HOME}/.firefrontend"

step() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$1"; exit 1; }

mkdir -p "$WORK_DIR"

# 1. adb
step "Looking for adb"
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  ADB="$WORK_DIR/platform-tools/adb"
  if [ ! -x "$ADB" ]; then
    case "$(uname -s)" in
      Darwin) os=darwin ;;
      Linux) os=linux ;;
      *) fail "Unsupported system: $(uname -s). On Windows, use install.ps1." ;;
    esac
    echo "adb not found - downloading Android platform-tools from Google..."
    curl -fsSL -o "$WORK_DIR/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
    unzip -q -o "$WORK_DIR/platform-tools.zip" -d "$WORK_DIR"
    rm "$WORK_DIR/platform-tools.zip"
  fi
fi
echo "Using $ADB"

# 2. Connect (read from the terminal even when this script is piped into bash)
step "Connecting to your Fire TV"
echo "Find the IP on your Fire TV: Settings > My Fire TV > About > Network."
ip="${FIREFRONTEND_IP:-}"
if [ -z "$ip" ]; then
  printf "Fire TV IP address (e.g. 192.168.1.50): "
  read -r ip < /dev/tty
fi
ip="$(echo "$ip" | tr -d '[:space:]')"
[ -n "$ip" ] || fail "No IP address entered."
case "$ip" in *:*) ;; *) ip="$ip:5555" ;; esac

"$ADB" connect "$ip" || true
printf '\033[33mIf your TV shows "Allow USB debugging?", tick "Always allow from this computer" and press OK.\033[0m\n'
state=""
for i in $(seq 0 39); do
  state="$("$ADB" -s "$ip" get-state 2>/dev/null || true)"
  [ "$state" = "device" ] && break
  if [ "$i" -eq 10 ]; then "$ADB" disconnect "$ip" >/dev/null 2>&1 || true; "$ADB" connect "$ip" >/dev/null 2>&1 || true; fi
  sleep 3
done
[ "$state" = "device" ] || fail "Couldn't connect to $ip. Check the IP, that ADB debugging is on, and that you accepted the prompt on the TV."
echo "Connected."

# 3. Download + install
step "Downloading the latest FireFrontend"
if [ -n "${FIREFRONTEND_APK:-}" ]; then
  # For testing a local build instead of the latest release.
  APK="$FIREFRONTEND_APK"
  echo "Using local APK $APK"
else
  APK="$WORK_DIR/FireFrontend.apk"
  curl -fsSL -o "$APK" "$APK_URL"
fi

step "Installing"
out="$("$ADB" -s "$ip" install -r "$APK" 2>&1 || true)"
if echo "$out" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
  echo "An older test build is installed with a different signature - replacing it (your FireFrontend settings will reset)."
  "$ADB" -s "$ip" uninstall dev.firefrontend >/dev/null || true
  out="$("$ADB" -s "$ip" install "$APK" 2>&1 || true)"
fi
echo "$out" | grep -q Success || fail "Install failed: $out"
echo "Installed."

# 4. Home button
step "Turning on the Home button redirect"
"$ADB" -s "$ip" shell appops set dev.firefrontend GET_USAGE_STATS allow >/dev/null
"$ADB" -s "$ip" shell am start -n dev.firefrontend/.MainActivity >/dev/null

printf '\n\033[32mDone! FireFrontend is open on your TV.\033[0m\n'
echo "Pressing Home now returns to FireFrontend after about 5 seconds (a Fire OS limit)."
echo "Set your city for weather in FireFrontend > Settings."
