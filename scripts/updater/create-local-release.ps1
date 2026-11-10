$ErrorActionPreference = 'Stop'
python "$PSScriptRoot/updater_tools.py" create @args
if ($LASTEXITCODE -ne 0) { throw 'Local release creation failed' }
