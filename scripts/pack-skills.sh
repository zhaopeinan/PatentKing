#!/usr/bin/env bash
# 将 patent-skills 源仓库整理为 Apboa 可导入结构：zip 根目录为 skills/<name>/SKILL.md
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/patent-skills"
OUT="$SRC/_packaged"
STAGE="$OUT/skills"

rm -rf "$STAGE"
mkdir -p "$STAGE"

copy_skill() {
  local dest_name="$1"
  local src_dir="$2"
  local dest="$STAGE/$dest_name"
  mkdir -p "$dest"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --exclude '.git' --exclude '.venv' --exclude 'node_modules' \
      --exclude '__pycache__' --exclude '.DS_Store' \
      "$src_dir/" "$dest/"
  else
    cp -R "$src_dir/." "$dest/"
  fi
  if [[ ! -f "$dest/SKILL.md" ]]; then
    echo "缺少 SKILL.md: $dest" >&2
    exit 1
  fi
  echo "packed $dest_name <- $src_dir"
}

copy_skill "patent-disclosure-skill" "$SRC/handsomestWei-patent-disclosure-skill"
copy_skill "patent-mining-disclosure-skill" "$SRC/patent-mining-disclosure-skill"
copy_skill "patent-disclosure-lite" "$SRC/fuyuxiang-patent-disclosure-skill"
copy_skill "paper2patent" "$SRC/paper2patent/skills/paper2patent"
copy_skill "patentradar" "$SRC/PatentRadar/skills/patentradar"

ZIP="$OUT/patentking-skills.zip"
rm -f "$ZIP"
(cd "$OUT" && zip -qr "patentking-skills.zip" skills)
echo "输出: $ZIP ($(du -h "$ZIP" | awk '{print $1}'))"
echo "导入: 控制台「技能」→ 上传 ZIP，分类填 pk"
