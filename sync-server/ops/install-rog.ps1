param(
    [switch]$Apply,
    [string]$GatewayModule = 'C:\Users\Meta\Project\Workspaces\dayweave\.local\https-gateway\sync-gateway.mjs',
    [string]$GatewayTaskName = 'DayweaveHttpsGateway'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$server = Join-Path $project 'sync-server'
$private = Join-Path $project '.local\native-sync'
$node = (Get-Command node -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $GatewayModule -PathType Leaf)) { throw 'The existing shared gateway module is missing.' }
$gatewayTask = Get-ScheduledTask -TaskName $GatewayTaskName -ErrorAction Stop
if ($gatewayTask.Actions.Count -ne 1) { throw 'Unexpected shared gateway task actions.' }
$originalGateway = $gatewayTask.Actions[0]
$entry = Join-Path $server 'shared-gateway.mjs'
$expectedArguments = '"' + $entry + '" "' + $GatewayModule + '"'
if ($originalGateway.Arguments -ne ('"' + $GatewayModule + '"') -and $originalGateway.Arguments -ne $expectedArguments) {
    throw 'The shared gateway task changed; inspect before applying.'
}
if (-not $Apply) {
    Write-Output 'PREPARE ONLY: no files, credentials, services, tasks or routes changed.'
    Write-Output ('Dedicated backend: ' + (Join-Path $private 'data') + ' on loopback:18742')
    Write-Output ('Requires approval: create MojianNativeSync startup task and restart ' + $GatewayTaskName)
    Write-Output 'Existing Dayweave and subscription routes are delegated unchanged. SakuraFrpService is not touched.'
    return
}
if (Get-ScheduledTask -TaskName 'MojianNativeSync' -ErrorAction SilentlyContinue) {
    throw 'MojianNativeSync already exists. Keep its credentials and data; inspect rather than overwrite or rotate.'
}
New-Item -ItemType Directory -Path $private -Force | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($principal in @($identity, 'NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $private -AclObject $acl
$configurationPath = Join-Path $private 'server.json'
if (-not (Test-Path -LiteralPath $configurationPath)) {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $configuration = @{ port = 18742; dataRoot = (Join-Path $private 'data'); token = [Convert]::ToBase64String($bytes) }
    [IO.File]::WriteAllText($configurationPath, ($configuration | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
}
$backup = Join-Path $private 'gateway-task.previous.xml'
if (-not (Test-Path -LiteralPath $backup)) {
    [IO.File]::WriteAllText($backup, (Export-ScheduledTask -TaskName $GatewayTaskName), [Text.Encoding]::Unicode)
}
$action = New-ScheduledTaskAction -Execute $node -Argument ('"' + (Join-Path $server 'run-configured.mjs') + '" "' + $configurationPath + '"') -WorkingDirectory $server
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType S4U -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'MojianNativeSync' -Action $action -Trigger $trigger -Principal $principal -Settings $settings | Out-Null
Start-ScheduledTask -TaskName 'MojianNativeSync'
$ready = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    try {
        $reply = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18742/mojian-sync/v1/manifest' -TimeoutSec 2
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 401) { $ready = $true; break }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'Backend did not become ready. Gateway was not changed; inspect the new backend task.' }
$gatewayAction = New-ScheduledTaskAction -Execute $node -Argument $expectedArguments -WorkingDirectory $originalGateway.WorkingDirectory
try {
    Stop-ScheduledTask -TaskName $GatewayTaskName
    Set-ScheduledTask -TaskName $GatewayTaskName -Action $gatewayAction | Out-Null
    Start-ScheduledTask -TaskName $GatewayTaskName
    Write-Output ('Configured. Verify public HTTPS before enabling any dedicated client. Private configuration: ' + $configurationPath)
} catch {
    Stop-ScheduledTask -TaskName $GatewayTaskName -ErrorAction SilentlyContinue
    Set-ScheduledTask -TaskName $GatewayTaskName -Action $originalGateway | Out-Null
    Start-ScheduledTask -TaskName $GatewayTaskName
    throw
}
