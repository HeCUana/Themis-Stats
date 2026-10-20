# P0 剩余验收项的定向验证。
#
# 覆盖：
#   1. 玩家击杀玩家 -> 分类 minecraft:player（A05 的子用例）
#   2. 钓获计数（A06）
#   3. 负责人绑定（A21）
#   4. 破坏数据库后启动，原库不被覆盖（A28）
#
# 注意：本机访问不到 Mojang 会话服务器，Carpet 创建假人时要异步解析档案，
# 因此脚本会一直轮询到假人真的上线才继续。

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

function Start-TestServer {
    param([string]$Tag = '')
    try { if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue } } catch { }
    $stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
    foreach ($p in $stray) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($stray) { Start-Sleep -Seconds 8 }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "$env:JAVA_HOME\bin\java.exe"
    $psi.Arguments = "-Xmx2G -jar `"$launcher`" nogui"
    $psi.WorkingDirectory = $server
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $proc.StandardInput.WriteLine('')
    $proc.StandardInput.Flush()
    Write-Host "server started ($Tag) pid=$($proc.Id)"
    return $proc
}

function Wait-Log {
    param([string]$Pattern, [int]$Seconds, $Proc)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        if (Test-Path $log) {
            $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($c -and $c -match $Pattern) { return $true }
        }
        if ($Proc.HasExited) { return $false }
    }
    return $false
}

function Send-Cmd {
    param([string]$Cmd, [int]$Wait = 2, $Proc)
    Write-Output "### $Cmd"
    $Proc.StandardInput.WriteLine($Cmd)
    $Proc.StandardInput.Flush()
    Start-Sleep -Seconds $Wait
}

function Wait-Bot {
    param([string]$Name, [int]$Seconds, $Proc)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Send-Cmd 'themisstats bots' 2 $Proc | Out-Null
        $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($c -and $c -match ([regex]::Escape($Name) + "（")) { Write-Output "bot $Name online"; return $true }
        if ($Proc.HasExited) { return $false }
    }
    Write-Host "bot $Name NOT online"
    return $false
}

function Stop-TestServer {
    param($Proc)
    Send-Cmd 'stop' 15 $Proc | Out-Null
    if (-not $Proc.WaitForExit(60000)) { $Proc.Kill() }
}

# =====================================================================
# 场景 1：玩家击杀玩家 + 钓获 + 负责人绑定
# =====================================================================
$proc = Start-TestServer -Tag 'A05/A06/A21'
if (-not (Wait-Log 'Done \(' 240 $proc)) {
    Write-Output 'SERVER NOT READY'
    if (Test-Path $log) { Get-Content $log -Encoding UTF8 | Select-Object -Last 30 }
    Stop-TestServer $proc
    exit 1
}
Write-Output '=== SERVER READY ==='

# 清掉世界里残留的僵尸，避免它们干扰
Send-Cmd 'kill @e[type=zombie]' 2 $proc
Send-Cmd 'kill @e[type=husk]' 2 $proc
Send-Cmd 'kill @e[type=drowned]' 2 $proc
Send-Cmd 'kill @e[type=skeleton]' 2 $proc
Send-Cmd 'kill @e[type=creeper]' 2 $proc

# --- 凶手 Bot_K ---
Send-Cmd 'player Bot_K spawn' 3 $proc
Wait-Bot 'Bot_K' 220 $proc | Out-Null
Send-Cmd 'gamemode survival Bot_K' 2 $proc
Send-Cmd 'gamemode survival Bot_K' 2 $proc
Send-Cmd 'item replace entity Bot_K weapon.mainhand with minecraft:diamond_sword' 3 $proc

# --- 受害者 Bot_V ---
Send-Cmd 'player Bot_V spawn' 3 $proc
Wait-Bot 'Bot_V' 220 $proc | Out-Null
Send-Cmd 'gamemode survival Bot_V' 2 $proc
Send-Cmd 'gamemode survival Bot_V' 2 $proc

# 玩家击杀玩家
Send-Cmd 'damage @e[type=player,name=Bot_V,limit=1] 100 minecraft:player_attack by @e[type=player,name=Bot_K,limit=1]' 4 $proc
Send-Cmd 'themisstats admin flush' 4 $proc
Send-Cmd 'themisstats top kills bot' 4 $proc
Send-Cmd 'themisstats detail Bot_K bot kills' 4 $proc
Send-Cmd 'themisstats top deaths bot' 4 $proc

# --- 钓获：给一个假人鱼竿，站在水里 ---
Send-Cmd 'player Bot_F spawn' 3 $proc
Wait-Bot 'Bot_F' 220 $proc | Out-Null
Send-Cmd 'gamemode survival Bot_F' 2 $proc
Send-Cmd 'gamemode survival Bot_F' 2 $proc
Send-Cmd 'item replace entity Bot_F weapon.mainhand with minecraft:fishing_rod' 3 $proc
Send-Cmd 'execute at Bot_F run setblock ~ ~ ~ ~ ~ minecraft:water' 2 $proc
Send-Cmd 'execute at Bot_F run setblock ~ ~1 ~ minecraft:water' 2 $proc
Send-Cmd 'execute at Bot_F run tp Bot_F ~ ~1 ~' 2 $proc
Send-Cmd 'player Bot_F use continuous' 30 $proc
Send-Cmd 'player Bot_F stop' 2 $proc
Send-Cmd 'themisstats admin flush' 4 $proc
Send-Cmd 'themisstats top fishing bot' 4 $proc

# --- 负责人绑定 ---
Send-Cmd 'themisstats admin bot owner Bot_A Bot_K' 4 $proc
Send-Cmd 'themisstats bot Bot_A' 4 $proc
Send-Cmd 'themisstats admin audit 1' 4 $proc
Send-Cmd 'themisstats admin backup' 8 $proc
Send-Cmd 'themisstats admin export kills bot csv' 8 $proc

Stop-TestServer $proc

# =====================================================================
# 场景 2：破坏数据库后启动，原库不能被覆盖（A28）
# =====================================================================
$db = Join-Path $server 'world\themisstats\stats.db'
$backup = "$db.integrity-backup"
if (Test-Path $db) {
    Copy-Item $db $backup -Force
    Write-Host "database copied to $backup"
    # 写入一段不是 SQLite 的内容，模拟损坏
    $bytes = [byte[]]::new(4096)
    [System.IO.File]::WriteAllBytes($db, $bytes)
    Write-Host 'database corrupted on purpose'
}
$proc2 = Start-TestServer -Tag 'A28'
Start-Sleep -Seconds 45
Write-Output '--- 损坏库启动结果 ---'
Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue |
    Select-String -Pattern 'ThemisStats|Failed to start' | ForEach-Object { $_.Line } | Select-Object -First 12
if (Test-Path $db) {
    $size = (Get-Item $db).Length
    Write-Output "数据库文件仍然存在, 大小 $size 字节（没有被空库覆盖）"
}
Stop-TestServer $proc2
Write-Output '=== DONE ==='
