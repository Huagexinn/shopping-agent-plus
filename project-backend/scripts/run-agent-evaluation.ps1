param(
    [ValidateSet('scripted','live')][string]$Mode = 'scripted',
    [ValidateRange(1,10)][int]$Repeats = 3,
    [string]$MySqlBin = "$env:ProgramFiles\MySQL\MySQL Server 8.0\bin",
    [string]$MavenCommand = 'mvn',
    [string]$JavaHome = $env:JAVA_HOME,
    [string[]]$MavenArguments = @(),
    [string]$PythonCommand = 'python',
    [string]$NodeCommand = 'node',
    [string]$Baseline = '',
    [switch]$SkipFrontend
)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = Join-Path $backendRoot ('target\agent-evaluation-' + [Guid]::NewGuid().ToString('N'))
$reporter = Join-Path $PSScriptRoot 'evaluation_report.py'
if ($Mode -eq 'live') {
    foreach ($name in @('EVAL_CHAT_COMPLETIONS_URL','EVAL_API_KEY','EVAL_MODEL')) {
        if (![Environment]::GetEnvironmentVariable($name,'Process')) { throw "Missing $name. Live evaluation is never silently replaced by scripted evaluation." }
    }
}
if ($Baseline -and !(Test-Path -LiteralPath $Baseline -PathType Leaf)) { throw 'Baseline report does not exist.' }
$python = (Get-Command $PythonCommand -ErrorAction Stop).Source
& $python $reporter --output $outputRoot --prepare --mode $Mode --repeats $Repeats
if ($LASTEXITCODE -ne 0) { throw 'Unable to initialize evaluation manifest.' }
$saved = @{}
foreach ($name in @('EVAL_MODE','EVAL_REPEATS','EVAL_OUTPUT_DIR')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$env:EVAL_MODE = $Mode; $env:EVAL_REPEATS = "$Repeats"; $env:EVAL_OUTPUT_DIR = $outputRoot
$frontend = 'not_requested'; $reportTestsPassed = $false; $failure = $null
$runtime = @{python=(& $python --version | Out-String).Trim();javaHome=$JavaHome;maven=$MavenCommand;offline=($MavenArguments -contains '-o');node=$NodeCommand}
try {
    Push-Location $PSScriptRoot
    try { & $python -m unittest test_evaluation_report *> (Join-Path $outputRoot 'reporter-tests.log'); $reportTestsPassed = $LASTEXITCODE -eq 0 }
    finally { Pop-Location }
    if (!$reportTestsPassed) { throw 'Report self-tests failed.' }
    if (!$SkipFrontend) {
        $node = (Get-Command $NodeCommand -ErrorAction Stop).Source
        $runtime['nodeVersion'] = (& $node --version | Out-String).Trim()
        $frontend = 'failed'
        Push-Location (Join-Path $backendRoot '..\project-frontend')
        try {
            & $node node_modules/vue-tsc/bin/vue-tsc.js --build *> (Join-Path $outputRoot 'frontend-types.log')
            if ($LASTEXITCODE -ne 0) { throw 'Frontend type check failed.' }
            & $node node_modules/vite/bin/vite.js build *> (Join-Path $outputRoot 'frontend-build.log')
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
            $frontend = 'passed'
        } finally { Pop-Location }
    }
    & (Join-Path $PSScriptRoot 'test-cart-mysql.ps1') -TestClass EngineeringEvaluation -MySqlBin $MySqlBin -MavenCommand $MavenCommand -JavaHome $JavaHome -MavenArguments $MavenArguments
} catch {
    $failure = $_.Exception.Message
    Write-Warning "Evaluation execution did not complete; see logs in $outputRoot"
} finally {
    [pscustomobject]@{frontend=$frontend;reportTestsPassed=$reportTestsPassed;error=$failure;environment=$runtime} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot 'runner.json')
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
$reportArguments = @($reporter,'--output',$outputRoot)
if ($Baseline) { $reportArguments += @('--baseline',$Baseline) }
& $python @reportArguments
if ($LASTEXITCODE -ne 0) { throw "Evaluation gate failed: $outputRoot\report.json" }
Write-Output "Evaluation passed. Report: $outputRoot\report.md"
