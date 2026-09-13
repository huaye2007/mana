param(
    [ValidateRange(1, 10)][int]$Forks = 3,
    [ValidateRange(1, 20)][int]$Warmups = 3,
    [ValidateRange(1, 20)][int]$Samples = 5,
    [ValidateRange(100, 10000)][int]$Millis = 250,
    [ValidateSet(1, 2, 4, 8, 16, 32)][int]$Targets = 8,
    [ValidateRange(1, 1024)][int]$Routes = 64,
    [ValidateRange(1, 16)][int]$Producers = 4,
    [ValidateRange(1, 128)][int]$Window = 64,
    [ValidateRange(1000, 1000000)][int]$Messages = 1000000,
    [ValidateSet('all', 'micro', 'load')][string]$Mode = 'all'
)
$ErrorActionPreference = 'Stop'
$runtimeRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $runtimeRoot 'target/classes'
$benchmarkClasses = Join-Path $runtimeRoot 'target/benchmark-classes'
$output = Join-Path $runtimeRoot 'target/benchmarks'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'cn/managame/runtime/execution/GameRuntime.class'))) {
    throw 'Run mvn -pl game-runtime test from the repository root first.'
}
[System.IO.Directory]::CreateDirectory($benchmarkClasses) | Out-Null
[System.IO.Directory]::CreateDirectory($output) | Out-Null
& javac -cp $classes -d $benchmarkClasses (Join-Path $PSScriptRoot 'src/cn/managame/runtime/execution/RuntimeBenchmark.java')
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
$classpath = $classes + [System.IO.Path]::PathSeparator + $benchmarkClasses
$raw = Join-Path $output 'results.jsonl'
[System.IO.File]::WriteAllText($raw, '')
$cpuName = if ($IsWindows) { (Get-CimInstance Win32_Processor | Select-Object -First 1).Name } else { 'See host configuration' }
$configuration = @{ kind = 'configuration'; timestamp = (Get-Date).ToString('o'); cpu = $cpuName
    forks = $Forks; warmups = $Warmups; samples = $Samples; millis = $Millis; targets = $Targets
    routes = $Routes; producers = $Producers; window = $Window; messages = $Messages; mode = $Mode
    jvmFlags = '-Xms256m -Xmx256m -XX:+UseG1GC'
}
[System.IO.File]::AppendAllText($raw, ($configuration | ConvertTo-Json -Compress) + [Environment]::NewLine)

function Run-Benchmark([int]$Fork, [string[]]$Arguments) {
    Write-Host ("Fork {0}/{1}: {2}" -f $Fork, $Forks, ($Arguments -join ' '))
    $lines = & java -Xms256m -Xmx256m -XX:+UseG1GC -cp $classpath cn.managame.runtime.execution.RuntimeBenchmark @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed' }
    foreach ($line in $lines) {
        $entry = $line | ConvertFrom-Json -AsHashtable
        $entry['fork'] = $Fork
        [System.IO.File]::AppendAllText($raw, ($entry | ConvertTo-Json -Compress) + [Environment]::NewLine)
    }
}

# Rotate variant order across forks; every case runs in a fresh JVM.
for ($fork = 1; $fork -le $Forks; $fork++) {
    if ($Mode -ne 'load') {
        foreach ($shape in @('command2', 'command3', 'command4', 'event', 'cron')) {
            $variants = @('direct', 'legacy', 'bound')
            for ($index = 0; $index -lt 3; $index++) {
                $variant = $variants[($index + $fork - 1) % 3]
                Run-Benchmark $fork @('micro', $variant, $shape, "$Targets", "$Millis", "$Warmups", "$Samples")
            }
        }
    }
    if ($Mode -ne 'micro') {
        foreach ($kind in $(if ($fork % 2 -eq 1) { @('dispatch', 'command') } else { @('command', 'dispatch') })) {
            Run-Benchmark $fork @('load', $kind, "$Routes", "$Producers", "$Window", "$Messages", "$Warmups", "$Samples")
        }
    }
}
function Median($Values) {
    $sorted = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2
}
$records = @(Get-Content -LiteralPath $raw | ForEach-Object { $_ | ConvertFrom-Json })
$summary = @()
foreach ($group in ($records | Where-Object kind -eq 'micro' | Group-Object case)) {
    $forkResults = @($group.Group | Group-Object fork | ForEach-Object {
        @{ ns = (Median $_.Group.nsPerOp); bytes = (Median $_.Group.bytesPerOp) }
    })
    $summary += [pscustomobject]@{ kind = 'micro'; case = $group.Name; nsPerOp = (Median $forkResults.ns)
        minForkNs = ($forkResults.ns | Measure-Object -Minimum).Minimum; maxForkNs = ($forkResults.ns | Measure-Object -Maximum).Maximum
        bytesPerOp = (Median $forkResults.bytes) }
}
foreach ($group in ($records | Where-Object kind -eq 'load' | Group-Object case)) {
    $forkResults = @($group.Group | Group-Object fork | ForEach-Object {
        @{ ops = (Median $_.Group.opsPerSecond); p50 = (Median $_.Group.p50Micros); p99 = (Median $_.Group.p99Micros) }
    })
    $summary += [pscustomobject]@{ kind = 'load'; case = $group.Name; opsPerSecond = (Median $forkResults.ops)
        minForkOps = ($forkResults.ops | Measure-Object -Minimum).Minimum; maxForkOps = ($forkResults.ops | Measure-Object -Maximum).Maximum
        p50Micros = (Median $forkResults.p50); p99Micros = (Median $forkResults.p99) }
}
[System.IO.File]::WriteAllText((Join-Path $output 'summary.json'), (ConvertTo-Json -InputObject $summary -Depth 5))
$summary | Where-Object kind -eq 'micro' | Format-Table case, nsPerOp, minForkNs, maxForkNs, bytesPerOp -AutoSize
$summary | Where-Object kind -eq 'load' | Format-Table case, opsPerSecond, minForkOps, maxForkOps, p50Micros, p99Micros -AutoSize
Write-Host "Raw samples: $raw"
