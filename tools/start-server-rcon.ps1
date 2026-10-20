# 以 RCON 方式启动 Themis Stats 测试服（后台运行 + 日志重定向）。
#
# 与 start-server.ps1 的区别：
#   1. 打开 RCON（enable-rcon=true），自动化脚本可以用 tools/rcon.py 执行命令；
#   2. 后台运行并把控制台输出重定向到 logs\console-out.log，脚本自己读日志；
#   3. 加 -Djava.net.preferIPv4Stack=true，避免 JVM 走 IPv6 连 sessionserver 超时，
#      导致 Carpet 假人拿不到皮肤档案而卡在「已登录但没进游戏」。
#
# 停止服务端：python tools/rcon.py "stop"

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$server = 'C:\Users\Administrator\Desktop\mod\Themis Stats\server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'

# ---- server.properties ----
$props = Join-Path $server 'server.properties'
$c = Get-Content $props -Encoding UTF8
$c = $c -replace '^server-port=.*$', 'server-port=25565'
$c = $c -replace '^max-players=.*$', 'max-players=20'
$c = $c -replace '^view-distance=.*$', 'view-distance=8'
$c = $c -replace '^simulation-distance=.*$', 'simulation-distance=6'
$c = $c -replace '^motd=.*$', 'motd=Themis Stats 0.1.0 - Carpet 1.21.11'
$c = $c -replace '^spawn-protection=.*$', 'spawn-protection=0'
# 空服 60 秒后 Minecraft 会暂停 tick，Carpet 假人的连接握手会卡在「已登录但没进游戏」。
$c = $c -replace '^pause-when-empty-seconds=.*$', 'pause-when-empty-seconds=0'
# ---- RCON ----
$c = $c -replace '^enable-rcon=.*$', 'enable-rcon=true'
$c = $c -replace '^rcon.port=.*$', 'rcon.port=25575'
$c = $c -replace '^rcon.password=.*$', 'rcon.password=themisstats'
$c = $c -replace '^broadcast-rcon-to-ops=.*$', 'broadcast-rcon-to-ops=false'
Set-Content -Path $props -Value $c -Encoding UTF8

# ---- 停掉旧服务端 ----
$stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
foreach ($p in $stray) {
    Write-Output "closing old server pid=$($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force
}
if ($stray) { Start-Sleep -Seconds 8 }

# ---- 启动 ----
$out = Join-Path $server 'logs\console-out.log'
$err = Join-Path $server 'logs\console-err.log'
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList '-Xms1G', '-Xmx2G', '-Djava.net.preferIPv4Stack=true', '-jar', "`"$launcher`"" `
    -WorkingDirectory $server `
    -RedirectStandardOutput $out `
    -RedirectStandardError $err `
    -WindowStyle Hidden `
    -PassThru
Write-Output "server pid=$($proc.Id)"
Set-Content -Path (Join-Path $server 'themisstats.pid') -Value $proc.Id -Encoding ascii
Write-Output "console out : $out"
Write-Output "rcon        : 127.0.0.1:25575 (password: themisstats)"
