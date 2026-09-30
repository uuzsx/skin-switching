param(
    [string[]]$Projects = @('mc1212', 'mc2611', 'mc2612', 'mc263'),
    [hashtable]$NeoForge = @{},
    [switch]$ReuseMainClasses
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
$marker = Join-Path (Get-Location) 'shared/src/main/resources/skin-switching-mode.txt'
$original = [System.IO.File]::ReadAllText($marker)
$targets = Get-Content -Raw -LiteralPath 'versions.json' | ConvertFrom-Json
try {
    foreach ($project in $Projects) {
        if ($project -notin @('mc1212','mc2611','mc2612','mc263')) { throw "Unknown project: $project" }
        $loader = if ($NeoForge.ContainsKey($project)) { $NeoForge[$project] } else { $targets.$project.neoforge }
        $runArgs = @("${project}:runSmokePlay", "-P${project}.neoForge=$loader", '--console=plain')
        if ($ReuseMainClasses) {
            if (-not (Test-Path -LiteralPath "$project/build/classes/java/main")) { throw "Build baseline classes first: $project" }
            $runArgs += @('-x', "${project}:compileJava")
        }
        foreach ($edition in @('sync', 'client')) {
            [System.IO.File]::WriteAllText($marker, $edition + "`n")
            $startedAt = [DateTime]::UtcNow
            & .\gradlew.bat @runArgs
            if ($LASTEXITCODE -ne 0) { throw "Smoke client failed: $project / $edition" }
            $resultFile = Get-Item -LiteralPath "$project/run-smoke-play/smoke-result-$edition.txt"
            if ($resultFile.LastWriteTimeUtc -lt $startedAt) { throw "No fresh result: $project / $edition" }
            $result = Get-Content -Raw -LiteralPath $resultFile.FullName
            if (-not $result.StartsWith('PASS:')) { throw $result }
            if (-not $result.Contains("NeoForge $loader;")) { throw "Wrong runtime version: $result" }
            $recordDir = "$project/build/compatibility/$loader"
            New-Item -ItemType Directory -Path $recordDir -Force | Out-Null
            Copy-Item -LiteralPath $resultFile.FullName -Destination "$recordDir/$edition.txt"
            Write-Output "$project / $loader / $edition : $result"
        }
    }
} finally {
    [System.IO.File]::WriteAllText($marker, $original)
    Pop-Location
}
