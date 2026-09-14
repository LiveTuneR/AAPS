param([string]$Only = '*')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = Join-Path $root 'build/apex7-final-mutations'
[IO.Directory]::CreateDirectory($out) | Out-Null
$ads = 'plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/data/AutosensDataStoreObject.kt'
$queue = 'core/data/src/main/kotlin/app/aaps/core/data/workflow/LatestPendingCalculation.kt'
$cases = @(
    @{name='clone-anchor'; file=$ads; old='it.lastBucketPass = this.lastBucketPass'; new='it.lastBucketPass = this.lastBucketPass; it.referenceTime = this.referenceTime'; task=':plugins:main:testFullDebugUnitTest'; test='*FastCgmAnchorTest'},
    @{name='persistent-anchor'; file=$ads; old='referenceTime = -1L'; new='referenceTime = referenceTime'; task=':plugins:main:testFullDebugUnitTest'; test='*FastCgmAnchorTest'},
    @{name='shallow-row'; file=$ads; old='for (index in 0 until source.size()) put(source.keyAt(index), source.valueAt(index).deepCopy())'; new='for (index in 0 until source.size()) put(source.keyAt(index), source.valueAt(index))'; task=':plugins:main:testFullDebugUnitTest'; test='*AutosensOwnershipStressTest'},
    @{name='shallow-carbs'; file='core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/AutosensData.kt'; old='it.activeCarbsList = activeCarbsList.map { carb -> carb.copy() }.toMutableList()'; new='it.activeCarbsList = activeCarbsList'; task=':plugins:main:testFullDebugUnitTest'; test='*AutosensOwnershipStressTest'},
    @{name='oldest-pending'; file=$queue; old='pending = state.pending?.merge(intent) ?: intent'; new='pending = state.pending ?: intent'; task=':core:data:test'; test='*LatestPendingCalculationTest'},
    @{name='drop-history'; file=$queue; old='invalidateFrom = listOfNotNull(invalidateFrom, newer.invalidateFrom).minOrNull()'; new='invalidateFrom = listOfNotNull(invalidateFrom, newer.invalidateFrom).maxOrNull()'; task=':core:data:test'; test='*LatestPendingCalculationTest'},
    @{name='replace-starvation'; file='workflow/src/main/kotlin/app/aaps/workflow/CalculationWorkflowImpl.kt'; old='beginUniqueWork(MAIN_CALCULATION, ExistingWorkPolicy.APPEND_OR_REPLACE'; new='beginUniqueWork(MAIN_CALCULATION, ExistingWorkPolicy.REPLACE'; task=':workflow:testFullDebugUnitTest'; test='*ContinuousCgmWorkflowTest'},
    @{name='stale-publish'; file='workflow/src/main/kotlin/app/aaps/workflow/WorkflowChainData.kt'; old='if (stopped() || activeGeneration(job) != generation || (job == MAIN_CALCULATION && mainScheduler?.isCurrent(generation) == false))'; new='if (false)'; task=':workflow:testFullDebugUnitTest'; test='*WorkflowPublicationTest'},
    @{name='unsafe-metadata'; file='core/data/src/main/kotlin/app/aaps/core/data/diagnostics/GlucoseChangeClassifier.kt'; old='normalized == previous'; new='previous.id == current.id'; task=':core:data:test'; test='*GlucoseChangeClassifierTest'},
    @{name='sample-loss'; file='implementation/src/main/kotlin/app/aaps/implementation/telemetry/TherapyTelemetryStore.kt'; old='val now = observedUtc'; new='if (type == "CGM") return true; val now = observedUtc'; task=':implementation:testFullDebugUnitTest'; test='*TherapyTelemetryStoreTest.missing sample*'},
    @{name='secret-leak'; file='implementation/src/main/kotlin/app/aaps/implementation/telemetry/TelemetrySanitizer.kt'; old='fun clean(source: JSONObject): JSONObject = objectValue(source,0)'; new='fun clean(source: JSONObject): JSONObject = source'; task=':implementation:testFullDebugUnitTest'; test='*TherapyTelemetryStoreTest.secret keys*'},
    @{name='duplicate-command'; file='core/interfaces/src/main/kotlin/app/aaps/core/interfaces/queue/Command.kt'; old='val result = execute()'; new='execute(); val result = execute()'; task=':implementation:testFullDebugUnitTest'; test='*CommandTelemetryTest'}
)
$results = @()
Push-Location $root
try {
    foreach ($case in $cases | Where-Object { $_.name -like $Only }) {
        $path = [IO.Path]::GetFullPath((Join-Path $root $case.file))
        if (-not $path.StartsWith($root + [IO.Path]::DirectorySeparatorChar)) { throw 'Mutation outside repository' }
        $bytes = [IO.File]::ReadAllBytes($path)
        $source = [Text.Encoding]::UTF8.GetString($bytes)
        if (-not $source.Contains($case.old)) { throw "Missing mutation target: $($case.name)" }
        $backup = Join-Path $out ($case.name + '.original')
        [IO.File]::WriteAllBytes($backup,$bytes)
        $started = [DateTime]::UtcNow
        try {
            # Deliberate mechanical fault injection. Always restore the exact original bytes.
            [IO.File]::WriteAllText($path,$source.Replace($case.old,$case.new),[Text.UTF8Encoding]::new($false))
            $log = Join-Path $out ($case.name + '.log')
            & .\gradlew.bat $case.task --tests $case.test --no-daemon --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' *> $log
            $code = $LASTEXITCODE
            $module = $case.task.Substring(1,$case.task.LastIndexOf(':')-1).Replace(':',[IO.Path]::DirectorySeparatorChar)
            $testTask = $case.task.Substring($case.task.LastIndexOf(':')+1)
            $xmlRoot = Join-Path $root "$module/build/test-results/$testTask"
            $failures = 0
            Get-ChildItem -LiteralPath $xmlRoot -Filter 'TEST-*.xml' | Where-Object { $_.LastWriteTimeUtc -ge $started } | ForEach-Object {
                [xml]$xml = [IO.File]::ReadAllText($_.FullName)
                $failures += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
            }
            $result = [pscustomobject]@{name=$case.name;exitCode=$code;failingAssertions=$failures;killed=($code -ne 0 -and $failures -gt 0);restored=$false}
        } finally {
            [IO.File]::WriteAllBytes($path,$bytes)
            if ((Get-FileHash -LiteralPath $path).Hash -ne (Get-FileHash -LiteralPath $backup).Hash) { throw 'Mutation restore mismatch' }
        }
        $result.restored=$true
        $results += $result
        $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $out "results-$($Only.Replace('*','all')).json")
        Write-Output "$($case.name): killed=$($result.killed) assertions=$failures restored=true"
        if (-not $result.killed) { throw "Mutation survived or compile/environment failed: $($case.name)" }
    }
} finally { Pop-Location }
