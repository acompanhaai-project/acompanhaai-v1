#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$smokeUri = [uri]$BaseUrl
if ($smokeUri.Scheme -ne 'http' -or $smokeUri.Host -notin @('localhost', '127.0.0.1', '[::1]', '::1') -or
    $smokeUri.UserInfo -or $smokeUri.Query -or $smokeUri.Fragment -or $smokeUri.AbsolutePath -ne '/') {
    throw 'Smoke exclusivo para a origem HTTP local, sem credenciais, query ou caminho.'
}
$BaseUrl = $smokeUri.GetLeftPart([UriPartial]::Authority)
$smokeRepo = Split-Path -Parent $PSScriptRoot
$smokeRunId = [Guid]::NewGuid().ToString('N')
$smokeRoot = Join-Path $smokeRepo "target/smoke-kan29-$smokeRunId"
New-Item -ItemType Directory -Path $smokeRoot | Out-Null
$smokeEmail = "qa.kan29.$smokeRunId@example.test"
$smokePassword = 'Qa!' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$script:smokeChecks = 0
$script:smokeRequests = [Collections.Generic.List[object]]::new()
$script:smokeFailure = $null
$script:smokeErrorType = $null
$script:smokeErrorLine = $null
$script:smokeStage = 'initializing'
$script:smokeCsrf = $null
$script:smokeCsrfHeader = $null
$smokeUserId = $null
$smokeAttemptedRegistration = $false
$smokeAccountDeleted = $false
$smokePassed = $false
$smokeFailedStage = $null
$smokeCleanup = 'not-needed'

function New-SmokeClient {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.CookieContainer = [Net.CookieContainer]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    return $client
}

function Assert-Smoke([bool]$Condition, [string]$Message) {
    $script:smokeChecks++
    if (-not $Condition) {
        if (-not $script:smokeFailure) {
            $script:smokeFailure = $Message
        }
        throw $Message
    }
}

function Invoke-Smoke([string]$Method, [string]$Path, [int]$Expected, $Body = $null) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $BaseUrl + $Path)
    try {
        $script:smokeStage = 'serialize-request'
        if ($Body -ne $null) {
            $request.Content = [Net.Http.StringContent]::new(
                ($Body | ConvertTo-Json -Depth 8 -Compress),
                [Text.Encoding]::UTF8,
                'application/json'
            )
        }
        if ($Method -ne 'GET' -and $script:smokeCsrf) {
            $script:smokeStage = 'csrf-header'
            $request.Headers.Add($script:smokeCsrfHeader, $script:smokeCsrf)
        }
        $script:smokeStage = 'send-request'
        $response = $script:smokeClient.SendAsync($request).GetAwaiter().GetResult()
        try {
            $status = [int]$response.StatusCode
            $script:smokeRequests.Add([pscustomobject]@{
                method = $Method
                path = $Path
                expected = $Expected
                actual = $status
            })
            Assert-Smoke ($status -eq $Expected) "Status inesperado em $Method $Path; esperado $Expected, obtido $status."
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ([string]::IsNullOrWhiteSpace($text)) {
                return $null
            }
            return ConvertFrom-Json -InputObject $text -NoEnumerate
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

function Update-SmokeCsrf {
    $csrf = Invoke-Smoke 'GET' '/api/auth/csrf' 200
    Assert-Smoke ($csrf.headerName -eq 'X-CSRF-TOKEN' -and -not [string]::IsNullOrWhiteSpace($csrf.token)) 'Contrato CSRF inválido.'
    $script:smokeCsrf = $csrf.token
    $script:smokeCsrfHeader = $csrf.headerName
}

function Assert-SmokeOwner($User) {
    Assert-Smoke ($User.email -ceq $smokeEmail) 'Identidade diferente da conta fictícia; escritas interrompidas.'
    if ($null -ne $smokeUserId) {
        Assert-Smoke ($User.id -eq $smokeUserId) 'Identificador diferente da conta fictícia; escritas interrompidas.'
    }
    Assert-Smoke (-not ($User.PSObject.Properties.Name -contains 'password') -and
        -not ($User.PSObject.Properties.Name -contains 'passwordHash')) 'Credencial presente no contrato de usuário.'
}

$script:smokeClient = New-SmokeClient
try {
    Update-SmokeCsrf
    $smokeAttemptedRegistration = $true
    $registered = Invoke-Smoke 'POST' '/api/users' 201 @{
        name = 'QA KAN-29'
        email = $smokeEmail
        password = $smokePassword
    }
    $smokeUserId = $registered.id
    Assert-SmokeOwner $registered
    $logged = Invoke-Smoke 'POST' '/api/auth/login' 200 @{
        email = $smokeEmail
        password = $smokePassword
    }
    Assert-SmokeOwner $logged
    Update-SmokeCsrf
    $me = Invoke-Smoke 'GET' '/api/users/me' 200
    Assert-SmokeOwner $me
    $updated = Invoke-Smoke 'PUT' '/api/users/me' 200 @{
        name = 'QA KAN-29 atualizado'
        email = $smokeEmail
    }
    Assert-SmokeOwner $updated
    Assert-Smoke ($updated.name -eq 'QA KAN-29 atualizado') 'Perfil não atualizado.'

    $job = @{
        title = 'QA candidatura KAN-29'
        companyName = 'Empresa fictícia'
        sourceUrl = "https://example.test/vagas/$smokeRunId"
        description = 'Registro sintético exclusivo do smoke.'
    }
    $application = Invoke-Smoke 'POST' '/api/applications' 201 @{
        job = $job
        status = 'APPLIED'
        appliedOn = $null
        notes = 'Smoke KAN-29'
    }
    Assert-Smoke ($application.id -gt 0 -and $application.status -eq 'APPLIED') 'Cadastro da candidatura inválido.'
    $path = '/api/applications/' + $application.id
    $read = Invoke-Smoke 'GET' $path 200
    Assert-Smoke ($read.id -eq $application.id -and $read.job.title -eq $job.title) 'Consulta da candidatura divergente.'
    $job.title = 'QA candidatura KAN-29 editada'
    $edited = Invoke-Smoke 'PUT' $path 200 @{
        job = $job
        status = 'INTERVIEW'
        appliedOn = $null
        notes = 'Atualização sintética'
        version = $read.version
    }
    Assert-Smoke ($edited.status -eq 'INTERVIEW' -and $edited.job.title -eq $job.title -and $edited.version -gt $read.version) 'Edição ou versão da candidatura divergente.'
    $history = Invoke-Smoke 'GET' ($path + '/history') 200
    Assert-Smoke ($history.Count -eq 2 -and $history[-1].fromStatus -eq 'APPLIED' -and $history[-1].toStatus -eq 'INTERVIEW') 'Histórico da candidatura divergente.'
    $listed = Invoke-Smoke 'GET' '/api/applications?status=INTERVIEW&page=0&size=20' 200
    Assert-Smoke ($listed.totalElements -eq 1 -and $listed.content[0].id -eq $application.id) 'Filtro ou isolamento da listagem divergente.'
    $null = Invoke-Smoke 'DELETE' $path 204
    $null = Invoke-Smoke 'GET' $path 404
    $null = Invoke-Smoke 'DELETE' '/api/users/me' 204
    $smokeAccountDeleted = $true
    $null = Invoke-Smoke 'GET' '/api/users/me' 401
    $smokePassed = $true
    $smokeCleanup = 'confirmed'
} catch {
    $script:smokeErrorType = $_.Exception.GetType().FullName
    $script:smokeErrorLine = $_.InvocationInfo.ScriptLineNumber
    $smokeFailedStage = $script:smokeStage
    if (-not $script:smokeFailure) {
        $script:smokeFailure = 'Falha de transporte, leitura ou processamento; resposta privada não registrada.'
    }
} finally {
    if ($smokeAttemptedRegistration -and -not $smokeAccountDeleted) {
        # Nova sessão; só remove a conta cujo e-mail/ID correspondem à massa desta rodada.
        $script:smokeClient.Dispose()
        $script:smokeClient = New-SmokeClient
        $script:smokeCsrf = $null
        try {
            Update-SmokeCsrf
            $cleanupLogin = Invoke-Smoke 'POST' '/api/auth/login' 200 @{
                email = $smokeEmail
                password = $smokePassword
            }
            Assert-SmokeOwner $cleanupLogin
            Update-SmokeCsrf
            $cleanupMe = Invoke-Smoke 'GET' '/api/users/me' 200
            Assert-SmokeOwner $cleanupMe
            $null = Invoke-Smoke 'DELETE' '/api/users/me' 204
            $smokeAccountDeleted = $true
            $null = Invoke-Smoke 'GET' '/api/users/me' 401
            $smokeCleanup = 'confirmed-after-failure'
        } catch {
            $smokeCleanup = 'unconfirmed; revisar somente a conta ficticia desta rodada'
        }
    }
    $script:smokeClient.Dispose()
    $summary = [ordered]@{
        target = $BaseUrl
        runId = $smokeRunId
        testEmail = $smokeEmail
        finishedAt = [DateTimeOffset]::Now
        passed = $smokePassed
        assertions = $script:smokeChecks
        requests = $script:smokeRequests.Count
        cleanup = $smokeCleanup
        failure = $script:smokeFailure
        errorType = $script:smokeErrorType
        errorLine = $script:smokeErrorLine
        failedStage = $smokeFailedStage
        results = $script:smokeRequests
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $smokeRoot 'summary.json') -Encoding utf8
    Write-Output ('Relatório sanitizado: ' + (Join-Path $smokeRoot 'summary.json'))
    Write-Output "Resultado: $smokePassed; requisições: $($script:smokeRequests.Count); asserções: $script:smokeChecks; limpeza: $smokeCleanup"
}
if (-not $smokePassed) {
    throw 'Smoke reprovado; consulte o relatório sanitizado.'
}
