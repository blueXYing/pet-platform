param(
  [Parameter(Mandatory=$true)][string]$IssueId,
  [Parameter(Mandatory=$true)][string]$Slug,
  [string]$BaseBranch = "develop",
  [string]$WorktreeParent = ".."
)
$ErrorActionPreference = "Stop"
$branch = "feat/$IssueId-$Slug"
$folder = Join-Path $WorktreeParent ("wt-" + $IssueId.ToLower())

git fetch --all --prune
git switch $BaseBranch
git pull --ff-only
git worktree add $folder -b $branch $BaseBranch

Write-Host "Created:"
Write-Host "  Branch: $branch"
Write-Host "  Worktree: $folder"
Write-Host "Open this folder in a NEW Codex Thread."
