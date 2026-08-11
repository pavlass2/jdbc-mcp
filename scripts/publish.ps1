<#
.SYNOPSIS
    Build the JVM image and push it to a container registry.

.DESCRIPTION
    The PowerShell twin of publish.sh, for Windows. Same behaviour: builds, tags, and
    applies the same ':latest' rule as CI.

    `docker login` must have been run first - this script deliberately does not handle
    credentials.

    Tagging a release in git (`git tag v1.0; git push origin v1.0`) runs the same build in
    CI via .github/workflows/docker-publish.yml, which is the better route for anything
    other people will pull. This script is for getting a build onto a machine now.

.EXAMPLE
    .\scripts\publish.ps1 1.0
.EXAMPLE
    .\scripts\publish.ps1 1.1-rc1 -NoPush
.EXAMPLE
    .\scripts\publish.ps1 1.0 -Image ghcr.io/me/thing
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Version,

    [string]$Image = 'pavelmichalec/ora-jdbc-mcp',

    # Build and tag locally, push nothing.
    [switch]$NoPush,

    # The build needs JDK 17. Defaults to whatever JAVA_HOME already points at; pass this
    # when the shell default is a different JDK. Only ever set for the duration of this
    # script - the ambient JAVA_HOME is restored on exit, including on failure.
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

$previousJavaHome = $env:JAVA_HOME
try {
    if ($JavaHome) {
        $env:JAVA_HOME = $JavaHome
    }

    # The build fails deep inside a Quarkus mojo with an unhelpful NoSuchElementException
    # when it runs on JDK 11, so check up front and say what is actually wrong.
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    if (-not (Get-Command $javaExe -ErrorAction SilentlyContinue)) {
        throw "No java found at '$javaExe'. Pass -JavaHome pointing at a JDK 17 installation."
    }
    $versionLine = (& $javaExe -version 2>&1)[0]
    if ($versionLine -notmatch '"(\d+)') {
        throw "Could not read the Java version from: $versionLine"
    }
    $major = [int]$Matches[1]
    if ($major -lt 17) {
        throw ("This project needs JDK 17, but '$javaExe' is $major ($versionLine). " +
               "Pass -JavaHome pointing at a JDK 17 installation, e.g. " +
               "-JavaHome 'C:\Users\you\.jdks\liberica-17.0.5'.")
    }
    Write-Host "==> Using JDK $major from $($env:JAVA_HOME)" -ForegroundColor Cyan

    Write-Host '==> Building (this runs the test suite; Oracle container tests stay opt-in)' -ForegroundColor Cyan
    & .\mvnw.cmd package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }

    $primaryTag = "${Image}:${Version}"
    Write-Host "==> Building image $primaryTag" -ForegroundColor Cyan
    & docker build -f src/main/docker/Dockerfile.jvm -t $primaryTag .
    if ($LASTEXITCODE -ne 0) { throw "docker build failed with exit code $LASTEXITCODE" }

    $tags = @($primaryTag)

    # Only a plain version number moves ':latest', so a release candidate or a dated build
    # cannot become what everyone pulls by default. Same rule as the CI workflows.
    if ($Version -match '^\d+(\.\d+)*$') {
        $latestTag = "${Image}:latest"
        & docker tag $primaryTag $latestTag
        if ($LASTEXITCODE -ne 0) { throw "docker tag failed with exit code $LASTEXITCODE" }
        $tags += $latestTag
        Write-Host "==> Also tagged $latestTag" -ForegroundColor Cyan
    }

    if ($NoPush) {
        Write-Host "==> -NoPush given, stopping. Built: $($tags -join ', ')" -ForegroundColor Yellow
        return
    }

    foreach ($tag in $tags) {
        Write-Host "==> Pushing $tag" -ForegroundColor Cyan
        & docker push $tag
        if ($LASTEXITCODE -ne 0) { throw "docker push failed with exit code $LASTEXITCODE" }
    }

    Write-Host ''
    Write-Host 'Done. Pull with:' -ForegroundColor Green
    Write-Host "  docker pull $primaryTag"
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
