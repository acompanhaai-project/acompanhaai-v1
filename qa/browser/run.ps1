[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$containerName = 'acompanhaai-browser-qa-' + [guid]::NewGuid().ToString('N')
$databasePassword = [guid]::NewGuid().ToString('N')
$previousPassword = $env:ACOMPANHAAI_QA_DB_PASSWORD
$containerStarted = $false

try {
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) {
        throw 'Inicie o Docker Desktop antes de executar o QA.'
    }

    & docker run --detach --rm --name $containerName `
        --publish '127.0.0.1::5432' `
        --env 'POSTGRES_DB=acompanhaai_browser_qa' `
        --env 'POSTGRES_USER=acompanhaai_browser_qa' `
        --env "POSTGRES_PASSWORD=$databasePassword" `
        postgres:18
    if ($LASTEXITCODE -ne 0) {
        throw 'Não foi possível criar o PostgreSQL descartável.'
    }
    $containerStarted = $true

    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $containerName pg_isready -U acompanhaai_browser_qa -d acompanhaai_browser_qa *> $null
        if ($LASTEXITCODE -eq 0) {
            $databaseReady = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $databaseReady) {
        throw 'PostgreSQL não ficou pronto em 30 segundos.'
    }

    $binding = (& docker port $containerName '5432/tcp').Trim()
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:(\d+)$') {
        throw 'A porta do banco não corresponde ao loopback esperado.'
    }
    $databasePort = $Matches[1]
    $env:ACOMPANHAAI_QA_DB_PASSWORD = $databasePassword
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & './mvnw.cmd' --batch-mode --no-transfer-progress -Pbrowser-qa test `
            "-Dqa.database.url=jdbc:postgresql://127.0.0.1:$databasePort/acompanhaai_browser_qa" `
            '-Dqa.database.user=acompanhaai_browser_qa'
        if ($LASTEXITCODE -ne 0) {
            throw 'A suíte de navegador falhou. Consulte backend/target/surefire-reports.'
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:ACOMPANHAAI_QA_DB_PASSWORD = $previousPassword
    if ($containerStarted) {
        & docker stop --time 5 $containerName *> $null
    }
}
