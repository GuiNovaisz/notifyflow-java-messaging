param([string]$BaseUrl='http://localhost:8080')
$ErrorActionPreference='Stop'
$body=@{recipient='smoke@example.test';channel='EMAIL';subject='SUCCESS';body='Smoke notification'} | ConvertTo-Json -Compress
$key=[guid]::NewGuid().ToString()
$result=Invoke-RestMethod "$BaseUrl/api/v1/notifications" -Method Post -ContentType 'application/json' -Headers @{'Idempotency-Key'=$key} -Body $body
$replay=Invoke-RestMethod "$BaseUrl/api/v1/notifications" -Method Post -ContentType 'application/json' -Headers @{'Idempotency-Key'=$key} -Body $body
if($result.notificationId -ne $replay.notificationId) { throw 'Intake idempotency failed' }
$deadline=[DateTime]::UtcNow.AddSeconds(90)
do {
  $deliveries=@(Invoke-RestMethod "$BaseUrl/api/v1/notifications/$($result.notificationId)/deliveries")
  if($deliveries.Count -eq 1 -and $deliveries[0].status -eq 'DELIVERED') {
    Write-Output "PASS: notification=$($result.notificationId), one DELIVERED delivery, intake replay preserved identity"
    exit 0
  }
  Start-Sleep -Milliseconds 250
} while([DateTime]::UtcNow -lt $deadline)
throw 'Delivery deadline exceeded'
