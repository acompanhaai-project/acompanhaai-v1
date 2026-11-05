#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin'
)

$ErrorActionPreference = 'Stop'
$qaRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$qaRunId = [Guid]::NewGuid().ToString('N')
$qaTarget = Join-Path $qaRoot 'target'
$qaRunRoot = Join-Path $qaTarget ('kan33-postgres-' + $qaRunId)
if (-not $qaRunRoot.StartsWith($qaTarget + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Diretório de QA fora de target.'
}
foreach ($qaBinary in @('initdb.exe', 'pg_ctl.exe', 'createdb.exe', 'psql.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin $qaBinary))) {
        throw "Binário PostgreSQL ausente: $qaBinary."
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $qaRoot 'postman/fixtures/v2/node_modules/newman'))) {
    throw 'Execute npm ci dentro de postman/fixtures/v2 antes deste roteiro.'
}
New-Item -ItemType Directory -Path $qaRunRoot | Out-Null
$qaConfig = Join-Path $qaRunRoot 'config'
New-Item -ItemType Directory -Path $qaConfig | Out-Null
$qaBase = [IO.File]::ReadAllText((Join-Path $qaRoot 'src/main/resources/application.yml'))
$qaImport = '(?m)^  config:\r?\n    import: optional:file:\./application-local\.properties\r?\n'
if ([regex]::Matches($qaBase, $qaImport).Count -ne 1) {
    throw 'Import local mudou; revisar isolamento de configuração.'
}
$qaBase = [regex]::Replace($qaBase, $qaImport, '')
if ($qaBase -match '(?m)^\s*import:') {
    throw 'Import adicional não previsto na configuração de QA.'
}
[IO.File]::WriteAllText((Join-Path $qaConfig 'application.yml'), $qaBase)
$qaConfigUri = [uri]::new($qaConfig + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
$qaData = Join-Path $qaRunRoot 'data'
$qaStarted = $false
$qaApp = $null
$qaFailure = $null
$qaResult = $null
$qaDatabaseCheck = $false
$qaSentinelCheck = $false
$qaPostgresVersion = $null
$qaStartedAt = [DateTimeOffset]::UtcNow

function Get-QaPort {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $probe.Start()
        return $probe.LocalEndpoint.Port
    } finally {
        $probe.Stop()
    }
}

Push-Location -LiteralPath $qaRoot
try {
    & node postman/fixtures/v2/build.cjs --check
    if ($LASTEXITCODE -ne 0) {
        throw 'Coleção v2 não sincronizada com as fontes.'
    }
    & node --test postman/fixtures/v2/guards.test.cjs
    if ($LASTEXITCODE -ne 0) {
        throw 'Guardas de execução e limpeza não aprovadas.'
    }
    & ./mvnw.cmd --batch-mode --no-transfer-progress '-DskipTests' package 2>&1 |
        Tee-Object -FilePath (Join-Path $qaRunRoot 'build.log')
    if ($LASTEXITCODE -ne 0) {
        throw 'Build não aprovado; API de QA não será iniciada.'
    }
    & (Join-Path $PostgresBin 'initdb.exe') -D $qaData -U acompanhaai_qa_admin --auth=trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao criar cluster descartável.'
    }
    $qaDbPort = Get-QaPort
    $qaApiPort = Get-QaPort
    while ($qaApiPort -eq $qaDbPort) {
        $qaApiPort = Get-QaPort
    }
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $qaData -l (Join-Path $qaRunRoot 'postgres.log') `
        -o "-h 127.0.0.1 -p $qaDbPort" -w -t 60 start
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao iniciar o cluster exclusivo.'
    }
    $qaStarted = $true
    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $qaDbPort -U acompanhaai_qa_admin acompanhaai
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao criar banco exclusivo.'
    }
    $qaPsqlArgs = @('-X', '-w', '-h', '127.0.0.1', '-p', $qaDbPort, '-U', 'acompanhaai_qa_admin',
        '-d', 'acompanhaai', '-v', 'ON_ERROR_STOP=1')
    $qaPostgresVersion = & (Join-Path $PostgresBin 'psql.exe') @qaPsqlArgs -Atq -c 'SELECT version()'
    if ($LASTEXITCODE -ne 0) {
        throw 'Não foi possível verificar a instância exclusiva.'
    }
    foreach ($qaSql in @('create-database-roles.sql', 'adopt-database-objects.sql')) {
        & (Join-Path $PostgresBin 'psql.exe') @qaPsqlArgs -f (Join-Path $qaRoot "scripts/sql/$qaSql")
        if ($LASTEXITCODE -ne 0) {
            throw "Falha no provisionamento de QA: $qaSql."
        }
    }
    $qaJar = Join-Path $qaRoot 'target/acompanhaai-0.0.1-SNAPSHOT.jar'
    $qaArguments = @('-Xms32m', '-Xmx384m', '-XX:MaxMetaspaceSize=192m', '-XX:ReservedCodeCacheSize=96m',
        '-jar', "`"$qaJar`"", "`"--spring.config.location=$qaConfigUri`"",
        '--spring.config.additional-location=', '--spring.config.import=', '--spring.config.name=application',
        '--spring.application.json={}',
        '--spring.profiles.active=', '--server.address=127.0.0.1', "--server.port=$qaApiPort",
        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$qaDbPort/acompanhaai",
        '--spring.datasource.username=acompanhaai_app', '--spring.datasource.password=',
        '--spring.flyway.user=acompanhaai_migrator', '--spring.flyway.password=',
        '--spring.flyway.locations=classpath:db/migration,classpath:db/callback',
        '--server.servlet.session.cookie.secure=false')
    $qaApp = Start-Process -FilePath (Get-Command java -ErrorAction Stop).Source -ArgumentList $qaArguments `
        -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $qaRunRoot 'api.log') `
        -RedirectStandardError (Join-Path $qaRunRoot 'api-error.log')
    $qaBaseUrl = "http://127.0.0.1:$qaApiPort"
    $qaReady = $false
    for ($qaAttempt = 0; $qaAttempt -lt 90; $qaAttempt++) {
        if ($qaApp.HasExited) {
            throw 'API de QA encerrou antes de ficar disponível; consulte api.log.'
        }
        $qaLogFile = Join-Path $qaRunRoot 'api.log'
        $qaLogReady = (Test-Path -LiteralPath $qaLogFile) -and
            ((Get-Content -Raw -LiteralPath $qaLogFile) -match "Tomcat started on port $qaApiPort ")
        if (-not $qaLogReady) {
            Start-Sleep -Milliseconds 500
            continue
        }
        try {
            $qaResponse = Invoke-WebRequest -Uri "$qaBaseUrl/api/auth/csrf" -TimeoutSec 2 -MaximumRedirection 0
            if ($qaResponse.StatusCode -eq 200) {
                $qaReady = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $qaReady) {
        throw 'API de QA não ficou disponível.'
    }

    $qaFaultTemplate = [IO.File]::ReadAllText((Join-Path $qaRoot 'postman/fixtures/v2/history-fault.sql'))
    $qaFaultFile = Join-Path $qaRunRoot 'history-fault.sql'
    [IO.File]::WriteAllText($qaFaultFile, $qaFaultTemplate.Replace('__RUN_ID__', $qaRunId))
    & (Join-Path $PostgresBin 'psql.exe') @qaPsqlArgs -f $qaFaultFile
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao instalar o trigger exclusivo da fixture.'
    }
    $qaSentinelEmail = "qa.kan33.sentinel+$qaRunId@example.test"
    $qaSentinelFile = Join-Path $qaRunRoot 'sentinel.sql'
    $qaSentinelSql = @"
INSERT INTO tb_user(name, email, password_hash, auth_version, created_at)
VALUES ('Sentinela QA KAN33', '$qaSentinelEmail', 'hash-ficticio-sem-login', 0, CURRENT_TIMESTAMP);
INSERT INTO tb_job(user_id, title)
SELECT id, 'Vaga sentinela preservada' FROM tb_user WHERE email = '$qaSentinelEmail';
INSERT INTO tb_application(user_id, job_id, status, notes, created_at, updated_at, version)
SELECT user_id, id, 'SAVED', 'Sentinela preservada', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM tb_job WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail');
INSERT INTO tb_application_status_history(application_id, to_status, changed_at)
SELECT id, 'SAVED', CURRENT_TIMESTAMP FROM tb_application
WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail');
INSERT INTO tb_job_skill(job_id, name, normalized_name, type)
SELECT id, 'Java sentinela', 'java sentinela', 'HARD' FROM tb_job
WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail');
INSERT INTO tb_import_task(user_id, job_id, input_type, input_text, status, attempt_count, created_at, updated_at)
SELECT user_id, id, 'TEXT', 'Sentinela de importacao', 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM tb_job WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail');
"@
    [IO.File]::WriteAllText($qaSentinelFile, $qaSentinelSql)
    & (Join-Path $PostgresBin 'psql.exe') @qaPsqlArgs -f $qaSentinelFile
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao criar sentinela não pertencente à coleção.'
    }

    & node postman/fixtures/v2/run.cjs --base-url $qaBaseUrl --run-id $qaRunId --with-db-fault
    $qaNodeExit = $LASTEXITCODE
    $qaSummaryFile = Join-Path $qaRoot "postman/reports/v2/$qaRunId/summary.json"
    if (-not (Test-Path -LiteralPath $qaSummaryFile)) {
        throw 'Executor v2 não produziu resumo.'
    }
    $qaResult = Get-Content -Raw -LiteralPath $qaSummaryFile | ConvertFrom-Json
    $qaA = [long]$qaResult.accounts.a.id
    $qaB = [long]$qaResult.accounts.b.id
    if ($qaA -lt 1 -or $qaB -lt 1 -or $qaA -eq $qaB) {
        throw 'IDs das contas criadas não puderam ser confirmados; não ampliar a limpeza.'
    }
    $qaChecks = @"
SELECT count(*) FROM tb_user WHERE id IN ($qaA, $qaB);
SELECT count(*) FROM tb_job WHERE user_id IN ($qaA, $qaB);
SELECT count(*) FROM tb_application WHERE user_id IN ($qaA, $qaB);
SELECT count(*) FROM tb_import_task WHERE user_id IN ($qaA, $qaB);
SELECT count(*) FROM tb_user_photo WHERE user_id IN ($qaA, $qaB);
SELECT count(*) FROM tb_password_reset_token WHERE user_id IN ($qaA, $qaB);
SELECT count(*) FROM tb_user WHERE email = '$qaSentinelEmail' AND name = 'Sentinela QA KAN33';
SELECT count(*) FROM tb_job WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail')
AND title = 'Vaga sentinela preservada';
SELECT count(*) FROM tb_application WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail')
AND status = 'SAVED' AND notes = 'Sentinela preservada';
SELECT count(*) FROM tb_application_status_history h JOIN tb_application a ON a.id = h.application_id
JOIN tb_user u ON u.id = a.user_id WHERE u.email = '$qaSentinelEmail';
SELECT count(*) FROM tb_job_skill s JOIN tb_job j ON j.id = s.job_id
JOIN tb_user u ON u.id = j.user_id WHERE u.email = '$qaSentinelEmail';
SELECT count(*) FROM tb_import_task WHERE user_id = (SELECT id FROM tb_user WHERE email = '$qaSentinelEmail');
"@
    $qaCheckFile = Join-Path $qaRunRoot 'verify-cleanup.sql'
    [IO.File]::WriteAllText($qaCheckFile, $qaChecks)
    $qaRows = @(& (Join-Path $PostgresBin 'psql.exe') @qaPsqlArgs -Atq -f $qaCheckFile)
    if ($LASTEXITCODE -ne 0 -or $qaRows.Count -ne 12) {
        throw 'Verificação SQL da limpeza não foi concluída.'
    }
    $qaDatabaseCheck = ($qaRows[0..5] -join ',') -eq '0,0,0,0,0,0'
    $qaSentinelCheck = ($qaRows[6..11] -join ',') -eq '1,1,1,1,1,1'
    if ($qaNodeExit -ne 0 -or -not $qaDatabaseCheck -or -not $qaSentinelCheck -or
        -not $qaResult.databaseFaultEnabled -or -not $qaResult.cleanupComplete) {
        throw 'Regressão v2 não aprovada; consultar resumo e preservar evidências.'
    }
} catch {
    $qaFailure = $_
} finally {
    try {
        if ($qaApp -and -not $qaApp.HasExited) {
            Stop-Process -Id $qaApp.Id -Force
            $qaApp.WaitForExit(15000) | Out-Null
        }
        if ($qaStarted) {
            & (Join-Path $PostgresBin 'pg_ctl.exe') -D $qaData -m fast -w -t 60 stop
            if ($LASTEXITCODE -ne 0) {
                throw 'Cluster de QA não foi encerrado; consulte postgres.log.'
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
$qaEvidence = [ordered]@{
    task = 'KAN-33'
    runId = $qaRunId
    postgres = [string]$qaPostgresVersion
    startedAtUtc = $qaStartedAt.ToUniversalTime().ToString('O')
    finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    fixture = $qaResult
    fixtureRowsRemoved = $qaDatabaseCheck
    unrelatedSentinelPreserved = $qaSentinelCheck
    result = $(if ($qaFailure) { 'FAIL' } else { 'PASS' })
}
$qaEvidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $qaRunRoot 'summary.json') -Encoding utf8
Write-Host "Evidências KAN-33: $qaRunRoot"
if ($qaFailure) {
    throw $qaFailure
}
