# CacheSQL Master-Slave Integration Test
# Usage: .\test-replication.ps1

$ErrorActionPreference = "Continue"
$cp = "target\classes;" + (Get-Content cp.txt -Raw).Trim()

function Wait-Ready([string]$url, [int]$timeoutSec=30) {
    $start = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt $timeoutSec) {
        try {
            $r = Invoke-RestMethod -Uri "$url/cache/health" -TimeoutSec 2 -ErrorAction Stop
            if ($r.code -eq 0) { return $true }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function WC { 
    $w = New-Object System.Net.WebClient
    $w.Encoding = [System.Text.Encoding]::UTF8
    return $w
}

# ===== Cleanup =====
Write-Host "=== Cleanup ===" -ForegroundColor Yellow
foreach ($p in @(8080,8081,19091)) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($c) { $c.OwningProcess | Select-Object -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }
}
Remove-Item master.log,slave.log -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# ===== Start Master =====
Write-Host "=== Starting Master ===" -ForegroundColor Yellow
Start-Process -FilePath "java" -ArgumentList "-Dconfig=config-master.properties","-cp",$cp,"com.browise.database.Main" -RedirectStandardOutput "master.log" -WindowStyle Hidden
if (Wait-Ready "http://127.0.0.1:8080") {
    Write-Host "  Master READY (port 8080)" -ForegroundColor Green
} else {
    Write-Host "  Master FAILED to start" -ForegroundColor Red; exit 1
}

# ===== Start Slave =====
Write-Host "=== Starting Slave ===" -ForegroundColor Yellow
Start-Process -FilePath "java" -ArgumentList "-Dconfig=config-slave.properties","-cp",$cp,"com.browise.database.Main" -RedirectStandardOutput "slave.log" -WindowStyle Hidden
if (Wait-Ready "http://127.0.0.1:8081") {
    Write-Host "  Slave READY (port 8081)" -ForegroundColor Green
} else {
    Write-Host "  Slave FAILED to start" -ForegroundColor Red; exit 1
}

# ===== Load Tables =====
$wc = WC
Write-Host "=== Load Tables ===" -ForegroundColor Yellow
$m = $wc.UploadString("http://127.0.0.1:8080/cache/load","table=test_rep")
Write-Host "  Master: $m"
$s = $wc.UploadString("http://127.0.0.1:8081/cache/load","table=test_rep")
Write-Host "  Slave:  $s"

# ===== Test 1: Master Insert =====
Write-Host "=== Test 1: Insert on Master, Slave syncs ===" -ForegroundColor Cyan
$r = $wc.UploadString("http://127.0.0.1:8080/cache/insert","table=test_rep&column=id&value=001&name=Alice&age=25")
Start-Sleep -Seconds 1
$qm = $wc.DownloadString("http://127.0.0.1:8080/cache/get?table=test_rep&column=id&value=001")
$qs = $wc.DownloadString("http://127.0.0.1:8081/cache/get?table=test_rep&column=id&value=001")
$pass1 = ($qm -match '"id":"001"') -and ($qs -match '"id":"001"')
Write-Host "  Master: $qm"
Write-Host "  Slave:  $qs"
Write-Host "  Result: $(if($pass1){'PASS'}else{'FAIL'})" -ForegroundColor $(if($pass1){'Green'}else{'Red'})

# ===== Test 2: Slave Insert =====
Write-Host "=== Test 2: Insert on Slave, forwarded to Master ===" -ForegroundColor Cyan
$r = $wc.UploadString("http://127.0.0.1:8081/cache/insert","table=test_rep&column=id&value=002&name=Bob&age=30")
Start-Sleep -Seconds 1
$qm = $wc.DownloadString("http://127.0.0.1:8080/cache/get?table=test_rep&column=id&value=002")
$qs = $wc.DownloadString("http://127.0.0.1:8081/cache/get?table=test_rep&column=id&value=002")
$pass2 = ($qm -match '"id":"002"') -and ($qs -match '"id":"002"')
Write-Host "  Master: $qm"
Write-Host "  Slave:  $qs"
Write-Host "  Result: $(if($pass2){'PASS'}else{'FAIL'})" -ForegroundColor $(if($pass2){'Green'}else{'Red'})

# ===== Test 3: Idempotent Insert =====
Write-Host "=== Test 3: Idempotent insert (same key) ===" -ForegroundColor Cyan
$r = $wc.UploadString("http://127.0.0.1:8080/cache/insert","table=test_rep&column=id&value=001&name=AliceV2&age=99")
Start-Sleep -Seconds 1
$q = $wc.DownloadString("http://127.0.0.1:8080/cache/get?table=test_rep&column=id&value=001")
$st = $wc.DownloadString("http://127.0.0.1:8080/cache/stats?table=test_rep")
$pass3 = ($q -match 'AliceV2') -and ($st -match '"totalRows":2')
Write-Host "  Query: $q"
Write-Host "  Stats: $st"
Write-Host "  Result: $(if($pass3){'PASS'}else{'FAIL'})" -ForegroundColor $(if($pass3){'Green'}else{'Red'})

# ===== Test 4: Delete on Slave =====
Write-Host "=== Test 4: Delete on Slave, forwarded to Master ===" -ForegroundColor Cyan
$r = $wc.UploadString("http://127.0.0.1:8081/cache/delete","table=test_rep&column=id&value=002")
Start-Sleep -Seconds 1
$qm = $wc.DownloadString("http://127.0.0.1:8080/cache/get?table=test_rep&column=id&value=002")
$qs = $wc.DownloadString("http://127.0.0.1:8081/cache/get?table=test_rep&column=id&value=002")
$pass4 = ($qm -match '"data":\[\]') -and ($qs -match '"data":\[\]')
Write-Host "  Master: $qm"
Write-Host "  Slave:  $qs"
Write-Host "  Result: $(if($pass4){'PASS'}else{'FAIL'})" -ForegroundColor $(if($pass4){'Green'}else{'Red'})

# ===== Test 5: Master Down, Slave Buffers =====
Write-Host "=== Test 5: Master down, Slave buffers writes ===" -ForegroundColor Cyan
$mpid = (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).OwningProcess | Select-Object -Unique
Stop-Process -Id $mpid -Force
Write-Host "  Master killed"
Start-Sleep -Seconds 1
$r1 = $wc.UploadString("http://127.0.0.1:8081/cache/insert","table=test_rep&column=id&value=003&name=BufferedBoy&age=40")
$r2 = $wc.UploadString("http://127.0.0.1:8081/cache/insert","table=test_rep&column=id&value=004&name=BufferedGirl&age=50")
$pass5 = ($r1 -match '"code":0') -and ($r2 -match '"code":0')
Write-Host "  Insert 003: $r1"
Write-Host "  Insert 004: $r2"
Write-Host "  Result: $(if($pass5){'PASS'}else{'FAIL'})" -ForegroundColor $(if($pass5){'Green'}else{'Red'})

# ===== Test 6: Master Recovery, Pending Flush =====
Write-Host "=== Test 6: Master recovery, pending queue flushes ===" -ForegroundColor Cyan
Start-Process -FilePath "java" -ArgumentList "-Dconfig=config-master.properties","-cp",$cp,"com.browise.database.Main" -RedirectStandardOutput "master-restart.log" -WindowStyle Hidden
if (Wait-Ready "http://127.0.0.1:8080") {
    Write-Host "  Master RESTARTED" -ForegroundColor Green
} else {
    Write-Host "  Master FAILED to restart" -ForegroundColor Red; exit 1
}
$l = $wc.UploadString("http://127.0.0.1:8080/cache/load","table=test_rep")
Write-Host "  Master reload: $l"
Write-Host "  Waiting for pending flush..."
Start-Sleep -Seconds 5
$q3 = $wc.DownloadString("http://127.0.0.1:8080/cache/get?table=test_rep&column=id&value=003")
$q4 = $wc.DownloadString("http://127.0.0.1:8080/cache/get?table=test_rep&column=id&value=004")
$pass6 = ($q3 -match 'BufferedBoy') -and ($q4 -match 'BufferedGirl')
Write-Host "  Master 003: $q3"
Write-Host "  Master 004: $q4"
Write-Host "  Result: $(if($pass6){'PASS'}else{'FAIL'})" -ForegroundColor $(if($pass6){'Green'}else{'Red'})

# ===== Summary =====
$total = 6
$passed = @($pass1,$pass2,$pass3,$pass4,$pass5,$pass6) | Where-Object { $_ -eq $true }
Write-Host ""
Write-Host "========================================" -ForegroundColor White
Write-Host "  Results: $($passed.Count)/$total passed" -ForegroundColor $(if($passed.Count -eq $total){'Green'}else{'Yellow'})
Write-Host "========================================" -ForegroundColor White

# ===== Cleanup =====
Write-Host ""
Write-Host "=== Cleanup ===" -ForegroundColor Yellow
foreach ($p in @(8080,8081,19091)) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($c) { $c.OwningProcess | Select-Object -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }
}
Write-Host "  All processes stopped" -ForegroundColor Green
