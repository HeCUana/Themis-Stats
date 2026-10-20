# Focused verification of the kill attribution.
#
# The bot combat AI is not used here: Carpet's ATTACK action only strikes an entity for
# non continuous actions, and the bot has to survive long enough. Instead the server side
# attribution is driven directly with "/damage <target> <amount> <type> by <entity>", which
# uses exactly the same damage source path as a real hit.

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$server = 'C:\Users\Administrator\Desktop\mod\Themis Stats\server'
$launcher = Join-Path $server 'fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar'
$log = Join-Path $server 'logs\latest.log'

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
Write-Output "server pid=$($proc.Id)"

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

if (-not (Wait-Log 'Done \(' 240)) {
    Write-Output '=== SERVER NOT READY ==='
    if (Test-Path $log) { Get-Content $log -Encoding UTF8 | Select-Object -Last 40 }
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}
Write-Output '=== SERVER READY ==='

# --- a bot that kills a mob ---
Send-Cmd 'player Bot_K spawn' 3
Wait-Bot 'Bot_K' 200 | Out-Null
Send-Cmd 'gamemode survival Bot_K' 2
Send-Cmd 'gamemode survival Bot_K' 2
Send-Cmd 'execute at Bot_K run summon minecraft:zombie ^ ^0.5 ^2' 3
Send-Cmd 'damage @e[type=zombie,limit=1] 100 minecraft:player_attack by @e[type=player,name=Bot_K,limit=1]' 3
Send-Cmd 'kill @e[type=zombie,limit=1]' 3
Send-Cmd 'gamemode survival Bot_K' 2
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top kills bot' 4
Send-Cmd 'themisstats detail Bot_K bot kills' 4

# --- a bot that kills another bot (player kills player classification) ---
Send-Cmd 'player Bot_V spawn' 3
Wait-Bot 'Bot_V' 200 | Out-Null
Send-Cmd 'gamemode survival Bot_V' 2
Send-Cmd 'gamemode survival Bot_V' 2
Send-Cmd 'damage @e[type=player,name=Bot_V,limit=1] 100 minecraft:player_attack by @e[type=player,name=Bot_K,limit=1]' 3
Send-Cmd 'themisstats admin flush' 4
Send-Cmd 'themisstats top kills bot' 4
Send-Cmd 'themisstats top deaths bot' 4
Send-Cmd 'themisstats detail Bot_K bot kills' 4
Send-Cmd 'themisstats detail Bot_V bot deaths' 4
Send-Cmd 'themisstats sessions Bot_V bot 1' 4
Send-Cmd 'stop' 20

if (-not $proc.WaitForExit(60000)) { Write-Output 'server did not exit'; $proc.Kill() }
Write-Output "=== server exited with code $($proc.ExitCode) ==="
