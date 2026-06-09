"""GitHub 代码搜索爬虫：按文件扩展名和内容关键词精确搜索 PL/SQL。

与 github_api_spider 的差异:
  - 使用 GitHub Code Search API 直接搜索代码文件
  - 按扩展名 (.pks, .pkb, .prc, .fnc, .trg, .typ, .tpb, .vw) 精确匹配
  - 按内容关键词（CREATE PROCEDURE, CREATE FUNCTION 等）搜索
  - 搜索结果更精确但受限于 1000 条/查询

环境变量:
  GITHUB_TOKEN: API 访问令牌（推荐）
"""

import base64
import json
import logging
import os
import time

import scrapy

from plsql_crawler.items import classify_file

logger = logging.getLogger(__name__)

GITHUB_API = "https://api.github.com"

# 按扩展名搜索 — 每个扩展名对应其典型分类
EXTENSION_QUERIES = {
    ".pks": "extension:pks",
    ".pkb": "extension:pkb",
    ".pck": "extension:pck",
    ".prc": "extension:prc",
    ".fnc": "extension:fnc",
    ".trg": "extension:trg",
    ".typ": "extension:typ",
    ".tpb": "extension:tpb",
    ".vw":  "extension:vw",
    ".pls": "extension:pls",
}

# 按关键词 + 语言搜索
CONTENT_QUERIES = [
    '"CREATE OR REPLACE PROCEDURE" language:plsql',
    '"CREATE OR REPLACE FUNCTION" language:plsql',
    '"CREATE OR REPLACE PACKAGE BODY" language:plsql',
    '"CREATE OR REPLACE PACKAGE" language:plsql',
    '"CREATE OR REPLACE TRIGGER" language:plsql',
    '"CREATE OR REPLACE TYPE BODY" language:plsql',
    '"CREATE OR REPLACE TYPE" language:plsql',
    '"CREATE OR REPLACE LIBRARY" language:plsql',
]


class GithubCodeSearchSpider(scrapy.Spider):
    """按扩展名和关键词精确搜索 PL/SQL 代码。"""

    name = "github_code_search"
    allowed_domains = ["api.github.com", "raw.githubusercontent.com"]

    custom_settings = {
        "ROBOTSTXT_OBEY": False,
        "DOWNLOAD_DELAY": 1.5,
        "CONCURRENT_REQUESTS": 2,
        "AUTOTHROTTLE_ENABLED": True,
        "AUTOTHROTTLE_START_DELAY": 1.0,
        "AUTOTHROTTLE_MAX_DELAY": 15.0,
        "AUTOTHROTTLE_TARGET_CONCURRENCY": 1.0,
        "ITEM_PIPELINES": {
            "plsql_crawler.pipelines.PlsqlClassificationPipeline": 100,
            "plsql_crawler.pipelines.PlsqlDeduplicationPipeline": 200,
            "plsql_crawler.pipelines.PlsqlFileStoragePipeline": 300,
        },
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.token = os.environ.get("GITHUB_TOKEN", "")
        self._headers = {"Accept": "application/vnd.github.v3+json"}
        if self.token:
            self._headers["Authorization"] = f"token {self.token}"

    def start_requests(self):
        # 1) 按扩展名搜索
        for ext, query in EXTENSION_QUERIES.items():
            url = (
                f"{GITHUB_API}/search/code"
                f"?q={query}&per_page=100"
            )
            yield scrapy.Request(
                url=url,
                headers=self._headers,
                callback=self.parse_code_search,
                meta={"query": query, "page": 1, "ext": ext},
            )

        # 2) 按关键词搜索
        for i, kw_query in enumerate(CONTENT_QUERIES):
            url = (
                f"{GITHUB_API}/search/code"
                f"?q={kw_query}&per_page=100"
            )
            yield scrapy.Request(
                url=url,
                headers=self._headers,
                callback=self.parse_code_search,
                meta={"query": kw_query, "page": 1},
            )

    def parse_code_search(self, response):
        """解析代码搜索结果，下载文件内容。"""
        data = response.json()
        meta = response.meta
        self._check_rate_limit(response)

        items = data.get("items", [])
        total = data.get("total_count", 0)
        self.logger.info(
            "Code search [%s] page %d: %d items (total: %d)",
            meta["query"],
            meta["page"],
            len(items),
            total,
        )

        for code_item in items:
            file_path = code_item.get("path", "")
            file_name = file_path.split("/")[-1]
            repo_full = code_item["repository"]["full_name"]
            raw_url = code_item.get("html_url", "").replace(
                "github.com", "raw.githubusercontent.com"
            ).replace("/blob/", "/")

            # 文件信息
            info = {
                "file_path": file_path,
                "file_name": file_name,
                "repo": repo_full,
                "repo_stars": code_item["repository"].get("stargazers_count", 0),
                "repo_desc": code_item["repository"].get("description", ""),
                "url": raw_url,
            }

            # 下载原始内容
            yield scrapy.Request(
                url=raw_url,
                headers=self._headers,
                callback=self.parse_raw_file,
                meta=info,
                dont_filter=True,
            )

        # 分页 (最多 10 页，即 1000 条结果上限)
        page = meta["page"]
        if page < 10 and len(items) == 100:
            next_url = response.url.replace(
                f"&page={page}", f"&page={page + 1}"
            )
            # 如果原本没有 page 参数，添加它
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
        content = response.text
        if not content or len(content) < 10:
            return
        if len(content) > 5 * 1024 * 1024:  # 5MB
            return

        meta = response.meta
        _, ext = os.path.splitext(meta["file_name"])

        yield {
            "url": meta.get("url", response.url),
            "repo": meta["repo"],
            "file_path": meta["file_path"],
            "file_name": meta["file_name"],
            "extension": ext.lower(),
            "content": content,
            "raw_size": len(content),
            "repo_stars": meta.get("repo_stars", 0),
            "repo_desc": meta.get("repo_desc", ""),
        }

    def _check_rate_limit(self, response):
        """检查速率限制。"""
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
