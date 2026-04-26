$ErrorActionPreference = "Continue"
$mvnCmd = "D:\soft\apache-maven-3.9.5\bin\mvn.cmd"
$projectDir = "D:\work\myMemoryDB"
$logDir = "$projectDir\test-reports"
$cpFile = "$projectDir\target\cp.txt"

$jdks = @(
    @{Name="JDK_8";  Home="D:\Program Files\Java\jdk1.8.0_152"},
    @{Name="JDK_11"; Home="D:\Program Files\Java\jdk-11.0.17"},
    @{Name="JDK_17"; Home="D:\Program Files\Java\jdk-17"},
    @{Name="JDK_21"; Home="D:\Program Files\Java\jdk-21"}
)

if (!(Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

$depCp = Get-Content $cpFile -ErrorAction SilentlyContinue
if (!$depCp) {
    Write-Host "Building classpath..."
    & $mvnCmd -f $projectDir dependency:build-classpath -DincludeScope=test -q "-Dmdep.outputFile=$cpFile" 2>&1 | Out-Null
    $depCp = Get-Content $cpFile
}
$fullCp = "$projectDir\target\classes;$projectDir\target\test-classes;$depCp"

Write-Host "========================================================"
Write-Host "  myMemoryDB Benchmark Suite - $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "========================================================"
Write-Host ""

# Run unit tests for each JDK first
Write-Host "=== PHASE 1: Unit Tests ===" -ForegroundColor Cyan
Write-Host ""
foreach ($jdk in $jdks) {
    $javaExe = "$($jdk.Home)\bin\java.exe"
    if (!(Test-Path $javaExe)) {
        Write-Host "[$($jdk.Name)] SKIP (not found)" -ForegroundColor Yellow
        continue
    }
    $env:JAVA_HOME = $jdk.Home
    $logFile = "$logDir\$($jdk.Name)_unit.txt"
    Write-Host "[$($jdk.Name)] Running unit tests..." -ForegroundColor Cyan -NoNewline
    $output = & $mvnCmd -f $projectDir test -q 2>&1 | Out-String
    $output | Out-File -Encoding UTF8 $logFile
    if ($output -match "Tests run:.*Failures: [1-9]|BUILD FAILURE") {
        Write-Host " FAILED" -ForegroundColor Red
    } else {
        $m = [regex]::Match($output, "Tests run: (\d+), Failures: (\d+), Errors: (\d+)")
        if ($m.Success) {
            Write-Host " PASSED ($($m.Groups[1].Value) tests)" -ForegroundColor Green
        } else {
            Write-Host " PASSED" -ForegroundColor Green
        }
    }
}

Write-Host ""
Write-Host "=== PHASE 2: Benchmarks ===" -ForegroundColor Cyan
Write-Host ""

foreach ($jdk in $jdks) {
    $javaExe = "$($jdk.Home)\bin\java.exe"
    if (!(Test-Path $javaExe)) {
        Write-Host "[$($jdk.Name)] SKIP" -ForegroundColor Yellow
        continue
    }
    $logFile = "$logDir\$($jdk.Name)_benchmark.txt"
    Write-Host "[$($jdk.Name)] Running benchmark..." -ForegroundColor Cyan
    $output = & $javaExe -cp $fullCp com.browise.FullBenchmark 2>&1 | Out-String
    $output | Out-File -Encoding UTF8 $logFile
    
    # Print key lines
    foreach ($line in ($output -split "`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match "JVM:|Java:|CPU|get\(\)|SQL EQ|RANGE|LIKE|thread|HTTP|QPS|DONE|Loaded|Part") {
            Write-Host "  $trimmed"
        }
    }
    Write-Host "  [log saved to $logFile]"
    Write-Host ""
    Start-Sleep -Seconds 2
}

Write-Host ""
Write-Host "=== DONE ===" -ForegroundColor Green
Write-Host "All logs in: $logDir"
