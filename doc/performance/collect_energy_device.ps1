param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$Label,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [switch]$Perfetto
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($OutputDirectory)
$destination = Join-Path $root $Label
if (Test-Path -LiteralPath $destination) { throw 'Use a new label; existing evidence is never overwritten.' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$package = 'info.nightscout.androidaps'
$results = [ordered]@{ utc = [DateTime]::UtcNow.ToString('o'); label = $Label; package = $package; commands = @() }
function Capture([string]$Name, [string[]]$Arguments) {
    & $Adb -s $Serial @Arguments *> (Join-Path $destination "$Name.txt")
    $results.commands += [ordered]@{ name = $Name; exitCode = $LASTEXITCODE }
}
Capture 'device' @('shell','getprop','ro.build.fingerprint')
Capture 'package' @('shell','dumpsys','package',$package)
Capture 'battery' @('shell','dumpsys','battery')
Capture 'batterystats' @('shell','dumpsys','batterystats',$package)
Capture 'cpuinfo' @('shell','dumpsys','cpuinfo')
Capture 'power' @('shell','dumpsys','power')
Capture 'jobs' @('shell','dumpsys','jobscheduler')
Capture 'alarms' @('shell','dumpsys','alarm')
Capture 'services' @('shell','dumpsys','activity','services',$package)
Capture 'netstats' @('shell','dumpsys','netstats','detail')
$pidText = (& $Adb -s $Serial shell pidof $package | Out-String).Trim()
$processId = ($pidText -split '\s+' | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
if ($processId) {
    Capture 'process-stat' @('shell','cat',"/proc/$processId/stat")
    Capture 'process-status' @('shell','cat',"/proc/$processId/status")
    Capture 'process-io' @('shell','cat',"/proc/$processId/io")
}
if ($Perfetto) {
    $remoteTrace = "/data/local/tmp/aaps-energy-$Label.perfetto-trace"
    Capture 'perfetto-command' @('shell','perfetto','-o',$remoteTrace,'-t','60s','sched','freq','idle','am','wm')
    Capture 'perfetto-pull' @('pull',$remoteTrace,(Join-Path $destination 'scheduling.perfetto-trace'))
}
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'collection.json') -Encoding utf8
Write-Output $destination
