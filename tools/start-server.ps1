# 启动一个带可见控制台的 Themis Stats 测试服务端。
#
# 不用 nogui：不重定向标准输入，也不隐藏窗口，你会得到一个可以直接输入命令的
# Minecraft 控制台窗口（Minecraft 的控制台命令就是在这个窗口里敲）。
#
# 关闭服务端：在控制台窗口里输入 stop，或者直接关掉那个窗口。

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$server = 'C:\Users\Administrator\Desktop\mod\Themis Stats\server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'

$props = Join-Path $server 'server.properties'
$c = Get-Content $props -Encoding UTF8
$c = $c -replace '^server-port=.*$', 'server-port=25565'
$c = $c -replace '^max-players=.*$', 'max-players=20'
$c = $c -replace '^view-distance=.*$', 'view-distance=8'
$c = $c -replace '^simulation-distance=.*$', 'simulation-distance=6'
$c = $c -replace '^motd=.*$', 'motd=Themis Stats 0.1.0 - Carpet 1.21.11'
$c = $c -replace '^spawn-protection=.*$', 'spawn-protection=0'
# 空服 60 秒后 Minecraft 会暂停 tick，Carpet 假人的连接握手会卡在「已登录但没进游戏」，
# 因此测试服关掉这个暂停。
$c = $c -replace '^pause-when-empty-seconds=.*$', 'pause-when-empty-seconds=0'
Set-Content -Path $props -Value $c -Encoding UTF8

$stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
foreach ($p in $stray) {
    Write-Output "closing old server pid=$($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force
}
if ($stray) { Start-Sleep -Seconds 8 }

Write-Output 'starting server, a console window will appear and you can type commands in it'
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList '-Xms1G', '-Xmx2G', '-jar', "`"$launcher`"" `
    -WorkingDirectory $server `
    -PassThru
Write-Output "server pid=$($proc.Id)"
Set-Content -Path (Join-Path $server 'themisstats.pid') -Value $proc.Id -Encoding ascii
Write-Output "log file : $server\logs\latest.log"
Write-Output "connect  : 127.0.0.1:25565  (offline mode, any name works)"