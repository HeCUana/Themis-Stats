# 启用 / 关闭 Themis Stats 的性能对照（A30 的可执行部分）。
#
# 说明：本机无法访问 Mojang 会话服务器，每创建一个新假人都要等档案解析超时（约 3 分钟），
# 所以无法达到规格里 20 真人 / 100 假人的验收负载。本脚本 therefore 用**能真正跑起来的负载**
# 做 A/B 对照，并把规模写清楚，绝不把它说成规格里的基准。
#
# 测量方式：
#   * 固定时长（默认 120 秒）的固定工作量（假人持续挖矿 + 周期性查询）
#   * 记录服务端 JVM 的 CPU 时间与峰值内存
#   * 记录服务端日志里出现的掉帧提示（"Can't keep up"）

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'
$modJar = Join-Path $server 'mods\themis-stats-0.1.0.jar'
$durationSeconds = 120

function Invoke-Scenario {
    param([string]$Name, [bool]$WithMod)

    Write-Output ""
    Write-Output "########## 场景: $Name (Themis Stats = $(if ($WithMod) {'启用'} else {'停用'})) ##########"

    # 切换 mod
    $stash = "$modJar.disabled"
    if ($WithMod) {
        if (Test-Path $stash) { Move-Item $stash $modJar -Force }
    } else {
        if (Test-Path $modJar) { Move-Item $modJar $stash -Force }
    }

    try { if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue } } catch { }
    $stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
    foreach ($p in $stray) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($stray) { Start-Sleep -Seconds 8 }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "$env:JAVA_HOME\bin\java.exe"
    $psi.Arguments = "-Xms1G -Xmx2G -jar `"$launcher`" nogui"
    $psi.WorkingDirectory = $server
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $proc.StandardInput.WriteLine('')
    $proc.StandardInput.Flush()

    # 等待就绪
    $ready = $false
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        if (Test-Path $log) {
            $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($c -and $c -match 'Done \(') { $ready = $true; break }
        }
        if ($proc.HasExited) { break }
    }
    if (-not $ready) { Write-Output "场景 $Name 未就绪"; $proc.Kill(); return }

    # 固定工作量：挖矿的假人 + 周期性查询
    $proc.StandardInput.WriteLine('player Bench_A spawn'); $proc.StandardInput.Flush()
    Start-Sleep -Seconds 3
    $proc.StandardInput.WriteLine('gamemode survival Bench_A'); $proc.StandardInput.Flush()
    Start-Sleep -Seconds 2
    $proc.StandardInput.WriteLine('item replace entity Bench_A weapon.mainhand with minecraft:diamond_pickaxe'); $proc.StandardInput.Flush()
    Start-Sleep -Seconds 2
    $proc.StandardInput.WriteLine('execute at Bench_A run setblock ^ ^1.6 ^3 minecraft:stone'); $proc.StandardInput.Flush()
    Start-Sleep -Seconds 2
    $proc.StandardInput.WriteLine('player Bench_A attack continuous'); $proc.StandardInput.Flush()

    $p = Get-Process -Id $proc.Id
    $cpuBefore = $p.TotalProcessorTime.TotalMilliseconds
    $start = Get-Date
    $queries = 0
    while (((Get-Date) - $start).TotalSeconds -lt $durationSeconds) {
        if ($WithMod) {
            $proc.StandardInput.WriteLine('themisstats top mining bot'); $proc.StandardInput.Flush()
            $queries++
        }
        Start-Sleep -Milliseconds 500
    }
    $p.Refresh()
    $cpuAfter = $p.TotalProcessorTime.TotalMilliseconds
    $peak = $p.PeakWorkingSet64
    $elapsed = ((Get-Date) - $start).TotalSeconds

    $proc.StandardInput.WriteLine('player Bench_A stop'); $proc.StandardInput.Flush()
    Start-Sleep -Seconds 2
    $proc.StandardInput.WriteLine('stop'); $proc.StandardInput.Flush()
    if (-not $proc.WaitForExit(60000)) { $proc.Kill() }

    $lag = 0
    $lagDetail = ''
    if (Test-Path $log) {
        $lagLines = Get-Content $log -Encoding UTF8 | Select-String -Pattern "Can't keep up"
        $lag = ($lagLines | Measure-Object).Count
        if ($lag -gt 0) { $lagDetail = ($lagLines | Select-Object -First 3 | ForEach-Object { $_.Line }) -join ' | ' }
    }

    Write-Output ("场景 {0}: 持续 {1:N1} 秒, 期间查询 {2} 次" -f $Name, $elapsed, $queries)
    Write-Output ("  JVM CPU 时间   : {0:N0} ms  ({1:N1} ms/秒)" -f ($cpuAfter - $cpuBefore), (($cpuAfter - $cpuBefore) / $elapsed))
    Write-Output ("  JVM 峰值内存   : {0:N1} MB" -f ($peak / 1MB))
    Write-Output ("  掉帧提示条数   : {0}" -f $lag)
    if ($lagDetail) { Write-Output "  掉帧详情       : $lagDetail" }
}

# 结果同时写到文件，避免控制台输出被截断
$results = Join-Path $root 'tools\benchmark-result.txt'
$lines = New-Object System.Collections.Generic.List[string]
$transcript = {
    param($message)
    $lines.Add([string]$message)
    Write-Host $message
}
Invoke-Scenario -Name '关闭' -WithMod $false
Invoke-Scenario -Name '启用' -WithMod $true
Set-Content -Path $results -Value ($lines -join "`n") -Encoding UTF8
Write-Host "结果已写入 $results"

Write-Output ""
Write-Output "=== 对照结束。规模说明见脚本注释：这是小规模 A/B，不是规格里的 20 真人 / 100 假人基准。 ==="
