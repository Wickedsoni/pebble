<#
.SYNOPSIS
  Measures Pebble's CPU, memory and (on battery) power while you use it. Writes a CSV + summary.

.EXAMPLE
  # Idle test: start Pebble, leave the pet on screen, don't touch anything for 15 minutes.
  .\tools\perf\measure.ps1 -Label idle -Minutes 15

  # Voice test: during the 5 minutes, give ~10 spoken commands (hold Ctrl+Alt+Space).
  .\tools\perf\measure.ps1 -Label voice -Minutes 5

.NOTES
  CPU % is of the whole machine (all cores), like Task Manager's Processes tab.
  Battery numbers need the laptop unplugged; the discharge rate is the whole system, so run an
  idle baseline with Pebble closed first (-Process none) and compare.
#>
param(
    [string]$Label = "run",
    [double]$Minutes = 10,
    [int]$IntervalSeconds = 2,
    [string]$Process = "Pebble"
)

$cores = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
$out = Join-Path $PSScriptRoot ("pebble-perf-{0}-{1:yyyyMMdd-HHmm}.csv" -f $Label, (Get-Date))
$rows = @()
$end = (Get-Date).AddMinutes($Minutes)

function Battery {
    $b = Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($b -and -not $b.PowerOnline) { [pscustomobject]@{ DischargeMw = $b.DischargeRate; RemainingMwh = $b.RemainingCapacity } }
}

$prev = $null
Write-Host "Measuring '$Process' for $Minutes min ($Label) -> $out"
while ((Get-Date) -lt $end) {
    $now = Get-Date
    $p = if ($Process -ne "none") { Get-Process -Name $Process -ErrorAction SilentlyContinue | Sort-Object WorkingSet64 -Descending | Select-Object -First 1 }
    $bat = Battery
    $cpuPct = $null
    if ($p -and $prev -and $prev.Id -eq $p.Id) {
        $cpuPct = [math]::Round((($p.CPU - $prev.Cpu) / ($now - $prev.Time).TotalSeconds) / $cores * 100, 2)
    }
    if ($p) { $prev = [pscustomobject]@{ Id = $p.Id; Cpu = $p.CPU; Time = $now } }
    $rows += [pscustomobject]@{
        Time         = $now.ToString("HH:mm:ss")
        CpuPercent   = $cpuPct
        WorkingSetMB = if ($p) { [math]::Round($p.WorkingSet64 / 1MB, 1) } else { $null }
        PrivateMB    = if ($p) { [math]::Round($p.PrivateMemorySize64 / 1MB, 1) } else { $null }
        Threads      = if ($p) { $p.Threads.Count } else { $null }
        DischargeMw  = $bat.DischargeMw
        RemainingMwh = $bat.RemainingMwh
    }
    Start-Sleep -Seconds $IntervalSeconds
}
$rows | Export-Csv -NoTypeInformation -Path $out

$cpu = $rows.CpuPercent | Where-Object { $_ -ne $null } | Sort-Object
$ws = $rows.WorkingSetMB | Where-Object { $_ -ne $null }
$dis = $rows.DischargeMw | Where-Object { $_ -gt 0 }
"`n=== Pebble perf: $Label ($Minutes min, $($rows.Count) samples) ==="
if ($cpu) {
    "CPU (whole machine)  avg {0:N2}%   p95 {1:N2}%   max {2:N2}%" -f ($cpu | Measure-Object -Average).Average, $cpu[[int][math]::Floor($cpu.Count * 0.95)], $cpu[-1]
    "Memory (working set) avg {0:N0} MB   max {1:N0} MB" -f ($ws | Measure-Object -Average).Average, ($ws | Measure-Object -Maximum).Maximum
} else { "Process '$Process' not found (is Pebble running?)" }
if ($dis) {
    $used = $rows[0].RemainingMwh - $rows[-1].RemainingMwh
    "Battery: system draw avg {0:N1} W; used {1:N0} mWh in {2} min (compare with a run where Pebble is closed: -Process none)" -f (($dis | Measure-Object -Average).Average / 1000), $used, $Minutes
} else { "Battery: plugged in (unplug to measure power)" }
"CSV: $out"
