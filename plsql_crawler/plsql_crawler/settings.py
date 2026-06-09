"""Scrapy 设置文件。"""

import os

BOT_NAME = "plsql_crawler"

SPIDER_MODULES = ["plsql_crawler.spiders"]
NEWSPIDER_MODULE = "plsql_crawler.spiders"

# ── 遵守 robots.txt ──────────────────────────────────────────
ROBOTSTXT_OBEY = False

# ── 请求配置 ─────────────────────────────────────────────────
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)
DOWNLOAD_DELAY = 1.0
CONCURRENT_REQUESTS = 4
CONCURRENT_REQUESTS_PER_DOMAIN = 4

# ── 自动限速 ─────────────────────────────────────────────────
AUTOTHROTTLE_ENABLED = True
AUTOTHROTTLE_START_DELAY = 1.0
AUTOTHROTTLE_MAX_DELAY = 10.0
AUTOTHROTTLE_TARGET_CONCURRENCY = 2.0

# ── 重试 ─────────────────────────────────────────────────────
RETRY_ENABLED = True
RETRY_TIMES = 3
RETRY_HTTP_CODES = [429, 500, 502, 503, 504]

# ── 下载超时 ─────────────────────────────────────────────────
DOWNLOAD_TIMEOUT = 30

# ── 缓存 ─────────────────────────────────────────────────────
HTTPCACHE_ENABLED = True
HTTPCACHE_EXPIRATION_SECS = 3600  # 1 小时
HTTPCACHE_DIR = ".httpcache"

# ── 扩展 ─────────────────────────────────────────────────────
EXTENSIONS = {
    "scrapy.extensions.telnet.TelnetConsole": None,
}

# ── Item Pipeline ────────────────────────────────────────────
ITEM_PIPELINES = {
    "plsql_crawler.pipelines.PlsqlClassificationPipeline": 100,
    "plsql_crawler.pipelines.PlsqlDeduplicationPipeline": 200,
    "plsql_crawler.pipelines.PlsqlFileStoragePipeline": 300,
}

# ── 自定义 ──────────────────────────────────────────────────
# 数据输出目录（相对于项目根目录）
PLSQL_DATA_DIR = os.environ.get("PLSQL_DATA_DIR", "data")

# ── 日志 ─────────────────────────────────────────────────────
LOG_LEVEL = os.environ.get("SCRAPY_LOG_LEVEL", "INFO")
LOG_FORMAT = "%(asctime)s [%(name)s] %(levelname)s: %(message)s"
