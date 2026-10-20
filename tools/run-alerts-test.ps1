# 运行告警的定向实测：低生命值 → 恢复通知 → 指定统计停滞。
#
# 与 run-p1p2-test.ps1 分开跑，原因是告警需要「假人一直在线且 60 秒宽限期已过」，
# 单独跑更短、更可控。

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

Copy-Item (Join-Path $root 'build\libs\themis-stats-0.1.0.jar') `
    (Join-Path $server 'mods\themis-stats-0.1.0.jar') -Force
Write-Output 'deployed themis-stats jar'

$stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
foreach ($p in $stray) {
    Write-Output "killing stray server pid=$($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if ($stray) { Start-Sleep -Seconds 8 }
try { if (Test-Path $log) { Remove-Item $log -Force -ErrorAction Stop } } catch { Write-Output "log kept: $($_.Exception.Message)" }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "$env:JAVA_HOME\bin\java.exe"
$psi.Arguments = "-Xmx2G -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -jar `"$launcher`" nogui"
$psi.WorkingDirectory = $server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.CreateNoWindow = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$null = $proc.Start()
Write-Output "server pid=$($proc.Id)"
$proc.StandardInput.WriteLine('')
$proc.StandardInput.Flush()

function Wait-Log([string]$pattern, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        if (Test-Path $log) {
            $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($c -and $c -match $pattern) { return $true }
        }
        if ($proc.HasExited) { return $false }
    }
    return $false
}

function Send-Cmd([string]$cmd, [int]$wait = 2) {
    Write-Output "### CMD: $cmd"
    $proc.StandardInput.WriteLine($cmd)
    $proc.StandardInput.Flush()
    Start-Sleep -Seconds $wait
}

function Wait-Bot([string]$name, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        Send-Cmd 'themisstats bots' 2 | Out-Null
        $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($c -and $c -match ([regex]::Escape($name) + "（")) { Write-Output "bot $name is online"; return $true }
        if ($proc.HasExited) { return $false }
    }
    Write-Output "bot $name did NOT come online in time"
    return $false
}

if (-not (Wait-Log 'Done \(' 300)) {
    Write-Output "=== SERVER NOT READY ==="
    if (Test-Path $log) { Get-Content $log -Encoding UTF8 | Select-Object -Last 60 }
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}
Write-Output "=== SERVER READY ==="

Send-Cmd 'player Bot_A spawn' 3
Wait-Bot 'Bot_A' 240 | Out-Null
Send-Cmd 'gamemode survival Bot_A' 2
Send-Cmd 'gamemode survival Bot_A' 2
Send-Cmd 'themisstats admin bot role Bot_A mining' 3
Send-Cmd 'themisstats admin bot owner Bot_A Bot_A' 3

# 先挖一点，让「挖掘量」有一个基准值，之后不再挖矿
Send-Cmd 'item replace entity Bot_A weapon.mainhand with minecraft:diamond_pickaxe' 2
Send-Cmd 'execute at Bot_A run setblock ^ ^1.6 ^3 minecraft:stone' 2
Send-Cmd 'player Bot_A attack continuous' 10
Send-Cmd 'player Bot_A stop' 2
Send-Cmd 'themisstats admin flush' 5
Send-Cmd 'themisstats top mining bot' 4

# 订阅：低生命值 ＋ 指定统计停滞
Send-Cmd 'execute as Bot_A run themisstats watch Bot_A bot low_health' 5
Send-Cmd 'execute as Bot_A run themisstats watch Bot_A bot metric_stall mining' 5
Send-Cmd 'carpet themisStatsBotAlerts true' 3

Write-Output '--- 等待 70 秒，让假人走出 60 秒宽限期 ---'
Start-Sleep -Seconds 70

Write-Output '--- 把假人打到 5 点血（阈值 6），等两次采样确认 ---'
Send-Cmd 'damage Bot_A 15 minecraft:generic' 4
Start-Sleep -Seconds 5
Send-Cmd 'themisstats admin flush' 4

Write-Output '--- 把血量补满，应当收到一次恢复通知 ---'
Send-Cmd 'data merge entity Bot_A {Health:20.0f}' 4
Start-Sleep -Seconds 5
Send-Cmd 'themisstats admin flush' 4

Write-Output '--- 等待 6 分钟，让指定统计停滞窗口到期 ---'
Start-Sleep -Seconds 360
Send-Cmd 'themisstats admin flush' 5
Send-Cmd 'stop' 25

if (-not $proc.WaitForExit(60000)) { Write-Output 'server did not exit'; $proc.Kill() }
Write-Output "=== server exited with code $($proc.ExitCode) ==="

# ---------------------------------------------------------------- 查库
$py = @'
import sqlite3
db = r"C:\Users\Administrator\Desktop\mod\Themis Stats\server\world\themisstats\stats.db"
c = sqlite3.connect("file:" + db + "?mode=ro", uri=True)
print("--- watch_subscription ---")
for r in c.execute("SELECT target_identity, subscriber_uuid, alerts, low_health, low_durability, stall_metric FROM watch_subscription"):
    print(r)
print("--- alert_log ---")
for r in c.execute("SELECT reason, message FROM alert_log ORDER BY id"):
    print(r)
print("--- alert_state ---")
for r in c.execute("SELECT reason, active, last_fired_wall, last_recovered_wall FROM alert_state"):
    print(r)
print("--- stall_window ---")
for r in c.execute("SELECT metric, window_start_wall, last_value FROM stall_window"):
    print(r)
'@
$py | Out-File -FilePath (Join-Path $server 'query-alerts.py') -Encoding utf8
python (Join-Path $server 'query-alerts.py')
