# Raccourci : tools\xm.ps1 battery nc eq ...   (le pont tools\pc-bridge\run.ps1 doit tourner)
# Fixe JAVA_HOME sur le JDK d'Android Studio si absent, et la sortie console en UTF-8.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Push-Location (Split-Path $PSScriptRoot -Parent)
try { & .\gradlew.bat :desktop:run --console=plain -q "--args=$($args -join ' ')" }
finally { Pop-Location }
