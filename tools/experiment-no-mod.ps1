# 对照实验：临时摘掉 Themis Stats 模组，验证「假人无法进服」到底是不是我们模组造成的。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\experiment-no-mod.ps1 on    # 摘掉模组并重启
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\experiment-no-mod.ps1 off   # 放回模组并重启
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\experiment-no-mod.ps1 status
#
# 假人上线判定（不依赖聊天输出，直接查实体与玩家列表）：
#   list                                  -> 玩家数量
#   data get entity @a[limit=1] Pos       -> 有没有玩家实体
#   themisstats 假人状态采样日志          -> 我们模组的在线假人计数

param([ValidateSet('on', 'off', 'status')] [string] $Mode = 'status')

$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$jar = Join-Path $server 'mods\themis-stats-0.1.0.jar'
$disabled = "$jar.disabled"

function Stop-Server {
    $stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
    foreach ($p in $stray) {
        Write-Output "stopping server pid=$($p.ProcessId)"
        Stop-Process -Id $p.ProcessId -Force
    }
    if ($stray) { Start-Sleep -Seconds 8 }
}

switch ($Mode) {
    'on' {
        Stop-Server
        if (Test-Path $jar) { Rename-Item -Path $jar -NewName 'themis-stats-0.1.0.jar.disabled' }
        Write-Output 'Themis Stats 模组已摘除'
        & (Join-Path $root 'tools\start-server-rcon.ps1')
    }
    'off' {
        Stop-Server
        if (Test-Path $disabled) { Rename-Item -Path $disabled -NewName 'themis-stats-0.1.0.jar' }
        Write-Output 'Themis Stats 模组已放回'
        & (Join-Path $root 'tools\start-server-rcon.ps1')
    }
    'status' {
        if (Test-Path $jar) { Write-Output '模组：在位' }
        elseif (Test-Path $disabled) { Write-Output '模组：已摘除' }
        else { Write-Output '模组：状态异常（两个位置都没有）' }
    }
}
