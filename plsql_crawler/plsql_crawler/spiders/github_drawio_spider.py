"""GitHub 爬虫: 搜索 drawio 流程图文件。

搜索 GitHub 上公开的 drawio 格式流程图，下载到本地作为参考。
使用 GitHub Code Search API 搜索 .drawio 扩展名的文件。

用法:
  scrapy crawl github_drawio -s PLSQL_DATA_DIR=../../data
  GITHUB_TOKEN=ghp_xxx scrapy crawl github_drawio
"""

import base64
import logging
import os
import time

import scrapy

logger = logging.getLogger(__name__)

GITHUB_API = "https://api.github.com"

DRAWIO_FILE_EXTS = {".drawio", ".dio", ".drawio.svg", ".drawio.png"}
# 搜索关键词组合 — 覆盖不同类型的流程图
SEARCH_QUERIES = [
    'extension:drawio mxGraphModel',
    'extension:drawio flowchart flowchart',
    'extension:drawio "mxCell" "flowchart"',
    'extension:drawio "swimlane" OR "lane"',
]
# 内容关键词过滤（包含以下任一关键词的才保留）
CONTENT_FILTER_KEYWORDS = [
    "mxGraphModel", "mxCell", "flowchart", "swimlane",
    "decision", "process", "start", "end",
]

MAX_RESULTS_PER_QUERY = 200  # 最多 200 条/查询
TARGET_COUNT = 60  # 目标下载数量


class GithubDrawioSpider(scrapy.Spider):
    name = "github_drawio"
    allowed_domains = ["api.github.com", "raw.githubusercontent.com", "github.com"]

    custom_settings = {
        "ROBOTSTXT_OBEY": False,
        "DOWNLOAD_DELAY": 1.5,
        "CONCURRENT_REQUESTS": 2,
        "AUTOTHROTTLE_ENABLED": True,
        "AUTOTHROTTLE_START_DELAY": 1.0,
        "AUTOTHROTTLE_MAX_DELAY": 15.0,
        "AUTOTHROTTLE_TARGET_CONCURRENCY": 1.0,
        "ITEM_PIPELINES": {
            "plsql_crawler.pipelines.PlsqlDeduplicationPipeline": 200,
            "plsql_crawler.pipelines.PlsqlFileStoragePipeline": 300,
        },
        "PLSQL_DATA_DIR": os.environ.get("PLSQL_DATA_DIR", "data"),
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.token = os.environ.get("GITHUB_TOKEN", "")
        self._headers = {"Accept": "application/vnd.github.v3+json"}
        if self.token:
            self._headers["Authorization"] = f"token {self.token}"
        self._downloaded = 0
        self._seen_urls = set()

    def start_requests(self):
        for query in SEARCH_QUERIES:
            url = (
                f"{GITHUB_API}/search/code"
                f"?q={query}&per_page=100"
            )
            self.logger.info("Searching: %s", query)
            yield scrapy.Request(
                url=url,
                headers=self._headers,
                callback=self.parse_code_search,
                meta={"query": query, "page": 1},
            )

    def parse_code_search(self, response):
        """解析代码搜索结果，下载 drawio 文件。"""
        if self._downloaded >= TARGET_COUNT:
            return

        data = response.json()
        meta = response.meta
        self._check_rate_limit(response)

        items = data.get("items", [])
        total = data.get("total_count", 0)
        self.logger.info(
            "Code search [%s] page %d: %d items (total: %d, downloaded: %d/%d)",
            meta["query"][:30],
            meta["page"],
            len(items),
            total,
            self._downloaded,
            TARGET_COUNT,
        )

        for code_item in items:
            if self._downloaded >= TARGET_COUNT:
                break

            # 检查文件扩展名
            file_path = code_item.get("path", "")
            file_name = file_path.split("/")[-1]
            _, ext = os.path.splitext(file_name)
            is_drawio = ext.lower() in DRAWIO_FILE_EXTS or file_name.endswith(".drawio.svg") or file_name.endswith(".drawio.png")

            # 也接受 .drawio.svg 和 .drawio.png
            if not (is_drawio or ".drawio." in file_name):
                continue

            repo_full = code_item["repository"]["full_name"]
            raw_url = code_item.get("html_url", "").replace(
                "github.com", "raw.githubusercontent.com"
            ).replace("/blob/", "/")

            if raw_url in self._seen_urls:
                continue
            self._seen_urls.add(raw_url)

            info = {
                "file_path": file_path,
                "file_name": file_name,
                "repo": repo_full,
                "repo_stars": code_item["repository"].get("stargazers_count", 0),
                "repo_desc": code_item["repository"].get("description", ""),
                "url": raw_url,
            }

            yield scrapy.Request(
                url=raw_url,
                headers=self._headers,
                callback=self.parse_raw_file,
                meta=info,
                dont_filter=True,
            )

        # 翻页
        page = meta["page"]
        if page < 10 and len(items) == 100 and self._downloaded < TARGET_COUNT:
            next_url = response.url.replace(
                f"&page={page}", f"&page={page + 1}"
            )
            if f"&page={page}" not in response.url:
                next_url = response.url + f"&page={page + 1}"
            yield scrapy.Request(
                url=next_url,
                headers=self._headers,
                callback=self.parse_code_search,
                meta={"query": meta["query"], "page": page + 1},
            )

    def parse_raw_file(self, response):
        """处理原始文件内容，生成 Item。"""
        if self._downloaded >= TARGET_COUNT:
            return

        content = response.text
        if not content or len(content) < 100:
            return
        if len(content) > 1024 * 1024:  # 1MB
            return

        # 检查是不是真正的 drawio XML（包含 mxGraphModel）
        if "mxGraphModel" not in content:
            return

        meta = response.meta
        self._downloaded += 1

        self.logger.info(
            "Downloaded [%d/%d]: %s (%s, %d bytes)",
            self._downloaded, TARGET_COUNT,
            meta["file_path"], meta["repo"], len(content),
        )

        yield {
            "url": meta.get("url", response.url),
            "repo": meta["repo"],
            "file_path": meta["file_path"],
            "file_name": meta["file_name"],
            "extension": ".drawio",
            "content": content,
            "raw_size": len(content),
            "repo_stars": meta.get("repo_stars", 0),
            "repo_desc": meta.get("repo_desc", ""),
        }

    def _check_rate_limit(self, response):
        """检查 GitHub API 速率限制。"""
        remaining = response.headers.get("X-RateLimit-Remaining")
        reset_time = response.headers.get("X-RateLimit-Reset")
        if remaining is not None:
            remaining = int(remaining)
            if remaining < 5 and reset_time:
                reset_ts = int(reset_time)
                sleep = max(reset_ts - time.time() + 5, 0)
                self.logger.warning(
                    "Rate limit low (%d). Sleeping %.0fs", remaining, sleep
                )
                if sleep > 0:
                    time.sleep(sleep)
