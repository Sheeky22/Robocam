#!/bin/sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dist_dir="$root_dir/userDeviceWebUI/dist"
data_dir="$root_dir/esp32Firmware/RoboCamFirmware/data"

if [ ! -f "$dist_dir/index.html" ]; then
  echo "Missing dashboard build. Run: cd userDeviceWebUI && npm run build" >&2
  exit 1
fi

rm -rf "$data_dir"
mkdir -p "$data_dir"
cp -R "$dist_dir"/. "$data_dir"/
echo "Prepared LittleFS files in $data_dir"
