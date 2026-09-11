param(
  [string]$InitialBranch = "main"
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path ".git")) {
  git init -b $InitialBranch
  git add .
  git commit -m "chore: bootstrap pet platform v1.0 AI development repo"
} else {
  Write-Host "Git repository already exists. Skip git init."
}

$branches = git branch --format="%(refname:short)"
if ($branches -notcontains "develop") {
  git branch develop
  Write-Host "Created develop branch."
} else {
  Write-Host "develop branch already exists."
}

Write-Host "Local Git bootstrap finished."
Write-Host "GitHub remote is NOT configured automatically. Let Work/Codex configure it after you provide the repository URL."
