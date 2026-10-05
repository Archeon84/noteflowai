# PowerShell script to clone whisper.cpp into the correct location
Set-Location $PSScriptRoot
Write-Host "Cloning whisper.cpp into $pwd\whisper ..."
git clone https://github.com/ggerganov/whisper.cpp.git whisper
Write-Host "Done. The whisper.cpp source code is now in app/src/main/cpp/whisper."
Write-Host "Next: place a GGML model file (e.g., ggml-tiny.bin) in app/src/main/assets/models/ and build the project."