#!/usr/bin/env bash
# 通过国内镜像显式拉取并 retag 为 Compose 期望的本地名（registry-mirrors 失效时的兜底）
set -euo pipefail

# DaoCloud 公共加速（library 官方镜像需带 library/ 前缀）
MIRROR="${DOCKER_CN_MIRROR:-docker.m.daocloud.io}"

images_library=(
  "mysql:8.0"
  "redis:7-alpine"
  "maven:3.9-eclipse-temurin-21"
  "eclipse-temurin:21-jre"
  "node:22"
  "nginx:alpine"
  "python:3.12-slim"
)

images_other=(
  "pgvector/pgvector:pg16"
)

pull_tag() {
  local src="$1"
  local dst="$2"
  echo ">>> $src  ->  $dst"
  docker pull "$src"
  docker tag "$src" "$dst"
}

echo "使用镜像前缀: $MIRROR"
for img in "${images_library[@]}"; do
  pull_tag "$MIRROR/library/$img" "$img"
done
for img in "${images_other[@]}"; do
  pull_tag "$MIRROR/$img" "$img"
done

echo "全部基础镜像已就绪（已 retag）。可执行: bash scripts/pk-dev.sh build"
