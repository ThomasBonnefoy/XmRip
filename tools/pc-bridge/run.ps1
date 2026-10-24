# Lance le pont RFCOMM -> TCP. Le venv vit hors du dépôt (dossier synchronisé par Seafile).
$venv = Join-Path $env:LOCALAPPDATA 'xmrip-bridge\venv'
$py = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path $py)) {
    python -m venv $venv
    & $py -m pip install --quiet --disable-pip-version-check winsdk
}
& $py (Join-Path $PSScriptRoot 'rfcomm_bridge.py') @args
