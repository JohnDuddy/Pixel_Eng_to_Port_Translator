param(
    [string]$ProjectId = "",
    [string]$Region = "southamerica-east1",
    [string]$ServiceName = "duddy-translator-backend",
    [switch]$SkipEnableServices
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendRoot = Join-Path $ProjectRoot "backend"
$EnvPath = Join-Path $BackendRoot ".env"
$LogsRoot = Join-Path $ProjectRoot "logs"
$CloudRunUrlPath = Join-Path $LogsRoot "cloudrun-backend-url.txt"

function Get-GcloudCommand {
    $command = Get-Command gcloud -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"),
        "C:\Program Files\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }

    return ""
}

function Get-EnvFileValue {
    param(
        [string]$Name,
        [string]$Default = ""
    )
    if (-not (Test-Path -LiteralPath $EnvPath)) {
        return $Default
    }
    $line = Get-Content -LiteralPath $EnvPath |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if (-not $line) {
        return $Default
    }
    $value = (($line -split "=", 2)[1]).Trim().Trim('"').Trim("'")
    if ($value) { $value } else { $Default }
}

function New-SecretVersion {
    param(
        [string]$SecretName,
        [string]$SecretValue
    )
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -LiteralPath $tmp -Value $SecretValue -NoNewline
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $Gcloud secrets describe $SecretName --project $ProjectId --quiet 1>$null 2>$null
            $secretExists = $LASTEXITCODE -eq 0
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        if (-not $secretExists) {
            & $Gcloud secrets create $SecretName --replication-policy automatic --project $ProjectId --quiet
            if ($LASTEXITCODE -ne 0) {
                throw "Could not create Secret Manager secret $SecretName."
            }
        }

        & $Gcloud secrets versions add $SecretName --data-file $tmp --project $ProjectId --quiet
        if ($LASTEXITCODE -ne 0) {
            throw "Could not add a new Secret Manager version for $SecretName."
        }
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Grant-SecretAccess {
    param(
        [string]$SecretName,
        [string]$ServiceAccount
    )
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Gcloud secrets add-iam-policy-binding $SecretName `
            --member "serviceAccount:$ServiceAccount" `
            --role "roles/secretmanager.secretAccessor" `
            --project $ProjectId --quiet 1>$null 2>$null
        $grantExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($grantExitCode -ne 0) {
        throw "Could not grant Cloud Run access to Secret Manager secret $SecretName."
    }
}

$Gcloud = Get-GcloudCommand
if (-not $Gcloud) {
    throw "gcloud was not found. Install Google Cloud CLI, then run: gcloud auth login"
}

if (-not $ProjectId) {
    $ProjectId = (& $Gcloud config get-value project 2>$null).Trim()
}
if ($ProjectId -in @("YOUR_GOOGLE_CLOUD_PROJECT_ID", "your-real-project-id", "your-project-id")) {
    throw "Replace '$ProjectId' with an actual Google Cloud project ID. To find it after login, run: gcloud projects list"
}
if (-not $ProjectId -or $ProjectId -eq "(unset)") {
    throw "Pass -ProjectId <google-cloud-project-id> or run: gcloud config set project <id>"
}

$activeAccount = (& $Gcloud config get-value account 2>$null).Trim()
if (-not $activeAccount) {
    throw "gcloud is not authenticated. Run: gcloud auth login"
}
if ($activeAccount -eq "(unset)") {
    throw "gcloud has no active account. Run: gcloud auth login"
}

$openAiKey = Get-EnvFileValue "OPENAI_API_KEY"
$appToken = Get-EnvFileValue "ALLOWED_APP_TOKEN"
$sessionSecret = Get-EnvFileValue "SESSION_TOKEN_SECRET"
$realtimeModel = Get-EnvFileValue "OPENAI_REALTIME_MODEL" "gpt-realtime-2"
$textModel = Get-EnvFileValue "OPENAI_TEXT_MODEL" "gpt-4.1-mini"

if (-not $openAiKey -or $openAiKey -eq "your_openai_api_key_here") {
    throw "backend\.env must contain a real OPENAI_API_KEY before deploying."
}
if (-not $appToken -or $appToken -eq "dev-local-token") {
    throw "backend\.env ALLOWED_APP_TOKEN must be a generated travel-safe token. Run scripts\run-backend-travel.ps1 once or set a strong value."
}
if (-not $sessionSecret -or $sessionSecret -eq "replace_with_a_long_random_secret" -or $sessionSecret -eq $appToken) {
    throw "backend\.env SESSION_TOKEN_SECRET must be a generated travel-safe secret."
}

if (-not $SkipEnableServices) {
    & $Gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com --project $ProjectId --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "Could not enable required Google Cloud services."
    }
}

$projectNumber = (& $Gcloud projects describe $ProjectId --format "value(projectNumber)").Trim()
if (-not $projectNumber) {
    throw "Could not resolve Google Cloud project number for $ProjectId."
}
$runServiceAccount = "$projectNumber-compute@developer.gserviceaccount.com"

$openAiSecret = "duddy-openai-api-key"
$appTokenSecret = "duddy-allowed-app-token"
$sessionSecretName = "duddy-session-token-secret"
$secretBindings = "OPENAI_API_KEY=${openAiSecret}:latest,ALLOWED_APP_TOKEN=${appTokenSecret}:latest,SESSION_TOKEN_SECRET=${sessionSecretName}:latest"

New-SecretVersion $openAiSecret $openAiKey
New-SecretVersion $appTokenSecret $appToken
New-SecretVersion $sessionSecretName $sessionSecret

Grant-SecretAccess $openAiSecret $runServiceAccount
Grant-SecretAccess $appTokenSecret $runServiceAccount
Grant-SecretAccess $sessionSecretName $runServiceAccount

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & $Gcloud run deploy $ServiceName `
        --source $BackendRoot `
        --region $Region `
        --project $ProjectId `
        --allow-unauthenticated `
        --ingress all `
        --port 8080 `
        --memory 512Mi `
        --cpu 1 `
        --max-instances 3 `
        --timeout 60 `
        --set-env-vars "OPENAI_REALTIME_MODEL=$realtimeModel,OPENAI_TEXT_MODEL=$textModel" `
        --set-secrets $secretBindings `
        --quiet
    $deployExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($deployExitCode -ne 0) {
    throw "Cloud Run deployment failed."
}

$serviceUrl = (& $Gcloud run services describe $ServiceName --region $Region --project $ProjectId --format "value(status.url)").Trim()
if (-not $serviceUrl) {
    throw "Cloud Run deployed, but no service URL was returned."
}

New-Item -ItemType Directory -Force -Path $LogsRoot | Out-Null
Set-Content -LiteralPath $CloudRunUrlPath -Value $serviceUrl

Write-Host ""
Write-Host "Duddy Translator stable travel backend is deployed:"
Write-Host "  $serviceUrl"
Write-Host ""
Write-Host "Install the Pixel travel build with:"
Write-Host "  .\scripts\install-pixel9-cloudrun.ps1"
