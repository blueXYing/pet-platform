param(
  [Parameter(Mandatory=$true)][string]$IssueId,
  [Parameter(Mandatory=$true)][string]$Branch,
  [string]$WorktreeParent = ".."
)
$ErrorActionPreference = "Stop"
$folder = Join-Path $WorktreeParent ("wt-" + $IssueId.ToLower())

git worktree remove $folder
git branch -d $Branch

Write-Host "Removed task worktree and local branch."
