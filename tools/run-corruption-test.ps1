$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$root = 'C:\Users\Administrator\Desktop\mod\Themis Stats'
$server = Join-Path $root 'server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'
$db = Join-Path $server 'world\themisstats\stats.db'

# 先正常启动一次，确认数据库可用
$stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
foreach ($p in $stray) { Stop-Process -Id $p.ProcessId -Force }
if ($stray) { Start-Sleep -Seconds 8 }
try { if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue } } catch { }

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
$deadline = (Get-Date).AddSeconds(180)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    if (Test-Path $log) {
        $c = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($c -and $c -match 'Done \(') { $ready = $true; break }
    }
}
if (-not $ready) { Write-Output 'not ready'; $proc.Kill(); exit 1 }
Write-Output 'baseline server ready'
$proc.StandardInput.WriteLine('stop'); $proc.StandardInput.Flush()
$proc.WaitForExit(60000) | Out-Null
Start-Sleep -Seconds 3

# 备份原始库，然后破坏它
$backup = "$db.pristine"
Copy-Item $db $backup -Force
$before = (Get-FileHash $db -Algorithm SHA256).Hash
$bytes = [byte[]]::new(8192)
for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = 0x41 }
[System.IO.File]::WriteAllBytes($db, $bytes)
Write-Output "database corrupted (was sha256 $before)"

# 用损坏的库启动
try { if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue } } catch { }
$proc2 = New-Object System.Diagnostics.Process
$proc2.StartInfo = $psi
$null = $proc2.Start()
$proc2.StandardInput.WriteLine('')
$proc2.StandardInput.Flush()
Start-Sleep -Seconds 40
Write-Output '--- 损坏库启动结果 ---'
Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue |
    Select-String -Pattern 'ThemisStats|Failed to start|Done \(' | ForEach-Object { $_.Line } | Select-Object -First 14
$after = (Get-FileHash $db -Algorithm SHA256).Hash
Write-Output "损坏后的库 sha256 = $after"
Write-Output "原库是否被覆盖 = $($after -eq $before)"
Write-Output "损坏库大小 = $((Get-Item $db).Length) 字节（原库 $((Get-Item $backup).Length) 字节）"
$proc2.StandardInput.WriteLine('stop'); $proc2.StandardInput.Flush()
if (-not $proc2.WaitForExit(30000)) { $proc2.Kill() }