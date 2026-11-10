$ErrorActionPreference = 'Stop'
python "$PSScriptRoot/updater_tools.py" test @args
if ($LASTEXITCODE -ne 0) { throw 'Local update test failed' }
