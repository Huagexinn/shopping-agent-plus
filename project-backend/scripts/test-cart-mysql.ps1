param(
    [string]$MySqlBin = "$env:ProgramFiles\MySQL\MySQL Server 8.0\bin",
    [string]$MavenCommand = 'mvn',
    [string]$JavaHome = $env:JAVA_HOME,
    [string[]]$MavenArguments = @(),
    [ValidateSet('CartOperationMysqlIT', 'AgentRunMysqlIT', 'AgentRecoveryMysqlIT', 'AgentRecoveryBrowserIT', 'OrderConfirmationMysqlIT', 'OrderConfirmationBrowserIT', 'EngineeringEvaluation')]
    [string]$TestClass = 'CartOperationMysqlIT'
)

$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runRoot = Join-Path $backendRoot ('target\mysql-validation-' + [Guid]::NewGuid().ToString('N'))
$dataRoot = Join-Path $runRoot 'data'
$mysqld = Join-Path $MySqlBin 'mysqld.exe'
$mysql = Join-Path $MySqlBin 'mysql.exe'
$mysqladmin = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($binary in @($mysqld, $mysql, $mysqladmin)) {
    if (!(Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Missing MySQL binary: $binary" }
}
$maven = (Get-Command $MavenCommand -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
$serverProcess = $null
$serverVerified = $false
$testExit = 1
$cleanupSucceeded = $false
$port = 0
$version = $null
$failureMessage = $null
$environmentNames = @('JAVA_HOME', 'SHOPPING_TEST_MYSQL_URL', 'SHOPPING_TEST_MYSQL_USER', 'SHOPPING_TEST_MYSQL_PASSWORD')
$originalEnvironment = @{}
foreach ($name in $environmentNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$evaluation = $TestClass -eq 'EngineeringEvaluation'
$testSelection = $TestClass
if ($evaluation) {
    $testSelection = 'AiRequestIdentityTest,CartRequestRetryTest,NativeToolPolicyTest,AiGroundingValidatorTest,CartToolGovernanceTest,CartOperationMysqlIT,AgentRunMysqlIT,AgentRecoveryMysqlIT,OrderConfirmationMysqlIT,AgentBusinessEvaluationIT,BusinessIntegrityOracleIT,EvaluationModelDriverTest'
    if (!$env:EVAL_OUTPUT_DIR) { throw 'Use run-agent-evaluation.ps1 to initialize the evaluation output directory.' }
    $evaluationRoot = [IO.Path]::GetFullPath($env:EVAL_OUTPUT_DIR)
    $targetPrefix = [IO.Path]::GetFullPath((Join-Path $backendRoot 'target')) + [IO.Path]::DirectorySeparatorChar
    if (!$evaluationRoot.StartsWith($targetPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evaluation output must be inside backend/target.' }
    New-Item -ItemType Directory -Path (Join-Path $evaluationRoot 'junit') -Force | Out-Null
    # 删除本轮选定套件的旧结果，不能把上次通过的报告算成本次证据。
    foreach ($suite in $testSelection.Split(',')) {
        Get-ChildItem -Path (Join-Path $backendRoot "target\surefire-reports\TEST-*.$suite.xml") -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    }
}

try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    Write-Output "Initializing isolated MySQL data directory: $runRoot"
    $initialize = Start-Process -FilePath $mysqld -WindowStyle Hidden -PassThru -Wait `
        -ArgumentList @('--no-defaults', '--initialize-insecure', ('--datadir="' + $dataRoot + '"'), '--console') `
        -RedirectStandardOutput (Join-Path $runRoot 'initialize.stdout.log') `
        -RedirectStandardError (Join-Path $runRoot 'initialize.stderr.log')
    if ($initialize.ExitCode -ne 0) { throw 'MySQL initialization failed; inspect initialize.stderr.log.' }

    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $serverProcess = Start-Process -FilePath $mysqld -WindowStyle Hidden -PassThru `
        -ArgumentList @('--no-defaults', ('--datadir="' + $dataRoot + '"'), '--bind-address=127.0.0.1',
            "--port=$port", '--mysqlx=0', '--skip-log-bin', '--innodb-buffer-pool-size=32M', '--console') `
        -RedirectStandardOutput (Join-Path $runRoot 'server.stdout.log') `
        -RedirectStandardError (Join-Path $runRoot 'server.stderr.log')
    $connectionArguments = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$port", '--user=root', '--skip-password', '--connect-timeout=1')
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($serverProcess.HasExited) { throw 'Temporary MySQL exited; inspect server.stderr.log.' }
        & $mysqladmin @connectionArguments ping *> (Join-Path $runRoot 'ping.log')
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Temporary MySQL did not become ready.' }

    # Confirm this port belongs to our own temporary instance before any test mutation.
    $observedData = (& $mysql @connectionArguments --batch --raw --skip-column-names '--execute=SELECT @@datadir').Trim()
    if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($observedData).TrimEnd('\', '/') -ne [IO.Path]::GetFullPath($dataRoot).TrimEnd('\', '/')) {
        throw 'MySQL data directory does not match this test run.'
    }
    $serverVerified = $true
    $version = (& $mysql @connectionArguments --batch --raw --skip-column-names '--execute=SELECT VERSION()').Trim()
    Write-Output "Running MySQL $version integration tests on loopback port $port."
    $env:SHOPPING_TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$port/"
    $env:SHOPPING_TEST_MYSQL_USER = 'root'
    $env:SHOPPING_TEST_MYSQL_PASSWORD = ''
    Push-Location $backendRoot
    try {
        & $maven @MavenArguments "-Dtest=$testSelection" '-Djunit.jupiter.execution.parallel.enabled=false' test *> (Join-Path $runRoot 'maven.log')
        $testExit = $LASTEXITCODE
    } finally { Pop-Location }
    Get-Content -LiteralPath (Join-Path $runRoot 'maven.log') -Tail 35
    if ($evaluation) {
        foreach ($suite in $testSelection.Split(',')) {
            Get-ChildItem -Path (Join-Path $backendRoot "target\surefire-reports\TEST-*.$suite.xml") -ErrorAction SilentlyContinue | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $evaluationRoot 'junit') }
        }
    }
    if ($testExit -ne 0) { throw "MySQL integration tests failed; inspect $runRoot\maven.log" }
    if (!$evaluation) {
    $reportPath = Join-Path $backendRoot "target\surefire-reports\TEST-com.example.service.ai.impl.$TestClass.xml"
    [xml]$testReport = Get-Content -LiteralPath $reportPath -Raw
    $expectedTests = switch ($TestClass) { 'CartOperationMysqlIT' { 6 }; 'AgentRunMysqlIT' { 14 }; 'AgentRecoveryMysqlIT' { 21 }; 'AgentRecoveryBrowserIT' { 1 }; 'OrderConfirmationMysqlIT' { 23 }; 'OrderConfirmationBrowserIT' { 1 } }
    if ([int]$testReport.testsuite.tests -ne $expectedTests -or [int]$testReport.testsuite.skipped -ne 0 `
        -or [int]$testReport.testsuite.failures -ne 0 -or [int]$testReport.testsuite.errors -ne 0) {
        throw "Expected all $expectedTests MySQL integration tests to execute and pass."
    }
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $runRoot 'junit.xml')
    }

    $remainingSchemas = (& $mysql @connectionArguments --batch --raw --skip-column-names "--execute=SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'shopping_agent_test_%'").Trim()
    if ($LASTEXITCODE -ne 0 -or $remainingSchemas -ne '0') { throw 'A temporary test schema was not cleaned up.' }
    Write-Output 'Verified: no temporary test schemas remain.'
} catch {
    $failureMessage = $_.Exception.Message
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $runRoot 'failure.log')
    throw
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
    if ($null -ne $serverProcess -and !$serverProcess.HasExited) {
        if ($serverVerified) { & $mysqladmin @connectionArguments shutdown *> (Join-Path $runRoot 'shutdown.log') }
        if (!$serverProcess.WaitForExit(10000)) {
            # Only the process launched by this script, never an existing MySQL service.
            $serverProcess.Kill()
            $serverProcess.WaitForExit()
        }
    }
    $expectedParent = [IO.Path]::GetFullPath((Join-Path $backendRoot 'target')) + [IO.Path]::DirectorySeparatorChar
    $resolvedData = [IO.Path]::GetFullPath($dataRoot)
    if (!$resolvedData.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) `
        -or (Split-Path $runRoot -Leaf) -notmatch '^mysql-validation-[a-f0-9]{32}$' `
        -or (Split-Path $resolvedData -Leaf) -ne 'data') { throw 'Unsafe cleanup path; refusing to delete.' }
    $cleanupError = $null
    try {
        if (Test-Path -LiteralPath $resolvedData) { Remove-Item -LiteralPath $resolvedData -Recurse -Force }
    } catch {
        $cleanupError = $_.Exception.Message
    }
    $cleanupSucceeded = !(Test-Path -LiteralPath $resolvedData)
    [pscustomobject]@{
        mysqlVersion = $version
        testClass = $TestClass
        port = $port
        verifiedIsolatedInstance = $serverVerified
        testExitCode = $testExit
        serverStopped = ($null -eq $serverProcess -or $serverProcess.HasExited)
        temporaryDataRemoved = $cleanupSucceeded
        failureMessage = $failureMessage
        cleanupError = $cleanupError
        completedAt = [DateTimeOffset]::Now.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'result.json')
    if ($evaluation) {
        Copy-Item -LiteralPath (Join-Path $runRoot 'result.json') -Destination (Join-Path $evaluationRoot 'mysql-result.json')
        if (Test-Path -LiteralPath (Join-Path $runRoot 'maven.log')) { Copy-Item -LiteralPath (Join-Path $runRoot 'maven.log') -Destination (Join-Path $evaluationRoot 'maven.log') }
    }
    Write-Output "Test evidence retained in $runRoot"
    if ($cleanupError) {
        if ($failureMessage) { Write-Warning "Temporary data cleanup failed: $cleanupError" }
        else { throw "Temporary data cleanup failed: $cleanupError" }
    }
}
