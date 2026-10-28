[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

function Get-ExistingOrPrompt {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Prompt
    )

    $current = [Environment]::GetEnvironmentVariable($Name, "Process")
    if (-not [string]::IsNullOrWhiteSpace($current)) {
        return $current
    }

    $value = Read-Host $Prompt
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$Name is required."
    }
    return $value
}

function Get-SecretAsPlainText {
    param([Parameter(Mandatory = $true)][string]$Prompt)

    $secureValue = Read-Host -Prompt $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Set-ProcessEnvironmentIfMissing {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $existing = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrWhiteSpace($existing)) {
        [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
    }
}

try {
    $dbUrl = Get-ExistingOrPrompt -Name "DB_URL" `
        -Prompt "DB_URL [jdbc:mysql://localhost:3306/cod_reconciliation?sslMode=REQUIRED&serverTimezone=UTC]"
    $dbUsername = Get-ExistingOrPrompt -Name "DB_USERNAME" -Prompt "MySQL username"
    $dbPassword = [Environment]::GetEnvironmentVariable("DB_PASSWORD", "Process")
    if ([string]::IsNullOrWhiteSpace($dbPassword)) {
        $dbPassword = Get-SecretAsPlainText -Prompt "MySQL password"
    }

    $adminUsername = Get-ExistingOrPrompt -Name "ADMIN_USERNAME" `
        -Prompt "Administrator username"
    $adminHash = [Environment]::GetEnvironmentVariable("ADMIN_PASSWORD_HASH", "Process")
    $appEnvironment = [Environment]::GetEnvironmentVariable("APP_ENV", "Process")

    if ([string]::IsNullOrWhiteSpace($adminHash)) {
        throw "ADMIN_PASSWORD_HASH is required. Generate a BCrypt hash locally and set it in this PowerShell process; the script never generates or stores administrator credentials."
    }
    if (-not [string]::IsNullOrWhiteSpace($appEnvironment) -and $appEnvironment -ne "local") {
        throw "APP_ENV is set to '$appEnvironment'. Use APP_ENV=local for this local startup script."
    }

    if ($dbUrl -notmatch "^jdbc:mysql://") {
        throw "DB_URL must be a MySQL JDBC URL."
    }
    if ($dbUrl -match "(?i)(useSSL\s*=\s*false|sslMode\s*=\s*DISABLED)") {
        throw "DB_URL disables TLS. Use sslMode=REQUIRED for local development."
    }
    if ($dbUrl -notmatch "(?i)(^|[?&])sslMode\s*=\s*(REQUIRED|VERIFY_IDENTITY)(&|$)") {
        throw "DB_URL must explicitly set sslMode=REQUIRED or sslMode=VERIFY_IDENTITY."
    }
    if ($adminHash -notmatch '^\{bcrypt\}\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$') {
        throw "ADMIN_PASSWORD_HASH must be a valid {bcrypt} BCrypt hash."
    }

    Set-ProcessEnvironmentIfMissing -Name "DB_URL" -Value $dbUrl
    Set-ProcessEnvironmentIfMissing -Name "DB_USERNAME" -Value $dbUsername
    Set-ProcessEnvironmentIfMissing -Name "DB_PASSWORD" -Value $dbPassword
    Set-ProcessEnvironmentIfMissing -Name "ADMIN_USERNAME" -Value $adminUsername
    Set-ProcessEnvironmentIfMissing -Name "APP_ENV" -Value "local"
    Set-ProcessEnvironmentIfMissing -Name "HSTS_ENABLED" -Value "false"

    Write-Host "Configuration validated. Starting the existing application."
    & "$PSScriptRoot\mvnw.cmd" spring-boot:run
    exit $LASTEXITCODE
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
