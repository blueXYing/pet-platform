param(
    [ValidateSet('Start','Stop')][string]$Mode='Start',
    [string]$JavaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot',
    [ValidateRange(60,28800)][int]$LifetimeSeconds=14400
)
$ErrorActionPreference='Stop'
$backendRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDirectory=[IO.Path]::GetFullPath((Join-Path $backendRoot 'pet-boot/target/aftersale-joint'))
if (-not $runtimeDirectory.StartsWith($backendRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Runtime directory escaped backend workspace'}
New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
if ($Mode -eq 'Stop') {
    [IO.File]::WriteAllText((Join-Path $runtimeDirectory 'stop'),'explicit local UI stop')
    Write-Output 'Stop requested; owning launcher will close fixture and its exact containers.'
    exit 0
}
if (Test-Path -LiteralPath (Join-Path $runtimeDirectory 'runtime.json')) {throw 'A joint runtime already exists; stop its owner before starting another'}
$dockerHost='npipe:////./pipe/docker_engine'
$runId=[Guid]::NewGuid().ToString('N')
$containers=[Collections.Generic.List[object]]::new()
$metadataPath=Join-Path $runtimeDirectory 'containers.json'
$mavenExit=1
function Invoke-JointDocker([string[]]$Arguments) {
    $result=& docker --host $dockerHost @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {throw 'Task Docker command failed; inspect local Docker state'}
    return ($result -join "`n")
}
function Create-JointContainer([string]$Kind,[string]$Image,[int]$ContainerPort,[string[]]$ExtraArguments) {
    $name='pet-aftersale-joint-'+$runId.Substring(0,12)+'-'+$Kind
    $arguments=@('run','--detach','--pull=never','--name',$name,'--label',('pet.aftersale.joint='+$runId),'-p',('127.0.0.1::'+$ContainerPort))+$ExtraArguments+@($Image)
    if ($Kind -eq 'redis') {$arguments+=@('redis-server','--save','','--appendonly','no')}
    $id=(Invoke-JointDocker $arguments).Trim()
    if ($id -notmatch '^[a-f0-9]{64}$') {throw 'Unexpected container ID'}
    $item=[pscustomobject]@{kind=$Kind;id=$id;name=$name;containerPort=$ContainerPort;hostPort=$null}
    $containers.Add($item)
    $state=(Invoke-JointDocker @('inspect',$id) | ConvertFrom-Json)[0]
    $binding=$state.NetworkSettings.Ports.($ContainerPort.ToString()+'/tcp')[0]
    if ($state.Id -ne $id -or $state.Config.Labels.'pet.aftersale.joint' -ne $runId -or $binding.HostIp -ne '127.0.0.1') {throw 'Container identity or loopback binding verification failed'}
    $item.hostPort=[int]$binding.HostPort
    return $item
}
try {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {throw 'Java 21 runtime not found'}
    $mysql=Create-JointContainer 'mysql' 'mysql:8.4' 3306 @('-e','MYSQL_ALLOW_EMPTY_PASSWORD=yes')
    $redis=Create-JointContainer 'redis' 'redis:7.4-alpine' 6379 @()
    [IO.File]::WriteAllText($metadataPath,(@{runId=$runId;launcherPid=$PID;containers=@($containers)} | ConvertTo-Json -Depth 5))
    $ready=$false
    for ($attempt=0;$attempt -lt 90;$attempt++) {
        & docker --host $dockerHost exec $mysql.id mysqladmin ping --host=127.0.0.1 --silent 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {$ready=$true;break}
        Start-Sleep -Milliseconds 1000
    }
    if (-not $ready) {throw 'Isolated MySQL did not become ready'}
    $redisReply=Invoke-JointDocker @('exec',$redis.id,'redis-cli','PING')
    if ($redisReply.Trim() -ne 'PONG') {throw 'Isolated Redis readiness failed'}
    $env:JAVA_HOME=$JavaHome
    $env:PATH=(Join-Path $JavaHome 'bin')+';'+$env:PATH
    $env:AUTH_MYSQL_URL='jdbc:mysql://127.0.0.1:'+$mysql.hostPort+'/'
    $env:AUTH_MYSQL_USER='root';$env:AUTH_MYSQL_PASSWORD=''
    $env:AUTH_REDIS_HOST='127.0.0.1';$env:AUTH_REDIS_PORT=$redis.hostPort.ToString()
    # Ignore caller BOOKING variables so this test cannot attach to another local database.
    Remove-Item Env:BOOKING_MYSQL_URL -ErrorAction SilentlyContinue
    Remove-Item Env:BOOKING_MYSQL_USER -ErrorAction SilentlyContinue
    Remove-Item Env:BOOKING_MYSQL_PASSWORD -ErrorAction SilentlyContinue
    $env:AFS_JOINT_LIVE='true';$env:AFS_JOINT_RUNTIME_DIR=$runtimeDirectory
    $env:AFS_JOINT_TIMEOUT_SECONDS=$LifetimeSeconds.ToString()
    Push-Location $backendRoot
    try {
        & mvn -o -pl pet-boot -am test '-Dtest=AfterSaleJointLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' *> (Join-Path $runtimeDirectory 'maven-live.log')
        $mavenExit=$LASTEXITCODE
    } finally {Pop-Location}
} finally {
    # Delete only recorded task container IDs after rechecking exact label/name/loopback target.
    foreach ($item in $containers) {
        $state=(Invoke-JointDocker @('inspect',$item.id) | ConvertFrom-Json)[0]
        $binding=$state.HostConfig.PortBindings.($item.containerPort.ToString()+'/tcp')[0]
        if ($state.Id -ne $item.id -or $state.Name -ne ('/'+$item.name) -or $state.Config.Labels.'pet.aftersale.joint' -ne $runId -or $binding.HostIp -ne '127.0.0.1') {throw 'Refusing cleanup: recorded container identity or target changed'}
        Invoke-JointDocker @('rm','--force','--volumes',$item.id) | Out-Null
    }
    [IO.File]::WriteAllText((Join-Path $runtimeDirectory 'cleanup.json'),(@{runId=$runId;removedContainers=$containers.Count;mysqlRedisOnly=$true} | ConvertTo-Json))
}
Write-Output ('Joint fixture stopped; Maven exit '+$mavenExit+'. Exact task containers removed.')
exit $mavenExit
