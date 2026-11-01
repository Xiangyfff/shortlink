# 生成压测用的"库里真实存在"的短码列表。
#
# 为什么要在本地算而不是查库：1000 万行里随机取 2 万个码，用 ORDER BY RAND() 会全表扫；
# 而 load-data.sql 用的是确定性的置换公式，本地重算一遍结果完全一致（已抽样核对）。
#
# 用法：
#   pwsh scripts/gen-codes.ps1 -Count 20000
param(
    [int]$Count = 20000,
    [string]$Out = (Join-Path $PSScriptRoot 'k6/codes.txt')
)

$ErrorActionPreference = 'Stop'

# 必须和 load-data.sql 里的常量保持一致
$ALPHABET = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
$K = [bigint]2654435761
$MOD = [bigint]3521614606208   # 62^7
$TARGET_ROWS = 10000000

$random = New-Object System.Random(42)
$set = New-Object 'System.Collections.Generic.HashSet[string]'

while ($set.Count -lt $Count) {
    $n = [bigint]$random.Next(0, $TARGET_ROWS)
    $v = ($n * $K) % $MOD
    $chars = New-Object char[] 7
    for ($i = 6; $i -ge 0; $i--) {
        $chars[$i] = $ALPHABET[[int]($v % 62)]
        $v = [bigint]::Divide($v, [bigint]62)
    }
    [void]$set.Add((-join $chars))
}

$dir = Split-Path -Parent $Out
if (-not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}
[System.IO.File]::WriteAllLines($Out, $set, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $($set.Count) codes -> $Out"
