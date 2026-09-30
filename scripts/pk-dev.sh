#!/usr/bin/env bash
# PatentKing Phase 0 helper: ensure OrbStack/Docker, start Apboa simple stack,
# optionally start patent-tools via venv.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOCKER_DIR="$ROOT/docker"
TOOLS_DIR="$DOCKER_DIR/patent-tools"

ensure_docker() {
  if docker info >/dev/null 2>&1; then
    return 0
  fi
  if [[ -d /Applications/OrbStack.app ]]; then
    open -a OrbStack
  elif [[ -d /Applications/Docker.app ]]; then
    open -a Docker
  else
    echo "未找到 OrbStack/Docker Desktop，请先安装并启动。" >&2
    exit 1
  fi
  echo "等待 Docker 守护进程..."
  for _ in $(seq 1 40); do
    if docker info >/dev/null 2>&1; then
      echo "Docker 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "Docker 启动超时" >&2
  exit 1
}

cmd="${1:-help}"

case "$cmd" in
  up|build)
    ensure_docker
    cd "$DOCKER_DIR"
    bash start-simple.sh build
    ;;
  status)
    ensure_docker
    cd "$DOCKER_DIR"
    bash start-simple.sh status || true
    echo "--- patent-tools ---"
    curl -sf http://127.0.0.1:3070/healthz && echo || echo "patent-tools 未在 3070 监听（可用: $0 tools-up）"
    ;;
  stop|down)
    ensure_docker
    cd "$DOCKER_DIR"
    bash start-simple.sh "$cmd"
    ;;
  tools-up)
    cd "$TOOLS_DIR"
    if [[ ! -d .venv ]]; then
      python3 -m venv .venv
      .venv/bin/pip install -U pip
      .venv/bin/pip install -r requirements.txt
    fi
    if curl -sf http://127.0.0.1:3070/healthz >/dev/null 2>&1; then
      echo "patent-tools 已在运行: $(curl -s http://127.0.0.1:3070/healthz)"
      exit 0
    fi
    nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 3070 \
      > /tmp/patent-tools-uvicorn.log 2>&1 &
    sleep 1
    curl -sf http://127.0.0.1:3070/healthz && echo
    ;;
  pack-skills)
    bash "$ROOT/scripts/pack-skills.sh"
    ;;
  tools-down)
    pkill -f "uvicorn app.main:app --host 0.0.0.0 --port 3070" 2>/dev/null || true
    pkill -f "uvicorn app.main:app --host 127.0.0.1 --port 3070" 2>/dev/null || true
    echo "已尝试停止 patent-tools"
    ;;
  mirror|mirrors)
    # 写入 OrbStack 国内源并重启引擎，再可选预拉取
    mkdir -p "$HOME/.orbstack/config"
    cat > "$HOME/.orbstack/config/docker.json" <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockercf.jsdelivr.fyi",
    "https://docker.jsdelivr.fyi"
  ]
}
EOF
    if command -v orb >/dev/null 2>&1; then
      orb restart docker
    else
      echo "未找到 orb CLI，请手动重启 OrbStack 使 mirrors 生效"
    fi
    sleep 2
    docker info 2>/dev/null | grep -A 12 "Registry Mirrors" || true
    if [[ "${2:-}" == "--pull" ]]; then
      bash "$ROOT/scripts/pk-docker-mirror-pull.sh"
    fi
    ;;
  help|*)
    cat <<EOF
用法: $0 <command>

  up|build     启动/构建 Apboa 单机 Docker 栈
  status       查看 Apboa + patent-tools 状态
  stop|down    停止 / 拆除 Apboa 栈
  tools-up     用专用 .venv 启动 patent-tools (:3070)
  tools-down   停止本机 patent-tools
  pack-skills  打包 5 套专利 Skill 为可导入 zip
  mirror       配置 OrbStack 国内 registry-mirrors 并重启
  mirror --pull  同上，并预拉取 mysql/redis/pgvector 等基础镜像

LLM API：登录控制台后在「模型供应商 / 模型配置」填写，勿写入仓库。
详见 docs/dev-environment.md
EOF
    ;;
esac
