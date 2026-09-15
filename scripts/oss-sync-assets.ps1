# OSS 一键重新导入(CCR-OSS-001)
# 用法:在仓库根目录执行  powershell -File scripts/oss-sync-assets.ps1
# 可选参数(带默认值):
#   -DbJdbcUrl / -DbUser / -DbPass   注册表所在MySQL(默认本机测试实例占位,按需改)
#   -MinBytes                        同步阈值,默认 51200(50KB,小于此随包不入OSS)
# 说明:密钥从 ops/oss.env.local 读取并只注入当前进程环境,绝不写入仓库/日志。
param(
  [string]$DbJdbcUrl = "jdbc:mysql://127.0.0.1:33440/",
  [string]$DbUser = "root",
  [string]$DbPass = "",
  [long]$MinBytes = 51200
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root "ops/oss.env.local"
if (-not (Test-Path $envFile)) { throw "缺少 $envFile;请先按模板填写OSS连接信息" }
Get-Content $envFile | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
    $idx = $line.IndexOf("=")
    $name = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1).Trim()
    if ($value) { Set-Item -Path ("Env:" + $name) -Value $value }
  }
}
$env:OSS_SYNC_MIN_BYTES = "$MinBytes"
$designRoot = Join-Path $root "planning/issues/wave-2"
$javaHome = "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
if (Test-Path $javaHome) { $env:JAVA_HOME = $javaHome }
Write-Host "== OSS 一键同步:大切图(>=$MinBytes 字节)与原图 → S3 + 注册表 =="
mvn -q -f (Join-Path $root "backend/pom.xml") -pl pet-thirdparty-biz -am `
  exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=com.petplatform.thirdparty.biz.application.OssAssetSyncCli" `
  "-Dexec.args=$DbJdbcUrl $DbUser $DbPass c002-assets=$designRoot/C-002-design-inputs/handoff/assets c002-originals=$designRoot/C-002-design-inputs/original-fills m002-assets=$designRoot/M-002-design-inputs/handoff/assets m002-originals=$designRoot/M-002-design-inputs/original-fills"
Write-Host "== 完成。注册表(asset_registry)已同步;重复执行为幂等无副作用 =="
