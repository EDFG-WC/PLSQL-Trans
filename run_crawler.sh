#!/usr/bin/env bash
# ============================================================
#  PL/SQL 存储过程代码爬虫 — 启动脚本
# ============================================================
# 用法:
#   ./run_crawler.sh                   # 运行主爬虫 (仓库搜索)
#   ./run_crawler.sh search            # 运行代码搜索爬虫 (精确搜索)
#   ./run_crawler.sh test              # 运行快速测试 (仅1页)
#   GITHUB_TOKEN=ghp_xxx ./run_crawler.sh  # 带 token 运行
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/plsql_crawler"
DATA_DIR="$SCRIPT_DIR/data"

export PATH="$HOME/Library/Python/3.9/bin:$PATH"
export PYTHONPATH="$PROJECT_DIR:$PYTHONPATH"

# ── 颜色 ──────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC}  $1"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
err()   { echo -e "${RED}[ERROR]${NC} $1"; }

# ── 检查 scrapy ───────────────────────────────────────────────
if ! command -v scrapy &>/dev/null; then
    err "Scrapy 未安装。请先运行: pip3 install scrapy"
    exit 1
fi

# ── 检查 token ────────────────────────────────────────────────
if [ -n "${GITHUB_TOKEN:-}" ]; then
    ok "GITHUB_TOKEN 已设置"
else
    warn "GITHUB_TOKEN 未设置（未经认证的 API 速率限制: 60 请求/小时）"
    warn "设置方式: export GITHUB_TOKEN='ghp_你的token'"
    echo ""
fi

# ── 创建数据目录 ──────────────────────────────────────────────
mkdir -p "$DATA_DIR"

# ── 显示配置 ──────────────────────────────────────────────────
echo ""
info "========================================="
info "  PL/SQL 存储过程代码爬虫"
info "========================================="
info "  项目目录: $PROJECT_DIR"
info "  数据目录: $DATA_DIR"
info "  爬虫版本: Scrapy $(scrapy version 2>/dev/null)"
echo ""

# ── 选择爬虫 ──────────────────────────────────────────────────
MODE="${1:-full}"
cd "$PROJECT_DIR"

case "$MODE" in
    full)
        info "启动主爬虫 (github_plsql) — 搜索仓库+全量下载"
        echo ""
        scrapy crawl github_plsql \
            -s PLSQL_DATA_DIR="$DATA_DIR" \
            -s LOG_FILE="$DATA_DIR/crawl_$(date +%Y%m%d_%H%M%S).log" \
            -s CLOSESPIDER_PAGECOUNT=500 \
            2>&1 | tee -a "$DATA_DIR/last_run.log"
        ;;
    search)
        info "启动代码搜索爬虫 (github_code_search) — 按扩展名精确搜索"
        echo ""
        scrapy crawl github_code_search \
            -s PLSQL_DATA_DIR="$DATA_DIR" \
            -s LOG_FILE="$DATA_DIR/search_$(date +%Y%m%d_%H%M%S).log" \
            -s CLOSESPIDER_PAGECOUNT=300 \
            2>&1 | tee -a "$DATA_DIR/last_run.log"
        ;;
    test)
        info "启动快速测试 (仅爬取 1 页仓库搜索结果)"
        echo ""
        scrapy crawl github_plsql \
            -s PLSQL_DATA_DIR="$DATA_DIR/test" \
            -s CLOSESPIDER_PAGECOUNT=10 \
            -s DOWNLOAD_DELAY=0.5 \
            -s LOG_LEVEL=DEBUG \
            2>&1 | tee -a "$DATA_DIR/test_run.log"
        ;;
    search_test)
        info "启动搜索测试 (仅爬取 2 页代码搜索结果)"
        echo ""
        scrapy crawl github_code_search \
            -s PLSQL_DATA_DIR="$DATA_DIR/test" \
            -s CLOSESPIDER_PAGECOUNT=10 \
            -s DOWNLOAD_DELAY=0.5 \
            -s LOG_LEVEL=DEBUG \
            2>&1 | tee -a "$DATA_DIR/test_search.log"
        ;;
    stats)
        info "统计已下载的数据"
        echo ""
        if [ -d "$DATA_DIR" ]; then
            echo "  各分类文件数:"
            for cat_dir in "$DATA_DIR"/*/; do
                if [ -d "$cat_dir" ]; then
                    count=$(find "$cat_dir" -type f 2>/dev/null | wc -l | tr -d ' ')
                    echo "    $(basename "$cat_dir"): $count"
                fi
            done
            total=$(find "$DATA_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')
            echo "  总计: $total"
        else
            echo "    暂无数据"
        fi
        ;;
    *)
        echo "用法: $0 [full|search|test|search_test|stats]"
        echo ""
        echo "  full         主爬虫 (默认)"
        echo "  search       代码搜索爬虫"
        echo "  test         主爬虫快速测试"
        echo "  search_test  代码搜索快速测试"
        echo "  stats        统计下载数据"
        exit 1
        ;;
esac

echo ""
ok "完成!"
