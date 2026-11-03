[CmdletBinding()]
param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin'
)

$ErrorActionPreference = 'Stop'
$qaRepoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$qaTargetRoot = Join-Path $qaRepoRoot 'target'
$qaRunRoot = Join-Path $qaTargetRoot ('kan27-postgres-' + [Guid]::NewGuid().ToString('N'))
$qaDataRoot = Join-Path $qaRunRoot 'data'
if (-not $qaRunRoot.StartsWith(
        $qaTargetRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Diretório temporário fora de target.'
}

foreach ($qaBinary in @('initdb.exe', 'pg_ctl.exe', 'createdb.exe', 'psql.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin $qaBinary))) {
        throw "Binário PostgreSQL ausente: $qaBinary. Informe -PostgresBin."
    }
}
$qaMaven = Join-Path $qaRepoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $qaMaven)) {
    throw 'Maven Wrapper não encontrado.'
}
New-Item -ItemType Directory -Path $qaRunRoot | Out-Null
$qaConfigRoot = Join-Path $qaRunRoot 'config'
New-Item -ItemType Directory -Path $qaConfigRoot | Out-Null
$qaBaseConfig = [IO.File]::ReadAllText((Join-Path $qaRepoRoot 'src/main/resources/application.yml'))
$qaLocalImportPattern = '(?m)^  config:\r?\n    import: optional:file:\./application-local\.properties\r?\n'
if ([regex]::Matches($qaBaseConfig, $qaLocalImportPattern).Count -ne 1) {
    throw 'Import local mudou; revisar a configuração de QA antes de executar.'
}
$qaBaseConfig = [regex]::Replace($qaBaseConfig, $qaLocalImportPattern, '')
$qaTestConfig = [IO.File]::ReadAllText((Join-Path $qaRepoRoot 'src/test/resources/application-test.yml'))
if ($qaBaseConfig -match '(?m)^\s*import:' -or $qaTestConfig -match '(?m)^\s*import:') {
    throw 'Import adicional não previsto; revisar isolamento da configuração de QA.'
}
[IO.File]::WriteAllText((Join-Path $qaConfigRoot 'application.yml'), $qaBaseConfig)
[IO.File]::WriteAllText((Join-Path $qaConfigRoot 'application-test.yml'), $qaTestConfig)
$qaConfigUri = [uri]::new($qaConfigRoot + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
$qaStarted = $false
$qaTestExit = $null
$qaFailure = $null
$qaPostgresVersion = $null
$qaStartedAt = $null
$qaPort = $null

Push-Location -LiteralPath $qaRepoRoot
try {
    & (Join-Path $PostgresBin 'initdb.exe') -D $qaDataRoot -U acompanhaai_qa --auth=trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao criar cluster temporário.'
    }

    # Porta efêmera local. Se ocupada antes do start, pg_ctl falha sem usar outra instância.
    $qaPortProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $qaPortProbe.Start()
        $qaPort = $qaPortProbe.LocalEndpoint.Port
    } finally {
        $qaPortProbe.Stop()
    }

    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $qaDataRoot -l (Join-Path $qaRunRoot 'postgres.log') `
        -o "-h 127.0.0.1 -p $qaPort" -w -t 60 start
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao iniciar PostgreSQL temporário.'
    }
    $qaStarted = $true
    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $qaPort -U acompanhaai_qa acompanhaai_kan27
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao criar banco de QA.'
    }
    $qaPostgresVersion = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $qaPort `
        -U acompanhaai_qa -d acompanhaai_kan27 -Atc 'SELECT version()'
    if ($LASTEXITCODE -ne 0) {
        throw 'Não foi possível conferir a instância temporária.'
    }

    $qaStartedAt = [DateTimeOffset]::UtcNow
    & $qaMaven --batch-mode --no-transfer-progress verify `
        "-Dspring.config.location=$qaConfigUri" `
        '-Dspring.config.additional-location=' `
        '-Dspring.config.name=application' `
        '-Dspring.application.json={}' `
        "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$qaPort/acompanhaai_kan27" `
        '-Dspring.datasource.username=acompanhaai_qa' `
        '-Dspring.datasource.password=' 2>&1 | Tee-Object -FilePath (Join-Path $qaRunRoot 'maven.log')
    $qaTestExit = $LASTEXITCODE
} catch {
    $qaFailure = $_
} finally {
    try {
        if ($qaStarted) {
            # Encerra somente o postmaster do diretório exclusivo desta execução.
            & (Join-Path $PostgresBin 'pg_ctl.exe') -D $qaDataRoot -m fast -w -t 60 stop
            if ($LASTEXITCODE -ne 0) {
                throw 'Não foi possível encerrar a instância temporária; confira postgres.log.'
            }
        }
    } catch {
        if (-not $qaFailure) {
            $qaFailure = $_
        }
    } finally {
        Pop-Location
    }
}

$qaReportsRoot = Join-Path $qaRunRoot 'reports'
New-Item -ItemType Directory -Path $qaReportsRoot | Out-Null
$qaSuiteSummary = @()
if ($qaStartedAt -and (Test-Path -LiteralPath (Join-Path $qaRepoRoot 'target/surefire-reports'))) {
    $qaFreshReports = Get-ChildItem -LiteralPath (Join-Path $qaRepoRoot 'target/surefire-reports') -File |
        Where-Object { $_.Extension -in '.xml', '.txt' -and $_.LastWriteTimeUtc -ge $qaStartedAt.UtcDateTime }
    foreach ($qaReport in $qaFreshReports) {
        Copy-Item -LiteralPath $qaReport.FullName -Destination $qaReportsRoot
        if ($qaReport.Extension -eq '.xml') {
            [xml]$qaReportXml = Get-Content -Raw -LiteralPath $qaReport.FullName
            $qaSuiteSummary += [pscustomobject]@{
                name = $qaReportXml.testsuite.name
                tests = [int]$qaReportXml.testsuite.tests
                failures = [int]$qaReportXml.testsuite.failures
                errors = [int]$qaReportXml.testsuite.errors
                skipped = [int]$qaReportXml.testsuite.skipped
            }
        }
    }
}
$qaSummary = [ordered]@{
    postgres = [string]$qaPostgresVersion
    database = 'acompanhaai_kan27'
    configuration = 'versioned-copy-without-local-import'
    host = '127.0.0.1'
    port = $qaPort
    startedAtUtc = $qaStartedAt
    finishedAtUtc = [DateTimeOffset]::UtcNow
    mavenExitCode = $qaTestExit
    tests = ($qaSuiteSummary | Measure-Object tests -Sum).Sum
    failures = ($qaSuiteSummary | Measure-Object failures -Sum).Sum
    errors = ($qaSuiteSummary | Measure-Object errors -Sum).Sum
    skipped = ($qaSuiteSummary | Measure-Object skipped -Sum).Sum
    suites = $qaSuiteSummary
}
$qaSummary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $qaRunRoot 'summary.json') -Encoding utf8
Write-Host "Evidências locais: $qaRunRoot"
Write-Host ('Resultado Maven: ' + $qaTestExit)
if ($qaFailure) {
    throw $qaFailure
}
if ($qaTestExit -ne 0 -or $qaSuiteSummary.Count -eq 0) {
    throw 'Regressão não aprovada; consulte os relatórios locais.'
}
