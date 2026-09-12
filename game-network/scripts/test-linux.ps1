param(
    [ValidateSet('verify','load')][string]$Mode = 'verify',
    [ValidateSet('tcp','ws','wss')][string]$Protocol = 'tcp',
    [ValidateRange(1,20000)][int]$Connections = 10000,
    [ValidateRange(1,1200)][int]$Seconds = 60,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskResults = Join-Path $taskRoot ('target/linux-' + $Mode + '-' + $Protocol)
New-Item -ItemType Directory -Path $taskResults -Force | Out-Null
$taskDockerArgs = @('run','--rm','--cpus=4','--memory=6g','--pids-limit=1024',
    '--ulimit','nofile=65536:65536',
    '--mount',"type=bind,source=$taskRoot,target=/src,readonly",
    '--mount',"type=bind,source=$taskResults,target=/results",
    '-e',"MODE=$Mode",'-e',"PROTOCOL=$Protocol",'-e',"CONNECTIONS=$Connections",
    '-e',"SECONDS_TO_RUN=$Seconds")
$taskCache = Join-Path $taskRoot '.m2'
if (Test-Path -LiteralPath $taskCache) {
    $taskDockerArgs += @('--mount',"type=bind,source=$taskCache,target=/cache,readonly")
}
if ($Offline) { $taskDockerArgs += @('-e','OFFLINE=1') }
# Official Maven / Temurin 25 image pinned to the digest used for the Linux validation.
$taskDockerArgs += @('maven:3.9-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753',
    'bash','/src/scripts/linux-verify.sh')
& docker @taskDockerArgs
if ($LASTEXITCODE -ne 0) { throw "Linux tests failed; see $taskResults" }
Write-Host "Linux results: $taskResults"
