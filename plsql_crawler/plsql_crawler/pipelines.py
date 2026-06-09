"""Pipeline: 分类 + 去重 + 文件落地。"""

import hashlib
import logging
import os
from collections import Counter

import scrapy
from scrapy.exceptions import DropItem

from .items import (
    ALL_CATEGORIES,
    CATEGORY_OTHER,
    classify_file,
)

logger = logging.getLogger(__name__)


def _safe_name(name: str) -> str:
    """将字符串转成安全的文件名。"""
    return "".join(c if c.isalnum() or c in "._-" else "_" for c in name)


class PlsqlClassificationPipeline:
    """Pipeline 1: 对每个 Item 执行分类。"""

    def process_item(self, item, spider):
        content = item.get("content", "")
        file_name = item.get("file_name", "unknown.sql")

        if not content:
            item["category"] = CATEGORY_OTHER
            item["detected_by"] = "fallback"
            item["confidence"] = 0.0
            return item

        category, detected_by, confidence = classify_file(file_name, content)
        item["category"] = category
        item["detected_by"] = detected_by
        item["confidence"] = confidence
        return item


class PlsqlDeduplicationPipeline:
    """Pipeline 2: 基于文件内容的 SHA256 去重。

    通过 spider 的属性维护一个全局集合，因此同一个爬虫运行期间不会重复写入。
    """

    def __init__(self):
        self._seen = set()

    def process_item(self, item, spider):
        content = item.get("content", "")
        if not content:
            return item

        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if digest not in self._seen:
            self._seen.add(digest)
            return item

        spider.logger.debug("duplicate skipped: %s", item.get("url"))
        raise DropItem(f"Duplicate content: {digest[:12]}")


class PlsqlFileStoragePipeline:
    """Pipeline 3: 按分类写入对应目录。

    目录结构:
      data/
        PROCEDURE/
          <repo_owner>_<repo_name>/
            <file_path_flat>.sql
        FUNCTION/
          ...
        OTHER/
          ...
    """

    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        self._dirs_created = set()
        self._counters = Counter()

    @classmethod
    def from_crawler(cls, crawler):
        data_dir = crawler.settings.get("PLSQL_DATA_DIR", "data")
        return cls(data_dir=data_dir)

    def open_spider(self, spider):
        os.makedirs(self.data_dir, exist_ok=True)
        for cat in ALL_CATEGORIES:
            cat_dir = os.path.join(self.data_dir, cat)
            os.makedirs(cat_dir, exist_ok=True)
            self._dirs_created.add(cat)

    def _ensure_dir(self, category: str, repo: str) -> str:
        """创建 <data_dir>/<category>/<repo>/ 目录。"""
        repo_safe = _safe_name(repo) if repo else "unknown"
        target = os.path.join(self.data_dir, category, repo_safe)
        os.makedirs(target, exist_ok=True)
        return target

    def _output_filename(self, item) -> str:
        """生成输出文件名: file_path 中的 / 换成 __，仍保留原扩展名。"""
        fpath = item.get("file_path", item.get("file_name", "unknown.sql"))
        repo = _safe_name(item.get("repo", "unknown"))
        # 把路径里的 / 替换成 __
        flat = fpath.replace("/", "__")
        # 避免文件名过长
        if len(flat) > 200:
            name, ext = os.path.splitext(flat)
            flat = name[:196] + ext
        return f"{repo}__{flat}"

    def process_item(self, item, spider):
        category = item.get("category", CATEGORY_OTHER) or CATEGORY_OTHER
        repo = item.get("repo", "unknown")
        content = item.get("content", "")
        url = item.get("url", "")

        if not content:
            logger.warning("Empty content from %s, skipped", url)
            return item

        # 写文件
        target_dir = self._ensure_dir(category, repo)
        out_name = self._output_filename(item)
        out_path = os.path.join(target_dir, out_name)

        try:
            with open(out_path, "w", encoding="utf-8", errors="replace") as f:
                f.write(f"-- Source: {url}\n")
                f.write(f"-- Repo: {repo}\n")
                f.write(f"-- Category: {category}\n")
                f.write(f"-- Detected by: {item.get('detected_by', '?')}\n")
                f.write(f"-- Confidence: {item.get('confidence', 0)}\n")
                f.write("-- " + "=" * 70 + "\n")
                f.write(content)
                if not content.endswith("\n"):
                    f.write("\n")
            self._counters[category] += 1
        except OSError as exc:
            logger.error("Failed to write %s: %s", out_path, exc)

        return item

    def close_spider(self, spider):
        """爬虫结束时打印统计摘要。"""
        total = sum(self._counters.values())
        if total == 0:
            spider.logger.info("No files were saved.")
            return

        lines = [f"\n{'='*50}", f"  下载统计 (共 {total} 个文件)", f"{'='*50}"]
        for cat in ALL_CATEGORIES:
            cnt = self._counters.get(cat, 0)
            if cnt:
                pct = cnt / total * 100
                lines.append(f"  {cat:20s}  {cnt:6d}  ({pct:5.1f}%)")
        lines.append(f"{'─'*50}")
        lines.append(f"  {'TOTAL':20s}  {total:6d}  (100.0%)")
        lines.append(f"{'='*50}\n")
        for line in lines:
            spider.logger.info(line)
