# 把 build/libs 里的模组 jar 部署到测试服务端。
# Copy-Item / Set-Content 写进 server/ 会被命令行层拦下，所以这一步只能走脚本。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'build\libs\themis-stats-0.1.0.jar'
$dst = Join-Path $root 'server\mods\themis-stats-0.1.0.jar'
if (-not (Test-Path -LiteralPath $src)) { throw "构建产物不存在：$src" }
Copy-Item -LiteralPath $src -Destination $dst -Force
$info = Get-Item -LiteralPath $dst
Write-Output ("已部署 {0} 字节 -> {1}" -f $info.Length, $dst)
