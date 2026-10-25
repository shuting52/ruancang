#!/usr/bin/env python3
"""
把软仓的大文件分块拼回原文件。

用法：
    python3 reassemble.py <含 parts/ 与 manifest.json 的文件夹> [输出文件名]

例如从 GitHub 把 apps/微信-8-0-50/ 整个文件夹下载下来后：
    python3 reassemble.py apps/微信-8-0-50
    # 得到 微信-8-0-50.apk（文件名取自 manifest.json 的 originalName）

原理：按 manifest.json 里 parts 的顺序，把 parts/part-001、part-002…
逐块追加写出，并按 sha256 逐块校验（manifest 里是每块的 SHA-256）。
"""
import hashlib
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    folder = Path(sys.argv[1])
    manifest_path = folder / "manifest.json"
    if not manifest_path.exists():
        print(f"找不到 manifest.json：{manifest_path}（这个文件夹不是分块上传的条目）")
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    out_name = sys.argv[2] if len(sys.argv) > 2 else manifest["originalName"]
    out_path = folder / out_name
    parts_dir = folder / "parts"
    total = 0
    with out_path.open("wb") as out:
        for i, part_name in enumerate(manifest["parts"]):
            part_path = parts_dir / part_name
            data = part_path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            expected = manifest["sha256"][i]
            if digest != expected:
                print(f"校验失败：{part_name} 的 SHA-256 与 manifest 不符，已停止（输出文件不完整）")
                return 1
            out.write(data)
            total += len(data)
    print(f"完成：{out_path}（{total} 字节，{len(manifest['parts'])} 块，SHA-256 逐块校验通过）")
    if total != manifest.get("totalSize"):
        print(f"注意：总大小与 manifest 记录的 {manifest.get('totalSize')} 不一致，请检查 parts 是否齐全。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
