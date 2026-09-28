$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
$marker = Join-Path (Get-Location) 'src/main/resources/skin-switching-mode.txt'
$original = [System.IO.File]::ReadAllText($marker)
try {
    foreach ($edition in @('sync', 'client')) {
        [System.IO.File]::WriteAllText($marker, $edition + "`n")
        $startedAt = [DateTime]::UtcNow
        & .\gradlew.bat runSmokePlay --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Smoke client failed to start: $edition" }
        $resultFile = Get-Item -LiteralPath "run-smoke-play/smoke-result-$edition.txt"
        if ($resultFile.LastWriteTimeUtc -lt $startedAt) { throw "No fresh smoke result: $edition" }
        $result = Get-Content -Raw -LiteralPath $resultFile.FullName
        if (-not $result.StartsWith('PASS:')) { throw $result }
        Write-Output $result
    }
} finally {
    [System.IO.File]::WriteAllText($marker, $original)
    Pop-Location
}
