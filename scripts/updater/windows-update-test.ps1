param([switch]$CheckPrerequisites)

$ErrorActionPreference = 'Stop'

try {
    $repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    if (!(Test-Path -LiteralPath (Join-Path $repository 'mvnw.cmd'))) {
        throw 'Run this launcher from an intact fm-ai-assistent checkout.'
    }

    $jdkDirectory = $env:JAVA_HOME
    if (!$jdkDirectory -or !(Test-Path -LiteralPath (Join-Path $jdkDirectory 'release'))) {
        throw 'Set JAVA_HOME to your JDK 25 installation, then run this file again.'
    }
    if (!(Select-String -LiteralPath (Join-Path $jdkDirectory 'release') -Pattern '^JAVA_VERSION="25(\.|\")' -Quiet)) {
        throw 'JAVA_HOME must point to JDK 25.'
    }
    foreach ($tool in @('java.exe', 'jpackage.exe', 'jlink.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $jdkDirectory "bin/$tool"))) {
            throw "JDK 25 is missing $tool. Install a full JDK and set JAVA_HOME."
        }
    }

    $pythonExecutable = $null
    $pythonPrefix = @()
    foreach ($name in @('python.exe', 'python3.exe', 'py.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if (!$command -or $command.Source -like '*\Microsoft\WindowsApps\*') { continue }
        $prefix = @()
        if ($name -eq 'py.exe') { $prefix = @('-3') }
        try {
            & $command.Source @prefix -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>$null
            if ($LASTEXITCODE -eq 0) {
                $pythonExecutable = $command.Source
                $pythonPrefix = $prefix
                break
            }
        } catch { }
    }
    if (!$pythonExecutable -and (Get-Command uv -ErrorAction SilentlyContinue)) {
        try {
            $foundPython = (& uv python find 2>$null | Select-Object -First 1)
            if ($LASTEXITCODE -eq 0 -and $foundPython -and (Test-Path -LiteralPath $foundPython)) {
                & $foundPython -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>$null
                if ($LASTEXITCODE -eq 0) { $pythonExecutable = $foundPython }
            }
        } catch { }
    }
    if (!$pythonExecutable) {
        throw 'Install Python 3.10+ (or make it available through py or uv), then run this file again.'
    }

    Write-Host "JDK:    $jdkDirectory"
    Write-Host "Python: $pythonExecutable"
    if ($CheckPrerequisites) {
        Write-Host 'Prerequisites OK. No builds or applications were started.'
        exit 0
    }

    $runName = 'fmai-updater-manual-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $runDirectory = Join-Path ([IO.Path]::GetTempPath()) $runName
    $builds = Join-Path $runDirectory 'builds'
    $sandbox = Join-Path $runDirectory 'sandbox'
    New-Item -ItemType Directory -Path $runDirectory | Out-Null

    Write-Host "Test files and logs: $runDirectory"
    Write-Host 'Building versions 2.1.0 and 2.1.1. This can take several minutes.'
    Write-Host 'Build logs: builds/old-build.log and builds/new-build.log under the directory above.'
    $tools = Join-Path $PSScriptRoot 'updater_tools.py'
    & $pythonExecutable @pythonPrefix $tools build --output-directory $builds
    if ($LASTEXITCODE -ne 0) { throw 'Packaged builds failed. Inspect the build logs above.' }

    Write-Host ''
    Write-Host 'The isolated 2.1.0 app will open. Wait for the LOCAL update offer, then click Download & install once.'
    Write-Host 'After it restarts as 2.1.1, expect PASS here. Close the test app after inspection.'
    & $pythonExecutable @pythonPrefix $tools test `
        --old-image (Join-Path $builds 'old/target/dist/FM AI Assistent') `
        --new-image (Join-Path $builds 'new/target/dist/FM AI Assistent') `
        --sandbox $sandbox --wait --timeout 1800
    if ($LASTEXITCODE -ne 0) { throw "Update test failed. Inspect $sandbox for application and transaction logs." }
    Write-Host "Test completed. Files retained at $runDirectory"
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
