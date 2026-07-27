param(
    [string]$Image = "ai-code-mother/sandbox-node:1",
    [string]$GoBaseImage = $env:GENERATED_CODE_SANDBOX_GO_BASE_IMAGE,
    [string]$DependencyNetwork = "ai-code-sandbox-egress",
    [string]$DevServerNetwork = "ai-code-sandbox-internal",
    [string]$PreviewGatewayNetwork = "ai-code-sandbox-preview-gateway",
    [string]$PnpmStoreVolume = "ai-code-mother-pnpm-store-v9"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Ensure-Network([string]$Name, [bool]$Internal) {
    $existing = docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $Name }
    if (-not $existing) {
        $arguments = @("network", "create", "--driver", "bridge")
        if ($Internal) {
            $arguments += "--internal"
        }
        $arguments += $Name
        docker @arguments | Out-Null
    }
    $actual = (docker network inspect --format "{{.Internal}}" $Name).Trim()
    if ($actual -ne $Internal.ToString().ToLowerInvariant()) {
        throw "Docker network '$Name' internal=$actual, expected internal=$Internal"
    }
}

function Ensure-Volume([string]$Name) {
    $existing = docker volume ls --format "{{.Name}}" | Where-Object { $_ -eq $Name }
    if (-not $existing) {
        docker volume create $Name | Out-Null
    }
}

Push-Location $ProjectRoot
try {
    docker version | Out-Null
    if ([string]::IsNullOrWhiteSpace($GoBaseImage) -or $GoBaseImage -notmatch '@sha256:[0-9a-fA-F]{64}$') {
        throw "必须通过 -GoBaseImage 或 GENERATED_CODE_SANDBOX_GO_BASE_IMAGE 提供带 sha256 摘要的 Go 基础镜像"
    }
    docker build `
        --pull `
        --build-arg "GO_BASE_IMAGE=$GoBaseImage" `
        --file docker/generated-code-sandbox/Dockerfile `
        --tag $Image `
        .
    Ensure-Network $DependencyNetwork $false
    Ensure-Network $DevServerNetwork $true
    Ensure-Network $PreviewGatewayNetwork $false
    Ensure-Volume $PnpmStoreVolume

    $env:SANDBOX_E2E_HOST_SECRET = "must-not-enter-generated-code-container"
    & .\mvnw.cmd `
        -Pintegration-test `
        "-Dtest=ContainerGeneratedCodeSandboxIntegrationTest" `
        "-DgeneratedCodeSandboxE2e=true" `
        "-DgeneratedCodeSandboxImage=$Image" `
        "-DgeneratedCodeSandboxDependencyNetwork=$DependencyNetwork" `
        "-DgeneratedCodeSandboxDevServerNetwork=$DevServerNetwork" `
        "-DgeneratedCodeSandboxPreviewGatewayNetwork=$PreviewGatewayNetwork" `
        "-DgeneratedCodeSandboxPnpmStoreVolume=$PnpmStoreVolume" `
        test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Remove-Item Env:SANDBOX_E2E_HOST_SECRET -ErrorAction SilentlyContinue
    Pop-Location
}
