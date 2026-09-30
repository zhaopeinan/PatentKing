#!/usr/bin/env bash
# 登录后上传专利 Skill zip，并创建 PatentKing 默认智能体
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIP="$ROOT/patent-skills/_packaged/patentking-skills.zip"
BASE="${PK_API:-http://127.0.0.1:3060}"
USER="${PK_USER:-admin}"
PASS="${PK_PASS:-Admin@123.com}"

if [[ ! -f "$ZIP" ]]; then
  bash "$ROOT/scripts/pack-skills.sh"
fi

TOKEN=$(curl -sf -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

echo "导入 Skill zip..."
curl -sf -X POST "$BASE/skill/import/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$ZIP" \
  -F "category=pk" \
  -F "cover=true"
echo
echo "引导默认智能体..."
curl -sf -X POST "$BASE/pk/bootstrap/agents" -H "Authorization: Bearer $TOKEN"
echo
echo "状态："
curl -sf "$BASE/pk/bootstrap/status" -H "Authorization: Bearer $TOKEN"
echo
