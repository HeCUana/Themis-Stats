# Live test harness for Themis Stats - scenario 4.
# Waits until the spawned bot is really online before it manipulates it, because Carpet
# resolves the game profile asynchronously and this machine has no access to the Mojang
# session server, which delays the spawn by up to a minute.

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$server = 'C:\Users\Administrator\Desktop\mod\Themis Stats\server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

if (Test-Path $log) { Remove-Item $log -Force }

# Guard: make sure no other server instance still holds the world lock.
$stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*launcher*' }
foreach ($p in $stray) {
    Write-Output "killing stray server pid=$($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if ($stray) { Start-Sleep -Seconds 5 }

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
Write-Output "server pid=$($proc.Id)"
# .NET Framework writes a byte order mark on the very first write to the redirected stdin.
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

# Waits until the named bot shows up in the observation list.
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

$ready = Wait-Log 'Done \(' 240
if (-not $ready) {
    Write-Output "=== SERVER NOT READY (exited=$($proc.HasExited)) ==="
    if (Test-Path $log) { Get-Content $log -Encoding UTF8 | Select-Object -Last 60 }
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}
Write-Output "=== SERVER READY ==="

# ---------------------------------------------------------------- kills (survival)
Send-Cmd 'player Bot_B spawn' 3
Wait-Bot 'Bot_B' 180 | Out-Null
Send-Cmd 'gamemode survival Bot_B' 2
Send-Cmd 'gamemode survival Bot_B' 2
Send-Cmd 'item replace entity Bot_B weapon.mainhand with minecraft:diamond_sword' 3
Send-Cmd 'execute at Bot_B run summon minecraft:zombie ~ ~0.5 ~2' 3
Send-Cmd 'player Bot_B attack continuous' 20
Send-Cmd 'player Bot_B stop' 2
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top kills bot' 4
Send-Cmd 'themisstats detail Bot_B bot kills' 4

# ---------------------------------------------------------------- deaths
Send-Cmd 'damage Bot_B 100 minecraft:generic_kill' 3
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top deaths bot' 4

# ---------------------------------------------------------------- mining and placing (survival)
Send-Cmd 'player Bot_A spawn' 3
Wait-Bot 'Bot_A' 180 | Out-Null
Send-Cmd 'gamemode survival Bot_A' 2
Send-Cmd 'gamemode survival Bot_A' 2
Send-Cmd 'item replace entity Bot_A weapon.mainhand with minecraft:diamond_pickaxe' 2
Send-Cmd 'execute at Bot_A run setblock ^ ^1.6 ^3 minecraft:stone' 2
Send-Cmd 'player Bot_A attack continuous' 10
Send-Cmd 'player Bot_A stop' 2
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top mining bot' 4
Send-Cmd 'themisstats detail Bot_A bot mining' 4

Send-Cmd 'item replace entity Bot_A weapon.mainhand with minecraft:stone' 2
Send-Cmd 'execute at Bot_A run setblock ^ ^1.6 ^3 minecraft:stone' 2
Send-Cmd 'player Bot_A use continuous' 10
Send-Cmd 'player Bot_A stop' 2
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top placement bot' 4

# ---------------------------------------------------------------- ranks, pause, admin
Send-Cmd 'themisstats top online bot' 4
Send-Cmd 'carpet themisStatsRecording false' 2
Send-Cmd 'execute at Bot_A run setblock ^ ^1.6 ^3 minecraft:stone' 2
Send-Cmd 'player Bot_A attack continuous' 6
Send-Cmd 'player Bot_A stop' 2
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top mining bot' 4
Send-Cmd 'carpet themisStatsRecording true' 2

Send-Cmd 'themisstats admin bot role Bot_A mining' 3
Send-Cmd 'themisstats admin bot tag Bot_A add main-farm' 3
Send-Cmd 'themisstats bot Bot_A' 5
Send-Cmd 'themisstats admin exclude Bot_A bot test object' 3
Send-Cmd 'themisstats top mining bot' 4
Send-Cmd 'themisstats admin include Bot_A bot restored' 3
Send-Cmd 'themisstats admin adjust Bot_A bot mining + 5 manual test' 4
Send-Cmd 'themisstats admin audit 1' 4
Send-Cmd 'themisstats admin backup' 8
Send-Cmd 'themisstats admin export mining bot csv' 8
Send-Cmd 'themisstats admin flush' 5
Send-Cmd 'themisstats sessions Bot_A bot 1' 5
Send-Cmd 'stop' 20

if (-not $proc.WaitForExit(60000)) { Write-Output 'server did not exit'; $proc.Kill() }
Write-Output "=== server exited with code $($proc.ExitCode) ==="
