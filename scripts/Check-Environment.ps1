$ErrorActionPreference = "Stop"
Write-Host "Checking Git..."
git --version
Write-Host "Checking Java..."
java -version
Write-Host "Checking Maven..."
mvn -version
Write-Host "Checking Node..."
node --version
Write-Host "Environment check finished."
