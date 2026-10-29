# Execute em um PowerShell como administrador, em uma janela interativa.
# A senha e solicitada pelo psql e nao passa pelo script ou historico do terminal.
[CmdletBinding()]
param(
    [string]$PostgresRoot = 'C:\Program Files\PostgreSQL\18',
    [ValidateRange(1, 65535)][int]$Port = 5433
)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Abra o PowerShell como administrador e execute este script novamente.'
}

$psqlPath = Join-Path $PostgresRoot 'bin\psql.exe'
$hbaPath = Join-Path $PostgresRoot 'data\pg_hba.conf'
if (-not (Test-Path -LiteralPath $psqlPath) -or -not (Test-Path -LiteralPath $hbaPath)) {
    throw 'Nao foi possivel localizar psql.exe e pg_hba.conf nesta instalacao.'
}

$original = [IO.File]::ReadAllBytes($hbaPath)
$originalText = [Text.Encoding]::UTF8.GetString($original)
if ($originalText.Contains('ACOMPANHAAI_PASSWORD_RECOVERY')) {
    throw 'Existe uma recuperacao incompleta. Restaure o backup pg_hba.conf.codex-recovery-*.bak antes de continuar.'
}
$backupPath = $hbaPath + '.codex-recovery-' + [Guid]::NewGuid().ToString('N') + '.bak'
[IO.File]::WriteAllBytes($backupPath, $original)
Write-Host "Backup da autenticacao: $backupPath"

# No Windows, as novas conexoes leem o pg_hba.conf imediatamente.
# A regra temporaria permite apenas o usuario postgres, banco postgres,
# vindo de 127.0.0.1. As regras para outros usuarios e hosts ficam intactas.
$temporaryRule = "# ACOMPANHAAI_PASSWORD_RECOVERY`r`nhost postgres postgres 127.0.0.1/32 trust`r`n"
$prefix = [Text.Encoding]::UTF8.GetBytes($temporaryRule)
# Remove somente o BOM da copia temporaria para que nao fique no meio do arquivo.
$body = $original
if ($original.Length -ge 3 -and $original[0] -eq 239 -and $original[1] -eq 187 -and $original[2] -eq 191) {
    $body = $original[3..($original.Length - 1)]
}
$temporary = [byte[]]::new($prefix.Length + $body.Length)
[Array]::Copy($prefix, 0, $temporary, 0, $prefix.Length)
[Array]::Copy($body, 0, $temporary, $prefix.Length, $body.Length)

try {
    [IO.File]::WriteAllBytes($hbaPath, $temporary)
    Write-Host 'Digite a NOVA senha do usuario postgres e repita quando solicitado.'
    Write-Host 'A senha nao aparece na tela enquanto voce digita.'
    & $psqlPath -X -h 127.0.0.1 -p $Port -U postgres -d postgres -w -v ON_ERROR_STOP=1 -c '\password postgres'
    if ($LASTEXITCODE -ne 0) {
        throw 'O psql nao concluiu a troca de senha. A autenticacao original sera restaurada.'
    }
} finally {
    [IO.File]::WriteAllBytes($hbaPath, $original)
    $restoredHash = (Get-FileHash -LiteralPath $hbaPath -Algorithm SHA256).Hash
    $backupHash = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash
    if ($restoredHash -ne $backupHash) {
        throw "Falha ao verificar a restauracao. Restaure imediatamente o arquivo $backupPath para $hbaPath."
    }
    Write-Host 'Configuracao original de autenticacao restaurada e conferida.'
}

Write-Host 'Agora digite a nova senha novamente para testar a conexao com autenticacao normal.'
& $psqlPath -X -h 127.0.0.1 -p $Port -U postgres -d postgres -W -v ON_ERROR_STOP=1 -c 'SELECT current_user, current_database();'
if ($LASTEXITCODE -ne 0) {
    throw 'A verificacao com a nova senha falhou. A autenticacao original permanece restaurada.'
}
Write-Host 'Conexao confirmada. No pgAdmin use localhost, porta 5433 e usuario postgres.'
