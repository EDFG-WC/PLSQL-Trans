"""定义爬取数据的 Item 模型。

每个 Item 代表一个从 GitHub 下载的 PL/SQL 代码文件，
经过分类后存储到对应的目录。
"""

import os
import re

import scrapy


class PlsqlCrawlerItem(scrapy.Item):
    """一个 PL/SQL 代码单元的数据模型。"""

    # ── 爬取原始字段 ──────────────────────────────────────────
    url         = scrapy.Field()
    repo        = scrapy.Field()
    file_path   = scrapy.Field()
    file_name   = scrapy.Field()
    extension   = scrapy.Field()
    content     = scrapy.Field()
    raw_size    = scrapy.Field()

    # ── 分类字段 ──────────────────────────────────────────────
    category    = scrapy.Field()
    detected_by = scrapy.Field()
    confidence  = scrapy.Field()

    # ── 辅助信息 ──────────────────────────────────────────────
    repo_stars  = scrapy.Field()
    repo_desc   = scrapy.Field()


# ══════════════════════════════════════════════════════════════
# 分类常量
# ══════════════════════════════════════════════════════════════

CATEGORY_PROCEDURE      = "PROCEDURE"
CATEGORY_FUNCTION       = "FUNCTION"
CATEGORY_PACKAGE        = "PACKAGE"
CATEGORY_PACKAGE_BODY   = "PACKAGE_BODY"
CATEGORY_TRIGGER        = "TRIGGER"
CATEGORY_TYPE           = "TYPE"
CATEGORY_TYPE_BODY      = "TYPE_BODY"
CATEGORY_LIBRARY        = "LIBRARY"
CATEGORY_VIEW           = "VIEW"
CATEGORY_ANONYMOUS      = "ANONYMOUS_BLOCK"
CATEGORY_OTHER          = "OTHER"

# 所有分类的列表
ALL_CATEGORIES = [
    CATEGORY_PROCEDURE,
    CATEGORY_FUNCTION,
    CATEGORY_PACKAGE,
    CATEGORY_PACKAGE_BODY,
    CATEGORY_TRIGGER,
    CATEGORY_TYPE,
    CATEGORY_TYPE_BODY,
    CATEGORY_LIBRARY,
    CATEGORY_VIEW,
    CATEGORY_ANONYMOUS,
    CATEGORY_OTHER,
]

# 扩展名 → 分类（None 表示需内容判定）
EXTENSION_MAP = {
    '.pks': CATEGORY_PACKAGE,
    '.pkb': CATEGORY_PACKAGE_BODY,
    '.pck': CATEGORY_PACKAGE,  # 混合包文件，默认 Packge
    '.prc': CATEGORY_PROCEDURE,
    '.fnc': CATEGORY_FUNCTION,
    '.trg': CATEGORY_TRIGGER,
    '.typ': CATEGORY_TYPE,
    '.tpb': CATEGORY_TYPE_BODY,
    '.vw':  CATEGORY_VIEW,
    '.pls': None,          # 需内容判定
    '.plb': CATEGORY_OTHER,
    '.ddl': None,          # 需内容判定
    '.sql': None,          # 需内容判定（绝大部分 PL/SQL 文件）
}

# 内容匹配规则（优先级从高到低）
# 注意: PACKAGE BODY 必须在 PACKAGE 之前，TYPE BODY 必须在 TYPE 之前
CONTENT_RULES = [
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?PACKAGE\s+BODY\s',
            re.IGNORECASE,
        ),
        CATEGORY_PACKAGE_BODY,
        0.95,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?PACKAGE\s',
            re.IGNORECASE,
        ),
        CATEGORY_PACKAGE,
        0.95,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?PROCEDURE\s',
            re.IGNORECASE,
        ),
        CATEGORY_PROCEDURE,
        0.95,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?FUNCTION\s',
            re.IGNORECASE,
        ),
        CATEGORY_FUNCTION,
        0.95,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?TRIGGER\s',
            re.IGNORECASE,
        ),
        CATEGORY_TRIGGER,
        0.95,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?TYPE\s+BODY\s',
            re.IGNORECASE,
        ),
        CATEGORY_TYPE_BODY,
        0.95,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?TYPE\s',
            re.IGNORECASE,
        ),
        CATEGORY_TYPE,
        0.90,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?LIBRARY\s',
            re.IGNORECASE,
        ),
        CATEGORY_LIBRARY,
        0.90,
    ),
    (
        re.compile(
            r'CREATE\s+(OR\s+REPLACE\s+)?VIEW\s',
            re.IGNORECASE,
        ),
        CATEGORY_VIEW,
        0.80,
    ),
]

# 匿名块检测: 匹配 DECLARE ... BEGIN ... END; 模式
ANONYMOUS_PATTERN = re.compile(
    r'(?:DECLARE\s+)?BEGIN\s+(?:.*?)END;',
    re.IGNORECASE | re.DOTALL,
)


def classify_file(file_name: str, content: str) -> tuple:
    """对 PL/SQL 文件进行分类。

    Args:
        file_name: 文件名（含扩展名）
        content: 文件文本内容

    Returns:
        (category, detected_by, confidence)
    """
    ext = os.path.splitext(file_name)[1].lower()

    # 第一级：扩展名映射
    cat_by_ext = EXTENSION_MAP.get(ext)
    if cat_by_ext is not None:
        return cat_by_ext, "extension", 0.95

    # 第二级：内容关键词判定（扫描前 5000 字符）
    scan_text = content[:5000]
    for regex, cat, conf in CONTENT_RULES:
        if regex.search(scan_text):
            return cat, "content-parse", conf

    # 第三级：匿名块检测
    if ANONYMOUS_PATTERN.search(scan_text):
        return CATEGORY_ANONYMOUS, "content-parse", 0.70

    # 第四级: 回退
    return CATEGORY_OTHER, "fallback", 0.50
