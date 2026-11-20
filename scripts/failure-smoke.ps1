param([string]$BaseUrl='http://localhost:8080',[string]$Project='notifyflow')
$ErrorActionPreference='Stop'
if($Project -notmatch '^notifyflow(?:-[a-z0-9]+)?$') { throw 'Use a task-owned notifyflow Compose project' }
$compose=@('compose','-p',$Project,'-f','compose.yaml','-f','compose.failure.yaml')
$dockerExecutable=(Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
function Docker([string[]]$Arguments) {
  $output=& $dockerExecutable @Arguments
  if($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments[0..1] -join ' ')" }
  return $output
}
function New-Notification([string]$Subject) {
  $body=@{recipient='failure@example.test';channel='EMAIL';subject=$Subject;body='Failure proof'} | ConvertTo-Json -Compress
  return (Invoke-RestMethod "$BaseUrl/api/v1/notifications" -Method Post -ContentType 'application/json' -Headers @{'Idempotency-Key'=[guid]::NewGuid().ToString()} -Body $body).notificationId
}
function Wait-Delivery([string]$Id,[string]$Status) {
  $deadline=[DateTime]::UtcNow.AddSeconds(120)
  do {
    try {
      $rows=@(Invoke-RestMethod "$BaseUrl/api/v1/notifications/$Id/deliveries")
      if($rows.Count -eq 1 -and $rows[0].status -eq $Status) { return }
    } catch { if([DateTime]::UtcNow -ge $deadline) { throw } }
    Start-Sleep -Milliseconds 300
  } while([DateTime]::UtcNow -lt $deadline)
  throw "Delivery $Id did not reach $Status"
}
function Sql([string]$Service,[string]$Query) {
  return ((Docker ($compose+@('exec','-T',$Service,'psql','-U','notifyflow','-d','notifyflow','-Atc',$Query))) -join '').Trim()
}
try {
  Docker ($compose+@('stop','rabbitmq')) | Out-Null
  $brokerId=New-Notification 'SUCCESS'
  $pending=Sql 'api-db' "select count(*) from outbox_events where aggregate_id='$brokerId' and published_at is null"
  if($pending -ne '1') { throw 'Broker outage lost pending outbox' }
} finally { Docker ($compose+@('start','rabbitmq')) | Out-Null }
Wait-Delivery $brokerId 'DELIVERED'
Write-Output 'PASS broker outage: accepted + pending outbox + recovered delivery'
try {
  Docker ($compose+@('stop','worker')) | Out-Null
  $workerId=New-Notification 'SUCCESS'
  $deadline=[DateTime]::UtcNow.AddSeconds(30)
  do {
    $published=Sql 'api-db' "select count(*) from outbox_events where aggregate_id='$workerId' and published_at is not null"
    if($published -eq '1') { break }
    Start-Sleep -Milliseconds 300
  } while([DateTime]::UtcNow -lt $deadline)
  if($published -ne '1') { throw 'Worker outage: event was not published' }
} finally { Docker ($compose+@('start','worker')) | Out-Null }
Wait-Delivery $workerId 'DELIVERED'
Write-Output 'PASS worker outage: broker backlog survived worker restart'
$workerContainer=(Docker ($compose+@('ps','-q','worker'))).Trim()
$before=[int](Docker -Arguments @('inspect','--format','{{.RestartCount}}',$workerContainer))
$crashId=New-Notification 'CRASH_AFTER_PROVIDER'
Wait-Delivery $crashId 'DELIVERED'
$after=[int](Docker -Arguments @('inspect','--format','{{.RestartCount}}',$workerContainer))
if($after -le $before) { throw 'Worker process did not restart after injected halt' }
$eventId=Sql 'worker-db' "select event_id from deliveries where notification_id='$crashId'"
$effects=Sql 'provider-db' "select count(*) from provider_effects where key='$eventId'"
$calls=Sql 'provider-db' "select calls from provider_requests where key='$eventId'"
$attempts=Sql 'worker-db' "select attempt_count from deliveries where notification_id='$crashId'"
if($effects -ne '1' -or $calls -ne '2' -or $attempts -ne '1') { throw "Crash invariant failed: effects=$effects calls=$calls attempts=$attempts" }
Write-Output 'PASS process crash: halt(91), automatic restart, redelivery, provider calls=2, logical effects=1, committed attempts=1'
foreach($scenario in @(@('FAIL_TWICE','DELIVERED',3),@('PERMANENT','DEAD_LETTERED',1),@('ALWAYS_FAIL','DEAD_LETTERED',4),@('TIMEOUT','DEAD_LETTERED',4))) {
  $id=New-Notification $scenario[0]
  Wait-Delivery $id $scenario[1]
  $attempts=Sql 'worker-db' "select attempt_count from deliveries where notification_id='$id'"
  if($attempts -ne [string]$scenario[2]) { throw "Unexpected attempt count for $($scenario[0])" }
  Write-Output "PASS $($scenario[0]): $($scenario[1]), attempts=$attempts"
}
$dlq=Sql 'worker-db' "select count(*) from outbox_events where exchange_name='notifyflow.dead.v1' and published_at is not null"
if([int]$dlq -lt 3) { throw 'Terminal messages were not confirmed to DLQ' }
Write-Output 'PASS terminal outbox messages confirmed to DLQ'
