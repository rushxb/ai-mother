param(
    [string]$Image = "mysql:8.4",
    [string]$RootPassword = "ai-code-mother-it"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ContainerName = "ai-code-mysql-it-$PID"

Push-Location $ProjectRoot
try {
    docker version | Out-Null
    docker run --detach --rm `
        --name $ContainerName `
        --env "MYSQL_ROOT_PASSWORD=$RootPassword" `
        --publish "127.0.0.1::3306" `
        $Image | Out-Null

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        docker exec --env "MYSQL_PWD=$RootPassword" $ContainerName mysqladmin ping `
            --host=127.0.0.1 `
            --user=root `
            --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        throw "MySQL integration container did not become ready"
    }

    $portMapping = (docker port $ContainerName "3306/tcp").Trim()
    $hostPort = $portMapping.Substring($portMapping.LastIndexOf(':') + 1)
    $adminUrl = "jdbc:mysql://127.0.0.1:$hostPort/?allowPublicKeyRetrieval=true"

    & .\mvnw.cmd `
        -Pintegration-test `
        "-Dtest=FlywaySchemaMigrationIntegrationTest,PromptReleaseMySqlIntegrationTest,ToolApprovalMySqlIntegrationTest,DevServerSessionRegistryMySqlIntegrationTest" `
        "-Dintegration.mysql.admin-url=$adminUrl" `
        "-Dintegration.mysql.username=root" `
        "-Dintegration.mysql.password=$RootPassword" `
        test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    docker rm --force $ContainerName 2>$null | Out-Null
    Pop-Location
}
