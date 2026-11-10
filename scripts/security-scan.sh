#!/usr/bin/env bash
# =============================================================================
# 安全扫描闸门  ——  bash scripts/security-scan.sh [--no-network]
#
# 【解决什么问题】
#   业务测试只测功能对不对,不查两件事:
#     ① 有没有人把密码、密钥、私钥不小心提交进代码仓库(一旦进了仓库就等于泄露);
#     ② 用的第三方组件有没有公开的安全漏洞。
#   提交代码前、发布前各跑一次。
#
# 【查什么、怎么判】
#   1. 密钥扫描(硬闸门):仓库里每个文件找私钥、云厂商密钥、AI 密钥、生产 env 文件等特征。命中就失败。
#      确认是假阳性(例如测试用的假值)的,把那一行的特征写进 scripts/security-scan.allow 并写明原因。
#   2. 前端依赖(硬闸门):npm audit 只看会打进网页的依赖(--omit=dev),有 high/critical 就失败。
#   3. 后端依赖(只报告不拦):把 jar 里的组件版本发给公开漏洞库 OSV(api.osv.dev,只发组件名和版本号,
#      不发任何业务数据)查一遍,列出命中项。只报告不拦:
#      出现命中项要按"项目用不用得到那段功能"逐条判断。
#   --no-network:没网时跳过 2、3(1 照跑)。
#
# 【参数】
ALLOW_FILE="scripts/security-scan.allow"
NPM_LEVEL="high"
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
NO_NET=0
[[ "${1:-}" == "--no-network" ]] && NO_NET=1
FAIL=0
say()  { printf '\033[96m[scan]\033[0m %s\n' "$*"; }
good() { printf '\033[92m[ ok ]\033[0m %s\n' "$*"; }
bad()  { printf '\033[91m[FAIL]\033[0m %s\n' "$*"; FAIL=1; }
warn() { printf '\033[93m[warn]\033[0m %s\n' "$*"; }

# ── 1. 密钥扫描 ───────────────────────────────────────────────────────────────
say "1/3 密钥扫描:仓库内全部受控文件(git ls-files)"
HITS=$(git ls-files -z | python3 -c '
import re, sys, os
allow = []
if os.path.exists(sys.argv[1]):
    for l in open(sys.argv[1], encoding="utf-8"):
        l = l.split("#", 1)[0].strip()
        if l: allow.append(l)
rules = [
    ("私钥", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----")),
    ("腾讯云 SecretId", re.compile(r"\bAKID[0-9A-Za-z]{32}\b")),
    ("阿里云 AccessKey", re.compile(r"\bLTAI[0-9A-Za-z]{12,20}\b")),
    ("AI 接口密钥", re.compile(r"\bsk-[A-Za-z0-9_\-]{24,}\b")),
    ("GitHub 令牌", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("前端构建变量里放了密钥(会被打进网页)", re.compile(r"\bVITE_[A-Z_]*(?:KEY|SECRET|TOKEN|PASSWORD)\s*=\s*\S+")),
    ("云存储/接口密钥赋值", re.compile(r"(?i)\b(?:SCM_COS_SECRET_KEY|SECRET_KEY|SA_TOKEN_JWT_SECRET_KEY|AI_API_KEY|WECHAT_[A-Z_]*SECRET)\s*[=:]\s*[\"\x27]?[A-Za-z0-9+/_\-]{16,}")),
]
skip_ext = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".xlsx", ".xls", ".docx", ".doc", ".zip",
            ".jar", ".woff", ".woff2", ".ttf", ".otf", ".mp4", ".mp3", ".wav", ".keystore", ".jks")
files = sys.stdin.buffer.read().split(b"\0")
out = []
for f in files:
    if not f: continue
    p = f.decode("utf-8", "replace")
    if p.lower().endswith(skip_ext) or p.endswith("package-lock.json"): continue
    base = os.path.basename(p)
    # 服务器 env 文件(放密钥的地方)不许进仓库;前端 .env.* 是 Vite 构建参数,本来就公开,只查里面有没有密钥样的值
    if not p.startswith("frontend/") and re.fullmatch(r"(?:.*\.)?env(?:\.prod|\.production)?", base) and not base.endswith(".example"):
        out.append(f"{p}: 服务器 env 文件不应进仓库")
        continue
    try:
        text = open(p, encoding="utf-8", errors="replace").read()
    except OSError:
        continue
    for i, line in enumerate(text.splitlines(), 1):
        for name, rx in rules:
            m = rx.search(line)
            if not m: continue
            if any(a in line for a in allow): continue
            hit = m.group(0)
            masked = hit[:6] + "…" if len(hit) > 8 else hit
            out.append(f"{p}:{i}: {name}  {masked}")
print("\n".join(out))
' "$ALLOW_FILE")
if [[ -n "$HITS" ]]; then
  bad "发现疑似密钥(只显示前几位):"
  printf '%s\n' "$HITS" | head -40
  printf '  → 真密钥:立刻从仓库删除并【作废重发】那把密钥(进过仓库就当已泄露);假阳性:写进 %s 并注明原因\n' "$ALLOW_FILE"
else
  good "未发现密钥"
fi

# ── 2. 前端依赖 ───────────────────────────────────────────────────────────────
if [[ "$NO_NET" -eq 1 ]]; then
  warn "2/3 前端依赖:--no-network 跳过"
else
  say "2/3 前端依赖:npm audit(只看打进网页的依赖,${NPM_LEVEL} 及以上算失败)"
  AUD=$(cd frontend && npm audit --omit=dev --audit-level="$NPM_LEVEL" --json 2>/dev/null)
  SUMMARY=$(printf '%s' "$AUD" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("ERR"); sys.exit()
v = d.get("metadata", {}).get("vulnerabilities", {})
bad = [k + " (" + str(x.get("severity")) + ")" for k, x in d.get("vulnerabilities", {}).items() if x.get("severity") in ("high", "critical")]
print(("BAD " + ", ".join(bad)) if bad else "OK " + str(v.get("total", 0)))
')
  case "$SUMMARY" in
    OK*)  good "前端运行依赖无 high/critical 漏洞(低危/中危共 ${SUMMARY#OK })";;
    BAD*) bad "前端运行依赖有高危漏洞:${SUMMARY#BAD }  → cd frontend && npm audit --omit=dev 看详情,升级到修复版本(发布满两周的版本)";;
    *)    bad "npm audit 没跑起来(网络?);确认没网时可加 --no-network";;
  esac
fi

# ── 3. 后端依赖(只报告) ──────────────────────────────────────────────────────
if [[ "$NO_NET" -eq 1 ]]; then
  warn "3/3 后端依赖:--no-network 跳过"
else
  say "3/3 后端依赖:查公开漏洞库 OSV(只报告,不拦部署)"
  DEPS="$(mktemp)"
  ( cd backend && ./mvnw -q -o dependency:list -DincludeScope=runtime -DoutputFile="$DEPS" >/dev/null 2>&1 \
    || ./mvnw -q dependency:list -DincludeScope=runtime -DoutputFile="$DEPS" >/dev/null 2>&1 )
  python3 - "$DEPS" <<'PY' || warn "后端依赖查询失败(网络?),本次未检查"
import json, re, sys, urllib.request
deps = []
for l in open(sys.argv[1], encoding="utf-8", errors="replace"):
    l = re.sub(r"\x1b\[[0-9;]*m", "", l).strip()
    m = re.match(r"([\w.\-]+):([\w.\-]+):jar:([\w.\-]+):(compile|runtime)", l)
    if m: deps.append((m.group(1) + ":" + m.group(2), m.group(3)))
if not deps:
    print("[warn] 没取到后端依赖清单,本次未检查"); sys.exit(0)
q = {"queries": [{"package": {"ecosystem": "Maven", "name": n}, "version": v} for n, v in deps]}
req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(q).encode(),
                             headers={"Content-Type": "application/json"})
res = json.load(urllib.request.urlopen(req, timeout=60))["results"]
hits = [(n, v, len(r.get("vulns", []))) for (n, v), r in zip(deps, res) if r.get("vulns")]
if not hits:
    print(f"\033[92m[ ok ]\033[0m 后端 {len(deps)} 个运行依赖未命中公开漏洞")
else:
    print(f"\033[93m[warn]\033[0m 后端 {len(deps)} 个运行依赖中 {len(hits)} 个有公开漏洞记录(需按是否用得到逐项判断,详情 https://osv.dev):")
    for n, v, c in hits: print(f"         {n} {v}  ×{c}")
PY
  rm -f "$DEPS"
fi

echo
if [[ "$FAIL" -eq 0 ]]; then
  good "安全扫描闸门通过"
else
  bad "安全扫描闸门未通过"
fi
exit "$FAIL"
