"""Build the bundled offline guide from USER_GUIDE.md (requires Python Markdown)."""
from pathlib import Path
import markdown

ROOT = Path(__file__).resolve().parent.parent
renderer = markdown.Markdown(extensions=["tables", "fenced_code", "toc"])
body = renderer.convert((ROOT / "USER_GUIDE.md").read_text(encoding="utf-8"))
body = body.replace("<table>", '<div class="table-wrap"><table>').replace("</table>", "</table></div>")
style = """
:root { color-scheme: light; font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; color: #18283f; background: #edf2f7; font-size: 16px; line-height: 1.8; }
.page { max-width: 1050px; margin: 24px auto; padding: 30px 42px; background: white; border-radius: 16px; }
nav { background: #f4f7fc; border: 1px solid #dce6f5; border-radius: 10px; padding: 12px 18px; }
summary { cursor: pointer; color: #194ea8; font-weight: 650; }
.toc ul { padding-left: 22px; } .toc li { margin: 6px 0; }
h1 { font-size: 30px; line-height: 1.4; margin-top: 24px; }
h2 { font-size: 23px; line-height: 1.5; color: #194ea8; margin-top: 40px; border-top: 1px solid #dce6f5; padding-top: 20px; }
h3 { font-size: 19px; margin-top: 26px; }
p { margin: 12px 0; } li { margin: 8px 0; }
a { color: #194ea8; text-underline-offset: 3px; overflow-wrap: anywhere; }
code { background: #f0f4fa; border-radius: 4px; padding: 2px 5px; overflow-wrap: anywhere; }
.table-wrap { max-width: 100%; overflow-x: auto; margin: 18px 0; }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { border: 1px solid #dce6f5; padding: 10px 12px; text-align: left; vertical-align: top; }
th { background: #f0f5fd; font-weight: 650; } tbody tr:nth-child(even) { background: #fafcff; }
@media (max-width: 600px) {
  body { font-size: 15px; } .page { margin: 0; border-radius: 0; padding: 18px 16px 36px; }
  h1 { font-size: 25px; } h2 { font-size: 20px; } h3 { font-size: 17px; }
  ol, ul { padding-left: 24px; } th, td { padding: 8px; min-width: 100px; }
}
@media print { body { background: white; } .page { margin: 0; max-width: none; padding: 0; } nav { display: none; } h2 { break-after: avoid; } }
"""
html = f'''<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>FIT 文件生成器 · 完整操作说明</title><style>{style}</style>
</head><body><div class="page">
<nav aria-label="操作说明目录"><details><summary>目录（点击展开）</summary>{renderer.toc}</details></nav>
<main>{body}</main></div></body></html>
'''
destination = ROOT / "web" / "user-guide.html"
destination.write_text(html, encoding="utf-8", newline="\n")
print(f"Generated {destination.name} ({len(html.encode('utf-8'))} bytes)")
