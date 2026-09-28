param([string[]]$Projects = @('mc1212', 'mc2611', 'mc2612', 'mc263'))
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
$marker = Join-Path (Get-Location) 'shared/src/main/resources/skin-switching-mode.txt'
$original = [System.IO.File]::ReadAllText($marker)
try {
    foreach ($project in $Projects) {
        if ($project -notin @('mc1212','mc2611','mc2612','mc263')) { throw "Unknown project: $project" }
        foreach ($edition in @('sync', 'client')) {
            [System.IO.File]::WriteAllText($marker, $edition + "`n")
            $startedAt = [DateTime]::UtcNow
            & .\gradlew.bat ":${project}:runSmokePlay" --console=plain
            if ($LASTEXITCODE -ne 0) { throw "Smoke client failed: $project / $edition" }
            $resultFile = Get-Item -LiteralPath "$project/run-smoke-play/smoke-result-$edition.txt"
            if ($resultFile.LastWriteTimeUtc -lt $startedAt) { throw "No fresh result: $project / $edition" }
            $result = Get-Content -Raw -LiteralPath $resultFile.FullName
            if (-not $result.StartsWith('PASS:')) { throw $result }
            Write-Output "$project / $edition : $result"
        }
    }
} finally {
    [System.IO.File]::WriteAllText($marker, $original)
    Pop-Location
}
