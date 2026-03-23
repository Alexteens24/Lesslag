#!/usr/bin/env bash
# =============================================================
#  LessLag — Legacy color code migrator
#  Converts &x / §x Bukkit color codes to MiniMessage tags
#  in all .yml files inside the target directory.
#
#  Usage:
#    ./scripts/migrate-legacy-colors.sh [path/to/plugins/LessLag]
#
#  If no path is given, the current directory is used.
#
#  Each modified file is backed up as <name>.yml.bak before editing.
# =============================================================

set -euo pipefail

TARGET="${1:-.}"

if [[ ! -d "$TARGET" ]]; then
  echo "ERROR: '$TARGET' is not a directory." >&2
  exit 1
fi

# Legacy → MiniMessage mapping (code:tag)
declare -A MAP=(
  ["&0"]="<black>"
  ["&1"]="<dark_blue>"
  ["&2"]="<dark_green>"
  ["&3"]="<dark_aqua>"
  ["&4"]="<dark_red>"
  ["&5"]="<dark_purple>"
  ["&6"]="<gold>"
  ["&7"]="<gray>"
  ["&8"]="<dark_gray>"
  ["&9"]="<blue>"
  ["&a"]="<green>"
  ["&b"]="<aqua>"
  ["&c"]="<red>"
  ["&d"]="<light_purple>"
  ["&e"]="<yellow>"
  ["&f"]="<white>"
  ["&k"]="<obfuscated>"
  ["&l"]="<bold>"
  ["&m"]="<strikethrough>"
  ["&n"]="<underlined>"
  ["&o"]="<italic>"
  ["&r"]="<reset>"
)

# Build a single sed script with all substitutions (handles both & and §, case-insensitive)
build_sed_script() {
  local script=""
  for code in "${!MAP[@]}"; do
    local tag="${MAP[$code]}"
    local char="${code:1:1}"
    # Match &x, &X, §x, §X
    script+="s/[&§][${char}${char^^}]/${tag//\//\\/}/g;"
  done
  echo "$script"
}

SED_SCRIPT="$(build_sed_script)"

total=0
modified=0

while IFS= read -r -d '' file; do
  total=$((total + 1))

  # Quick check: does the file contain any legacy code?
  if ! grep -qP '[&§][0-9a-fk-orA-FK-OR]' "$file" 2>/dev/null; then
    continue
  fi

  backup="${file}.bak"
  cp "$file" "$backup"

  # Apply all substitutions in one sed pass
  sed -i "$SED_SCRIPT" "$file"

  count=$(diff <(cat "$backup") <(cat "$file") | grep -c '^[<>]' || true)
  echo "  ✔  $(basename "$file")  (${count} line(s) changed  →  backup: $(basename "$backup"))"
  modified=$((modified + 1))
done < <(find "$TARGET" -maxdepth 1 -name "*.yml" -print0)

echo ""
echo "────────────────────────────────────────────"
echo "  Scanned : $total file(s)"
echo "  Modified: $modified file(s)"

if [[ $modified -eq 0 ]]; then
  echo "  No legacy color codes found. Nothing to do."
fi
echo "────────────────────────────────────────────"
