param(
    [ValidateRange(1, 10)][int]$Forks = 3,
    [ValidateRange(1, 20)][int]$Warmups = 3,
    [ValidateRange(1, 20)][int]$Samples = 5,
    [ValidateRange(100, 5000)][int]$Millis = 500,
    [ValidateRange(10000, 2000000)][int]$Messages = 500000,
    [ValidateSet('all', 'micro', 'load')][string]$Mode = 'all',
    [string]$RunName = (Get-Date -Format 'yyyy-MM-dd-HHmmss')
)
$ErrorActionPreference = 'Stop'
if ($RunName -notmatch '^[A-Za-z0-9_-]+$') { throw 'RunName may contain only letters, digits, underscore and hyphen' }
$runtimeRoot = Split-Path -Parent $PSScriptRoot
$core = Join-Path $runtimeRoot 'target/classes'
$work = Join-Path $runtimeRoot 'target/bytecode-comparison'
$classes = Join-Path $work 'classes'
$patched = Join-Path $work 'patched'
$sourceDirectory = Join-Path $work 'source/cn/managame/runtime/execution'
$output = Join-Path $work $RunName
foreach ($directory in @($classes, $patched, $sourceDirectory, $output)) {
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
}
if (-not (Test-Path -LiteralPath (Join-Path $core 'cn/managame/runtime/execution/GameRuntime.class'))) {
    throw 'Build the current runtime with mvn -pl game-runtime test first'
}
$sourceRoot = Join-Path $PSScriptRoot 'src/cn/managame/runtime/execution'
$sources = @('BytecodeInvokers.java','BytecodeFixtures.java','BytecodeComparison.java','BytecodeInvokerChecks.java') |
    ForEach-Object { Join-Path $sourceRoot $_ }
& javac -cp $core -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
$separator = [System.IO.Path]::PathSeparator
$boundClasspath = $classes + $separator + $core
$bytecodeClasspath = $patched + $separator + $boundClasspath

# Isolate the experimental binding factory in the benchmark classpath. Production source is untouched.
$binderSource = Join-Path $runtimeRoot 'src/main/java/cn/managame/runtime/execution/HandlerBinder.java'
$binder = [System.IO.File]::ReadAllText($binderSource)
$oldFactory = 'HandlerInvokers.command(target(bean, method), plan.slots(), plan.arguments(), plan.routeSlot())'
$newFactory = 'BytecodeInvokers.command(bean, method, plan.slots(), plan.arguments(), plan.routeSlot())'
if (($binder.Split($oldFactory)).Count -ne 2) { throw 'Expected exactly one command binding factory; review benchmark instrumentation' }
$binder = $binder.Replace($oldFactory, $newFactory)
$localBinder = Join-Path $sourceDirectory 'HandlerBinder.java'
[System.IO.File]::WriteAllText($localBinder, $binder, [System.Text.UTF8Encoding]::new($false))
& javac -cp $boundClasspath -d $patched $localBinder
if ($LASTEXITCODE -ne 0) { throw 'Benchmark Binder compilation failed' }
& java "-Dbytecode.dump=$(Join-Path $output 'generated')" -cp $boundClasspath cn.managame.runtime.execution.BytecodeInvokerChecks
if ($LASTEXITCODE -ne 0) { throw 'Invoker parity verification failed' }
& javap -c -p (Join-Path $output 'generated/GeneratedCommand1.class') |
    Set-Content -LiteralPath (Join-Path $output 'generated-invoker.txt') -Encoding utf8

$raw = Join-Path $output 'samples.jsonl'
$cpu = if ($IsWindows) { (Get-CimInstance Win32_Processor | Select-Object -First 1).Name } else { 'unspecified' }
$coreHash = Get-FileHash -Algorithm SHA256 (Join-Path $core 'cn/managame/runtime/execution/HandlerInvokers.class')
$config = @{
    kind = 'configuration'; timestamp = (Get-Date).ToString('o'); cpu = $cpu
    forks = $Forks; warmups = $Warmups; samples = $Samples; millis = $Millis; messages = $Messages
    activeProtocols = @(1,32); boundProtocols = 32; arities = @(2,4); routes = 64; producers = 4
    window = 64; workers = 4; tasksPerTurn = 32; scheduling = 'PLATFORM/BALANCED'; mode = $Mode
    jvmFlags = '-Xms256m -Xmx256m -XX:+UseG1GC'; invokersSha256 = $coreHash.Hash
}
[System.IO.File]::WriteAllText($raw, ($config | ConvertTo-Json -Compress) + [Environment]::NewLine)

function Run-Case([int]$Fork, [string]$Kind, [string]$Flavor, [int]$Arity, [int]$Protocols) {
    $classpath = if ($Flavor -eq 'bytecode') { $bytecodeClasspath } else { $boundClasspath }
    $amount = if ($Kind -eq 'micro') { $Millis } else { $Messages }
    Write-Host ("Fork {0}/{1}: {2}, {3}, args={4}, activeProtocols={5}" -f $Fork, $Forks, $Kind, $Flavor, $Arity, $Protocols)
    $lines = & java -Xms256m -Xmx256m -XX:+UseG1GC -cp $classpath cn.managame.runtime.execution.BytecodeComparison $Kind $Flavor $Arity $Protocols $amount $Warmups $Samples
    if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $Kind $Flavor $Arity $Protocols" }
    foreach ($line in $lines) {
        $entry = $line | ConvertFrom-Json -AsHashtable
        $entry['fork'] = $Fork
        [System.IO.File]::AppendAllText($raw, ($entry | ConvertTo-Json -Compress) + [Environment]::NewLine)
    }
}
$cases = @()
$kinds = if ($Mode -eq 'all') { @('micro','load') } else { @($Mode) }
foreach ($kind in $kinds) {
    foreach ($arity in @(2,4)) {
        foreach ($protocols in @(1,32)) {
            $cases += [pscustomobject]@{ Kind=$kind; Arity=$arity; Protocols=$protocols }
        }
    }
}
for ($fork = 1; $fork -le $Forks; $fork++) {
    for ($index = 0; $index -lt $cases.Count; $index++) {
        $case = $cases[($index + $fork - 1) % $cases.Count]
        $flavors = if ($fork % 2 -eq 1) { @('bound','bytecode') } else { @('bytecode','bound') }
        foreach ($flavor in $flavors) { Run-Case $fork $case.Kind $flavor $case.Arity $case.Protocols }
    }
}

function Median($Values) {
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2
}
$records = @(Get-Content -LiteralPath $raw | ForEach-Object { $_ | ConvertFrom-Json })
$summary = @()
foreach ($group in ($records | Where-Object { $_.kind -in @('micro','load') } | Group-Object kind, flavor, arity, protocols)) {
    $first = $group.Group[0]
    $metrics = if ($first.kind -eq 'micro') { @('nsPerOp','bytesPerOp') } else { @('opsPerSecond','p50Micros','p99Micros','gcCount') }
    $result = [ordered]@{ kind=$first.kind; flavor=$first.flavor; arity=$first.arity; protocols=$first.protocols }
    foreach ($metric in $metrics) {
        $forkValues = @($group.Group | Group-Object fork | ForEach-Object {
            [pscustomobject]@{ fork=[int]$_.Name; value=(Median $_.Group.$metric) }
        })
        $result[$metric] = Median $forkValues.value
        $result[$metric + 'MinFork'] = ($forkValues.value | Measure-Object -Minimum).Minimum
        $result[$metric + 'MaxFork'] = ($forkValues.value | Measure-Object -Maximum).Maximum
        $result[$metric + 'Forks'] = $forkValues
    }
    $summary += [pscustomobject]$result
}
[System.IO.File]::WriteAllText((Join-Path $output 'summary.json'), (ConvertTo-Json -InputObject $summary -Depth 8))
$summary | Where-Object kind -eq 'micro' | Format-Table flavor, arity, protocols, nsPerOp, nsPerOpMinFork, nsPerOpMaxFork, bytesPerOp -AutoSize
$summary | Where-Object kind -eq 'load' | Format-Table flavor, arity, protocols, opsPerSecond, opsPerSecondMinFork, opsPerSecondMaxFork, p99Micros -AutoSize
Write-Host "Raw samples and summary: $output"
