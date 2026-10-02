<#
.SYNOPSIS
  End-to-end smoke test of the *installed* Pebble: presses Ctrl+Alt+Space, types commands like a person,
  then checks Pebble's database that each one was answered, saved and shown. Cleans up after itself.

.EXAMPLE
  .\tools\test\ui-smoke.ps1          # Pebble must be running; don't touch the keyboard for ~40 s

.NOTES
  Test entries carry the marker "zzpebbletest" and are deleted at the end. Needs Python 3 on PATH
  (for SQLite). Voice isn't covered here (no microphone input); VoiceCommandEvalTest covers that path.
#>
$ErrorActionPreference = "Stop"
if (-not (Get-Process Pebble -ErrorAction SilentlyContinue)) { throw "Start Pebble first." }
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class K { [DllImport("user32.dll")] public static extern void keybd_event(byte k, byte s, uint f, UIntPtr e); }
"@
function Hotkey {
    # Ctrl + Alt + Space, the way a keyboard sends it.
    [K]::keybd_event(0x11, 0, 0, [UIntPtr]::Zero); [K]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
    [K]::keybd_event(0x20, 0, 0, [UIntPtr]::Zero); [K]::keybd_event(0x20, 0, 2, [UIntPtr]::Zero)
    [K]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero); [K]::keybd_event(0x11, 0, 2, [UIntPtr]::Zero)
}
function Say([string]$text) {
    Hotkey; Start-Sleep -Milliseconds 1200
    $escaped = ($text -replace '([\+\^%~\(\)\{\}\[\]])', '{$1}')
    [System.Windows.Forms.SendKeys]::SendWait($escaped); Start-Sleep -Milliseconds 700
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}"); Start-Sleep -Milliseconds 1500
    [System.Windows.Forms.SendKeys]::SendWait("{ESC}"); Start-Sleep -Milliseconds 600
}

$db = Join-Path $env:APPDATA "Pebble\pebble.db"
$py = @"
import sqlite3, sys, json
c = sqlite3.connect(sys.argv[1]); c.row_factory = sqlite3.Row
q = lambda s, *a: [dict(r) for r in c.execute(s, a)]
mode = sys.argv[2]
if mode == 'count':
    print(json.dumps({'turns': q('select count(*) n from conversation_turn')[0]['n']}))
elif mode == 'check':
    since = int(sys.argv[3])
    turns = q('select said, via, did, reply from conversation_turn where id > ? order by id', since)
    notes = q("select text from note where text like '%zzpebbletest%'")
    rem = q("select title, due_at from one_off_reminder where title like '%zzpebbletest%' and done_at is null")
    print(json.dumps({'turns': turns, 'notes': notes, 'reminders': rem}, ensure_ascii=False))
elif mode == 'clean':
    since = int(sys.argv[3])
    c.execute("delete from note where text like '%zzpebbletest%'")
    c.execute("delete from one_off_reminder where title like '%zzpebbletest%'")
    c.execute('delete from conversation_turn where id > ?', (since,))
    c.execute('delete from command_feedback where at_millis >= ?', (int(sys.argv[4]),))
    c.commit(); print('cleaned')
"@
$pyFile = Join-Path $env:TEMP "pebble-smoke.py"; Set-Content -Path $pyFile -Value $py -Encoding utf8
$env:PYTHONIOENCODING = "utf-8"
$before = (python $pyFile $db count | ConvertFrom-Json).turns
$startMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$lastId = [int](python -c "import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute('select coalesce(max(id),0) from conversation_turn').fetchone()[0])" $db)

$commands = @(
    "note: zzpebbletest buy milk and bread",
    "remind me to zzpebbletest call mom at 9pm",
    "kya plan hai tumhara",
    "kya plan hai tumhara",
    "tell me a joke"
)
Write-Host "Typing $($commands.Count) commands into Quick Add..."
foreach ($c in $commands) { Say $c }

$r = python $pyFile $db check $lastId | ConvertFrom-Json
$fail = @()
if ($r.turns.Count -lt $commands.Count) { $fail += "only $($r.turns.Count)/$($commands.Count) commands reached the conversation" }
if (-not $r.notes) { $fail += "note was not saved" }
if (-not $r.reminders) { $fail += "reminder was not saved" }
$plan = @($r.turns | Where-Object { $_.said -eq "kya plan hai tumhara" })
if ($plan.Count -eq 2 -and $plan[0].reply -eq $plan[1].reply) { $fail += "same reply twice in a row: '$($plan[0].reply)'" }
foreach ($t in $r.turns) { if (-not $t.reply) { $fail += "no reply for '$($t.said)'" } }

Write-Host "`nConversation as Pebble saved it:"
$r.turns | ForEach-Object { Write-Host ("  you ({0}): {1}`n  pebble:   {2}   [{3}]" -f $_.via, $_.said, $_.reply, $_.did) }
python $pyFile $db clean $lastId $startMs | Out-Null
if ($fail) { Write-Host "`nFAILED:" -ForegroundColor Red; $fail | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }; exit 1 }
Write-Host "`nPASSED: every command was answered, the note and reminder were saved, replies vary. Test data removed." -ForegroundColor Green
