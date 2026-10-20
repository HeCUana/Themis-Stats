# 临时诊断脚本：只看假人创建卡在哪一步。
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

# 不复制 jar：诊断时用已经部署好的那一个（A/B 对照）
if (-not $env:THEMIS_NO_DEPLOY) {
    Copy-Item (Join-Path $root 'build\libs\themis-stats-0.1.0.jar') `
        (Join-Path $server 'mods\themis-stats-0.1.0.jar') -Force
}

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
Write-Output "server pid=$($proc.Id)"

$deadline = (Get-Date).AddSeconds(240)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-Path $log) {
        $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($c -and $c -match 'Done \(') { $ready = $true; break }
    }
}
if (-not $ready) { Write-Output 'server not ready'; $proc.Kill(); exit 1 }

$proc.StandardInput.WriteLine('player Bot_A spawn')
$proc.StandardInput.Flush()
Write-Output 'spawn requested, waiting 90 s'

Start-Sleep -Seconds 90
Write-Output '--- DIAG / join lines ---'
Select-String -Path $log -Pattern '\[DIAG\]|joined the game|logged in|会话开始|观察到' -Encoding UTF8 | ForEach-Object { $_.Line }
$proc.StandardInput.WriteLine('stop')
$proc.StandardInput.Flush()
if (-not $proc.WaitForExit(60000)) { $proc.Kill() }
