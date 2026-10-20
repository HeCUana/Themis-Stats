# 临时：验证 /themisstats admin bot 这一条分支能不能解析。
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

$stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
foreach ($p in $stray) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
if ($stray) { Start-Sleep -Seconds 8 }
try { if (Test-Path $log) { Remove-Item $log -Force -ErrorAction Stop } } catch { }

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

$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-Path $log) {
        $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($c -and $c -match 'Done \(') { break }
    }
}
Write-Output '=== server ready ==='

$proc.StandardInput.WriteLine('player Bot_A spawn')
$proc.StandardInput.Flush()
Write-Output '--- 等假人上线（最多 120 秒）---'
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($c -and $c -match 'bot_a joined the game') { Write-Output '假人已上线'; break }
}
Select-String -Path $log -Pattern 'joined the game|logged in|会话开始' -Encoding UTF8 | ForEach-Object { $_.Line }

foreach ($cmd in @(
    'themisstats admin bot role Bot_A mining',
    'themisstats admin bot owner Bot_A Bot_A',
    'themisstats admin project Bot_A my-farm',
    'themisstats project my-farm mining',
    'themisstats fine mining minecraft:stone bot',
    'themisstats admin board mining bot survival minecraft:stone minecraft:overworld',
    'themisstats compare Bot_A bot mining',
    'script run print(themis_stats(''mining'',''bot''))',
    'script run print(themis_stats_of(''mining'',''bot'',''Bot_A''))',
    'script run print(themis_stats_top(''mining'',''bot'',1))',
    'themisstats admin imported mining bot')) {
    Write-Output "### $cmd"
    $proc.StandardInput.WriteLine($cmd)
    $proc.StandardInput.Flush()
    Start-Sleep -Seconds 2
}

Start-Sleep -Seconds 3
Write-Output '--- 输出 ---'
Select-String -Path $log -Pattern 'Incorrect|Unknown|Incorrect argument|已设置|已把|没有成员|导入历史|对象有导入历史|用途|负责人|备注|标签' -Encoding UTF8 | ForEach-Object { $_.Line }
$proc.StandardInput.WriteLine('stop')
$proc.StandardInput.Flush()
if (-not $proc.WaitForExit(60000)) { $proc.Kill() }
