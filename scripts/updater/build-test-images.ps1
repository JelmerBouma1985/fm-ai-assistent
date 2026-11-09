$ErrorActionPreference = 'Stop'
python "$PSScriptRoot/updater_tools.py" build @args
if ($LASTEXITCODE -ne 0) { throw 'Packaged test builds failed' }
