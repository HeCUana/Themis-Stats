# P1/P2 实测脚本：在真实服务端上验证运行告警、细分榜、对比、导入、工程计量与 Scarpet 查询。
#
# 流程：
#   1. 部署最新的 jar，生成导入用的 CSV
#   2. 起服，等到「Done (」
#   3. 造一条假人，采集挖掘/放置/伤害/移动/丢弃/拾取数据
#   4. 验证 18 条规则、细分榜、诊断榜、对比、导入、工程、Scarpet
#   5. 打开运行告警：低生命值 → 恢复 → 指定统计停滞
#   6. 停服，用脚本查库确认告警日志
#
# 注意：假人不许被打死（上次实测就是假人中途被僵尸打死，后面的告警测试全落空），
# 所以伤害一律用 /damage 命令制造，假人不参与近战。

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

# ---------------------------------------------------------------- 部署
Copy-Item (Join-Path $root 'build\libs\themis-stats-0.1.0.jar') `
    (Join-Path $server 'mods\themis-stats-0.1.0.jar') -Force
Write-Output 'deployed themis-stats jar'

$importFile = Join-Path $server 'import-test.csv'
$importUuid = '00000000-0000-0000-0000-0000000000a1'
$csv = @(
    "# uuid,name,identity,metric,category,dimension,gameMode,value",
    "$importUuid,imported_bot,bot,mining,minecraft:stone,minecraft:overworld,survival,4242",
    "$importUuid,imported_bot,bot,kills,minecraft:zombie,minecraft:overworld,survival,7",
    "not-a-uuid,broken,bot,mining,x,x,survival,1",
    "$importUuid,broken,bot,not_a_metric,x,x,survival,1"
) -join "`n"
[System.IO.File]::WriteAllText($importFile, $csv, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "wrote $importFile"

# ---------------------------------------------------------------- 起服
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

$ready = Wait-Log 'Done \(' 300
if (-not $ready) {
    Write-Output "=== SERVER NOT READY ==="
    if (Test-Path $log) { Get-Content $log -Encoding UTF8 | Select-Object -Last 80 }
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}
Write-Output "=== SERVER READY ==="

# ---------------------------------------------------------------- 1. 规则
Send-Cmd 'carpet list themisStats' 4
Send-Cmd 'carpet themisStatsBotAlerts' 3
Send-Cmd 'carpet themisStatsPublicModes' 3
Send-Cmd 'carpet themisStatsStallPurposes' 3

# ---------------------------------------------------------------- 2. 造假人
Send-Cmd 'player Bot_A spawn' 3
Wait-Bot 'Bot_A' 240 | Out-Null
Send-Cmd 'gamemode survival Bot_A' 2
Send-Cmd 'gamemode survival Bot_A' 2
Send-Cmd 'themisstats admin bot role Bot_A mining' 3
Send-Cmd 'themisstats admin bot owner Bot_A Bot_A' 3
Send-Cmd 'themisstats admin bot project Bot_A my-farm' 3

# ---------------------------------------------------------------- 3. 采集数据
Send-Cmd 'item replace entity Bot_A weapon.mainhand with minecraft:diamond_pickaxe' 2
Send-Cmd 'execute at Bot_A run setblock ^ ^1.6 ^3 minecraft:stone' 2
Send-Cmd 'player Bot_A attack continuous' 10
Send-Cmd 'player Bot_A stop' 2

Send-Cmd 'item replace entity Bot_A weapon.mainhand with minecraft:stone' 2
Send-Cmd 'execute at Bot_A run setblock ^ ^1.6 ^3 minecraft:stone' 2
Send-Cmd 'player Bot_A use continuous' 8
Send-Cmd 'player Bot_A stop' 2

# 造成伤害：假人打僵尸，但假人不参战
Send-Cmd 'execute at Bot_A run summon minecraft:zombie ~ ~ ~ {NoAI:1b,Health:200.0f,PersistenceRequired:1b,Silent:1b}' 3
Send-Cmd 'damage Zombie 5 minecraft:player_attack by Bot_A' 3
Send-Cmd 'damage Zombie 5 minecraft:player_attack by Bot_A' 3
Send-Cmd 'damage Zombie 5 minecraft:player_attack by Bot_A' 3

# 受到伤害
Send-Cmd 'damage Bot_A 15 minecraft:generic' 3

# 丢弃
Send-Cmd 'item replace entity Bot_A weapon.mainhand with minecraft:diamond' 2
Send-Cmd 'player Bot_A drop' 4

# 拾取
Send-Cmd 'execute at Bot_A run summon minecraft:item ~ ~ ~ {Item:{id:"minecraft:emerald",Count:3}}' 3
Start-Sleep -Seconds 5

Send-Cmd 'themisstats admin flush' 6
Send-Cmd 'themisstats top mining bot' 4
Send-Cmd 'themisstats top damage_dealt bot' 4
Send-Cmd 'themisstats top damage_taken bot' 4
Send-Cmd 'themisstats top dropped bot' 4
Send-Cmd 'themisstats top picked_up bot' 4
Send-Cmd 'themisstats top movement bot' 4

# ---------------------------------------------------------------- 4. 细分榜与诊断榜
Send-Cmd 'themisstats fine mining minecraft:stone bot' 5
Send-Cmd 'themisstats fine movement minecraft:walk bot' 5
Send-Cmd 'themisstats fine damage_dealt minecraft:zombie bot' 5
Send-Cmd 'themisstats admin board mining bot survival' 5
Send-Cmd 'themisstats admin board mining bot survival minecraft:stone minecraft:overworld' 5
Send-Cmd 'themisstats admin board movement bot survival minecraft:walk minecraft:overworld' 5

# ---------------------------------------------------------------- 5. 关注 / 取消关注 / 对比
Send-Cmd 'execute as Bot_A run themisstats watch Bot_A bot low_health' 5
Send-Cmd 'execute as Bot_A run themisstats watch Bot_A bot metric_stall mining' 5
Send-Cmd 'themisstats compare Bot_A bot' 6
Send-Cmd 'themisstats compare Bot_A bot mining' 6
Send-Cmd 'execute as Bot_A run themisstats unwatch Bot_A bot' 5
Send-Cmd 'execute as Bot_A run themisstats watch Bot_A bot' 5

# ---------------------------------------------------------------- 6. 导入
Send-Cmd "themisstats admin import $importFile" 8
Send-Cmd 'themisstats admin imported mining bot' 6
Send-Cmd 'themisstats admin imported kills bot' 6
Send-Cmd 'themisstats top mining bot' 5

# ---------------------------------------------------------------- 7. 工程计量
Send-Cmd 'themisstats project my-farm' 6
Send-Cmd 'themisstats project my-farm mining' 6

# ---------------------------------------------------------------- 8. Scarpet 查询接口
Send-Cmd 'script run print(themis_stats(''mining'',''bot''))' 6
Send-Cmd 'script run print(themis_stats_of(''mining'',''bot'',''Bot_A''))' 6
Send-Cmd 'script run print(themis_stats_top(''mining'',''bot'',1))' 6

# ---------------------------------------------------------------- 9. 运行告警
Send-Cmd 'carpet themisStatsBotAlerts true' 3
Write-Output '--- 等待 70 秒，让假人走出 60 秒宽限期 ---'
Start-Sleep -Seconds 70

Send-Cmd 'damage Bot_A 15 minecraft:generic' 4
Write-Output '--- 等待 4 秒，等两次采样确认 ---'
Start-Sleep -Seconds 4
Send-Cmd 'themisstats admin flush' 4

Send-Cmd 'data merge entity Bot_A {Health:20.0f}' 4
Start-Sleep -Seconds 4
Send-Cmd 'themisstats admin flush' 4

# ---------------------------------------------------------------- 10. 停滞窗口
Write-Output '--- 等待 6 分钟，让指定统计停滞窗口到期（假人期间不再挖矿）---'
Start-Sleep -Seconds 360
Send-Cmd 'themisstats admin flush' 5
Send-Cmd 'themisstats admin audit 1' 6
Send-Cmd 'stop' 25

if (-not $proc.WaitForExit(60000)) { Write-Output 'server did not exit'; $proc.Kill() }
Write-Output "=== server exited with code $($proc.ExitCode) ==="

# ---------------------------------------------------------------- 11. 查库
Write-Output '--- 数据库里的告警日志 ---'
python (Join-Path $root 'tools\inspect_db.py')
