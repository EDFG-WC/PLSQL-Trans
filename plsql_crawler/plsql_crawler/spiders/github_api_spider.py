"""GitHub 爬虫: 搜索 PL/SQL 仓库 → 遍历文件树 → 下载代码 → 分类存储。

优化版:
  - 只用 language:plsql 搜索（去掉了多余的关键词搜索，节省 API 配额）
  - search/repositories 返回的 repo 已含 star 数和描述，不再二次请求
  - 用 git/trees?recursive=1 一次性获取整个仓库的文件树
  - 支持 CLOSESPIDER_ITEMCOUNT 限制下载文件数

环境变量:
  GITHUB_TOKEN  — API 令牌（推荐，5000 req/h）
  PLSQL_MIN_STARS — 最小 star 数过滤
  GITHUB_MAX_REPOS — 最多爬取多少个仓库（默认 500）
"""

import base64
import logging
import os
import time

import scrapy

logger = logging.getLogger(__name__)

GITHUB_API = "https://api.github.com"
PLSQL_EXTS = {".pks", ".pkb", ".pck", ".prc", ".fnc",
              ".trg", ".typ", ".tpb", ".vw", ".pls", ".sql", ".ddl"}
EXCLUDED_FILES = {"package.json", "package-lock.json"}


class GithubPlsqlSpider(scrapy.Spider):
    name = "github_plsql"
    allowed_domains = ["api.github.com", "raw.githubusercontent.com", "github.com"]

    custom_settings = {
        "ROBOTSTXT_OBEY": False,
        "DOWNLOAD_DELAY": 1.0,
        "CONCURRENT_REQUESTS": 3,
        "AUTOTHROTTLE_ENABLED": True,
        "AUTOTHROTTLE_START_DELAY": 1.0,
        "AUTOTHROTTLE_MAX_DELAY": 10.0,
        "AUTOTHROTTLE_TARGET_CONCURRENCY": 2.0,
        "ITEM_PIPELINES": {
            "plsql_crawler.pipelines.PlsqlClassificationPipeline": 100,
            "plsql_crawler.pipelines.PlsqlDeduplicationPipeline": 200,
            "plsql_crawler.pipelines.PlsqlFileStoragePipeline": 300,
        },
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.token = os.environ.get("GITHUB_TOKEN", "")
        self.min_stars = int(os.environ.get("PLSQL_MIN_STARS", "0"))
        self.max_repos = int(os.environ.get("GITHUB_MAX_REPOS", "500"))
        self._headers = {"Accept": "application/vnd.github.v3+json"}
        if self.token:
            self._headers["Authorization"] = f"token {self.token}"
        self._repos_queue = []
        self._repos_done = set()

    # ── Phase 1: 搜索仓库 ──────────────────────────────────
    def start_requests(self):
        url = (f"{GITHUB_API}/search/repositories"
               f"?q=language:plsql&sort=stars&order=desc&per_page=100")
        yield scrapy.Request(url, headers=self._headers,
                             callback=self.parse_repo_search,
                             meta={"page": 1})

    def parse_repo_search(self, response):
        data = response.json()
        self._check_rate_limit(response)
        meta = response.meta
        page = meta["page"]

        for repo in data.get("items", []):
            full_name = repo.get("full_name", "")
            stars = repo.get("stargazers_count", 0)
            if full_name in self._repos_done:
                continue
            if stars < self.min_stars:
                continue
            self._repos_done.add(full_name)
            self._repos_queue.append({
                "full_name": full_name,
                "stars": stars,
                "branch": repo.get("default_branch", "main"),
                "desc": repo.get("description", ""),
            })
            if len(self._repos_queue) >= self.max_repos:
                break

        self.logger.info(
            "Repo search page %d: %d repos (collected %d so far)",
            page, len(data.get("items", [])), len(self._repos_queue))

        # 翻页
        total = data.get("total_count", 0)
        if len(self._repos_queue) < self.max_repos and page < 10 and page * 100 < total:
            yield scrapy.Request(
                url=f"{GITHUB_API}/search/repositories"
                    f"?q=language:plsql&sort=stars&order=desc&per_page=100&page={page+1}",
                headers=self._headers, callback=self.parse_repo_search,
                meta={"page": page + 1})
        else:
            self.logger.info("Repo collection done. Starting tree crawl for %d repos.",
                             len(self._repos_queue))
            for r in self._repos_queue:
                yield from self._crawl_repo(r)

    # ── Phase 2: 遍历文件树 ────────────────────────────────
    def _crawl_repo(self, repo_info):
        full_name = repo_info["full_name"]
        branch = repo_info["branch"]
        url = f"{GITHUB_API}/repos/{full_name}/git/trees/{branch}?recursive=1"
        yield scrapy.Request(url, headers=self._headers,
                             callback=self.parse_repo_tree,
                             meta={"repo_info": repo_info})

    def parse_repo_tree(self, response):
        data = response.json()
        repo_info = response.meta["repo_info"]
        full_name = repo_info["full_name"]

        if "message" in data:
            self.logger.warning("API error for %s: %s", full_name, data["message"])
            return

        for entry in data.get("tree", []):
            if entry.get("type") != "blob":
                continue
            path = entry.get("path", "")
            fname = path.split("/")[-1]
            if fname in EXCLUDED_FILES:
                continue
            _, ext = os.path.splitext(fname)
            if ext.lower() in PLSQL_EXTS:
                yield scrapy.Request(
                    url=f"{GITHUB_API}/repos/{full_name}/contents/{path}",
                    headers=self._headers,
                    callback=self.parse_file_content,
                    meta={"repo_info": repo_info, "file_path": path, "file_name": fname,
                          "extension": ext.lower()})

    # ── Phase 3: 下载内容 ──────────────────────────────────
    def parse_file_content(self, response):
        data = response.json()
        m = response.meta
        b64 = data.get("content", "")
        if data.get("encoding") != "base64" or not b64:
            return

        try:
            decoded = base64.b64decode(b64).decode("utf-8", errors="replace")
        except Exception as exc:
            self.logger.warning("Decode failed %s: %s", m["file_path"], exc)
            return

        if len(decoded) < 10 or len(decoded) > 5 * 1024 * 1024:
            return

        yield {
            "url":      data.get("download_url", response.url),
            "repo":     m["repo_info"]["full_name"],
            "file_path": m["file_path"],
            "file_name": m["file_name"],
            "extension": m["extension"],
            "content":  decoded,
            "raw_size": len(decoded),
            "repo_stars": m["repo_info"]["stars"],
            "repo_desc":  m["repo_info"].get("desc", ""),
        }

    # ── 辅助 ───────────────────────────────────────────────
    def _check_rate_limit(self, response):
        rem = response.headers.get("X-RateLimit-Remaining")
        reset = response.headers.get("X-RateLimit-Reset")
        if rem is not None:
            rem = int(rem)
            if rem < 5 and reset:
                sleep = max(int(reset) - time.time() + 5, 0)
                self.logger.warning("Rate limit low (%d). Sleeping %.0fs", rem, sleep)
                if sleep > 0:
                    time.sleep(sleep)
