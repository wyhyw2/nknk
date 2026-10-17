#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BandSpark 卡密生成器（卖家自用）

与 App 内 VipManager.kt 的 SECRET 保持一致，生成格式：
    BANDSPARK-<到期日hex8>-<随机hex8>-<签名hex16>

用法:
    python3 keygen.py            # 生成 1 张 30 天卡
    python3 keygen.py 30 10      # 生成 10 张 30 天卡
    python3 keygen.py 365 5      # 生成 5 张年卡

⚠️ 卡密离线验证为入门级保护，防止白嫖用户；
   上量后建议改为服务端验证（服务器生成卡密 + App 联网校验）。
"""

import hashlib
import hmac
import secrets
import sys
import time

# 必须与 App 内 VipManager.SECRET 一致
SECRET = "bandspark-2026-huo-hua-shou-huan"


def hmac16(data: str) -> str:
    return hmac.new(SECRET.encode(), data.encode(), hashlib.sha256).hexdigest()[:16]


def gen(days: int, count: int):
    today = int(time.time() // 86400)
    for _ in range(count):
        expire = today + days
        days_hex = "%08x" % expire
        rand_hex = secrets.token_hex(4)
        sig = hmac16(f"{days_hex}-{rand_hex}")
        print(f"BANDSPARK-{days_hex}-{rand_hex}-{sig}")


if __name__ == "__main__":
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    print(f"生成 {count} 张 {days} 天卡密：")
    gen(days, count)
