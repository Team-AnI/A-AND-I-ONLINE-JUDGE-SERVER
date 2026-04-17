#!/usr/bin/env bash
set -euo pipefail

CLEANUP_LOGS_SCRIPT_PATH="/usr/local/bin/cleanup-logs.sh"
CLEANUP_LOGS_CRON_COMMAND="/usr/local/bin/cleanup-logs.sh >> /var/log/cleanup-logs.log 2>&1"
CLEANUP_LOGS_CRON_ENTRY="0 9 * * 6 ${CLEANUP_LOGS_CRON_COMMAND}"

if ! command -v sudo >/dev/null 2>&1; then
  echo "sudo is required on EC2 host"
  exit 1
fi

if ! sudo -n true >/dev/null 2>&1; then
  echo "passwordless sudo is required on EC2 host"
  exit 1
fi

sudo tee "${CLEANUP_LOGS_SCRIPT_PATH}" >/dev/null <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

echo "[$(date -Is)] Starting cleanup-logs"

if [[ -d /var/lib/docker/containers ]]; then
  find /var/lib/docker/containers -type f -name '*-json.log' -print0 \
    | while IFS= read -r -d '' container_log; do
        truncate -s 0 "${container_log}"
        echo "Truncated ${container_log}"
      done
fi

journalctl --vacuum-time=7d

find /var/log -type f \( -name '*.gz' -o -regex '.*/[^/]+\.[0-9]+$' \) -print0 \
  | while IFS= read -r -d '' rotated_log; do
      rm -f "${rotated_log}"
      echo "Removed ${rotated_log}"
    done

echo "[$(date -Is)] Finished cleanup-logs"
SCRIPT

sudo chmod +x "${CLEANUP_LOGS_SCRIPT_PATH}"

temp_crontab="$(mktemp)"
if sudo crontab -l >/dev/null 2>&1; then
  sudo crontab -l > "${temp_crontab}"
else
  : > "${temp_crontab}"
fi

if grep -Fqx "${CLEANUP_LOGS_CRON_ENTRY}" "${temp_crontab}"; then
  echo "Cleanup logs cron entry already present"
else
  printf '%s\n' "${CLEANUP_LOGS_CRON_ENTRY}" >> "${temp_crontab}"
  sudo crontab "${temp_crontab}"
  echo "Installed cleanup logs cron entry"
fi

rm -f "${temp_crontab}"
