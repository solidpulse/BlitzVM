#!/usr/bin/env bash
# Run WordPress on BlitzVM with one command, in the spirit of `frankenphp php-server`:
#
#   scripts/wordpress.sh [dir]          # default dir: ./wordpress
#
# - downloads WordPress into <dir> if it does not exist yet;
# - starts (or reuses) a throwaway MariaDB container `blitz-wp-db` (Docker required);
# - writes <dir>/wp-config.php with random salts if there is none;
# - serves <dir> with BlitzVM; open http://localhost:$PORT/ and the installer runs.
#
# Environment: PORT (8080), DB_NAME (wp), DB_PORT (33307), DB_PASSWORD (wp), WP_URL (latest .tar.gz).
# No web server config is needed: the server routes like FrankenPHP's php_server
# (unknown paths go to index.php, so pretty permalinks work).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${1:-wordpress}"
PORT="${PORT:-8080}"
DB_NAME="${DB_NAME:-wp}"
DB_PORT="${DB_PORT:-33307}"
DB_PASSWORD="${DB_PASSWORD:-wp}"
WP_URL="${WP_URL:-https://wordpress.org/latest.tar.gz}"

if [[ ! -f "$DIR/wp-settings.php" ]]; then
  echo "Downloading WordPress into $DIR ..."
  mkdir -p "$DIR"
  curl -fsSL "$WP_URL" | tar -xz -C "$DIR" --strip-components=1
fi

if ! docker ps --format '{{.Names}}' | grep -qx blitz-wp-db; then
  if docker ps -a --format '{{.Names}}' | grep -qx blitz-wp-db; then
    docker start blitz-wp-db >/dev/null
  else
    echo "Starting MariaDB (container blitz-wp-db, 127.0.0.1:$DB_PORT) ..."
    docker run -d --name blitz-wp-db -e MARIADB_ROOT_PASSWORD="$DB_PASSWORD" -e MARIADB_DATABASE=wp \
      -p "127.0.0.1:$DB_PORT:3306" mariadb:11 >/dev/null
  fi
  for _ in $(seq 1 60); do
    docker exec blitz-wp-db mariadb -uroot -p"$DB_PASSWORD" -e 'select 1' wp >/dev/null 2>&1 && break
    sleep 1
  done
fi
docker exec blitz-wp-db mariadb -uroot -p"$DB_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\`"

if [[ ! -f "$DIR/wp-config.php" ]]; then
  echo "Writing $DIR/wp-config.php ..."
  salt() { LC_ALL=C tr -dc 'A-Za-z0-9!#%()*+,-./:;<=>?@[]^_{|}~' </dev/urandom | head -c 64; }
  {
    echo "<?php"
    echo "define( 'DB_NAME', '$DB_NAME' );"
    echo "define( 'DB_USER', 'root' );"
    echo "define( 'DB_PASSWORD', '$DB_PASSWORD' );"
    echo "define( 'DB_HOST', '127.0.0.1:$DB_PORT' );"
    echo "define( 'DB_CHARSET', 'utf8mb4' );"
    echo "define( 'DB_COLLATE', '' );"
    for k in AUTH_KEY SECURE_AUTH_KEY LOGGED_IN_KEY NONCE_KEY AUTH_SALT SECURE_AUTH_SALT LOGGED_IN_SALT NONCE_SALT; do
      echo "define( '$k', '$(salt)' );"
    done
    echo "\$table_prefix = 'wp_';"
    echo "define( 'WP_DEBUG', false );"
    echo "// BlitzVM has no outgoing HTTP client yet: run cron from a system timer instead of a loopback request."
    echo "define( 'DISABLE_WP_CRON', true );"
    echo "if ( ! defined( 'ABSPATH' ) ) { define( 'ABSPATH', __DIR__ . '/' ); }"
    echo "require_once ABSPATH . 'wp-settings.php';"
  } > "$DIR/wp-config.php"
fi

echo "WordPress on BlitzVM: http://localhost:$PORT/"
cd "$DIR"
exec "$ROOT/bin/php-truffle" serve --port "$PORT" .
