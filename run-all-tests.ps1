$ErrorActionPreference = "Continue"

$mvnCmd = "D:\soft\apache-maven-3.9.5\bin\mvn.cmd"
$projectDir = "D:\work\myMemoryDB"
$logDir = "$projectDir\test-reports"

$jdks = @(
    @{Name="JDK 8";  Home="D:\Program Files\Java\jdk1.8.0_152"},
    @{Name="JDK 11"; Home="D:\Program Files\Java\jdk-11.0.17"},
    @{Name="JDK 17"; Home="D:\Program Files\Java\jdk-17"},
    @{Name="JDK 21"; Home="D:\Program Files\Java\jdk-21"}
)

if (!(Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "  myMemoryDB Cross-JDK Test Suite" -ForegroundColor Magenta
Write-Host "  Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta

$results = @{}
$benchResults = @{}

foreach ($jdk in $jdks) {
    $javaHome = $jdk.Home
    $javaExe = "$javaHome\bin\java.exe"
    $label = $jdk.Name

    if (!(Test-Path $javaExe)) {
        Write-Host "[$label] SKIP: $javaExe not found" -ForegroundColor Yellow
        $results[$label] = "SKIP"
        $benchResults[$label] = "SKIP"
        continue
    }

    $ver = & $javaExe -version 2>&1 | Out-String
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  $label" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host $ver.Trim()

    # Step 1: Compile with this JDK
    Write-Host ""
    Write-Host "[$label] Compiling..." -ForegroundColor Cyan
    $env:JAVA_HOME = $javaHome
    $compileLog = "$logDir\$($label -replace ' ','_')_compile.txt"
    $compileOutput = & $mvnCmd -f $projectDir clean compile test-compile -q 2>&1 | Out-String
    $compileOutput | Out-File -Encoding UTF8 $compileLog
    if ($compileOutput -match "ERROR|FAILURE") {
        Write-Host "[$label] COMPILE FAILED" -ForegroundColor Red
        Write-Host $compileOutput
        $results[$label] = "COMPILE_FAIL"
        $benchResults[$label] = "COMPILE_FAIL"
        continue
    }
    Write-Host "[$label] Compile OK" -ForegroundColor Green

    # Step 2: Unit tests
    Write-Host ""
    Write-Host "[$label] Running unit tests..." -ForegroundColor Cyan
    $unitLog = "$logDir\$($label -replace ' ','_')_unit.txt"
    $unitOutput = & $mvnCmd -f $projectDir test -q 2>&1 | Out-String
    $unitOutput | Out-File -Encoding UTF8 $unitLog
    Write-Host $unitOutput
    if ($unitOutput -match "Tests run:.*Failures: [1-9]|ERROR|FAILURE") {
        $results[$label] = "FAIL"
        Write-Host "[$label] Unit tests FAILED" -ForegroundColor Red
    } else {
        $results[$label] = "PASS"
        Write-Host "[$label] Unit tests PASSED" -ForegroundColor Green
    }

    # Extract test counts
    $testMatch = [regex]::Match($unitOutput, "Tests run: (\d+), Failures: (\d+), Errors: (\d+)")
    if ($testMatch.Success) {
        $results["$label`_count"] = "run=$($testMatch.Groups[1].Value) fail=$($testMatch.Groups[2].Value) err=$($testMatch.Groups[3].Value)"
    }

    # Step 3: Benchmark
    Write-Host ""
    Write-Host "[$label] Running benchmark..." -ForegroundColor Cyan

    $benchLog = "$logDir\$($label -replace ' ','_')_benchmark.txt"

    $depCpFile = "$projectDir\target\cp.txt"
    & $mvnCmd -f $projectDir dependency:build-classpath -DincludeScope=test -q "-Dmdep.outputFile=$depCpFile" 2>&1 | Out-Null
    $depCp = Get-Content $depCpFile -ErrorAction SilentlyContinue
    $fullCp = "$projectDir\target\classes;$projectDir\target\test-classes;$depCp"

    $benchOutput = & "$javaHome\bin\java.exe" -cp $fullCp com.browise.FullBenchmark 2>&1 | Out-String
    $benchOutput | Out-File -Encoding UTF8 $benchLog
    Write-Host $benchOutput

    if ($benchOutput -match "NoClassDefFoundError|ClassNotFoundException") {
        $benchResults[$label] = "FAIL"
        Write-Host "[$label] Benchmark FAILED" -ForegroundColor Red
    } else {
        $benchResults[$label] = "PASS"
        Write-Host "[$label] Benchmark PASSED" -ForegroundColor Green

        # Extract QPS numbers
        foreach ($line in ($benchOutput -split "`n")) {
            if ($line -match "QPS") {
                Write-Host "  $($line.Trim())" -ForegroundColor White
            }
        }
    }
}

Write-Host ""
Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "  SUMMARY" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta
Write-Host ""
Write-Host ("{0,-8} {1,-20} {2,-10}" -f "JDK", "Unit Tests", "Benchmark")
Write-Host ("{0,-8} {1,-20} {2,-10}" -f "---", "----------", "----------")
foreach ($jdk in $jdks) {
    $n = $jdk.Name
    $u = $results[$n]
    $b = $benchResults[$n]
    $detail = if ($results["$n`_count"]) { "$u ($($results["$n`_count"]))" } else { $u }
    $uColor = if ($u -eq "PASS") { "Green" } elseif ($u -eq "SKIP") { "Yellow" } else { "Red" }
    $bColor = if ($b -eq "PASS") { "Green" } elseif ($b -eq "SKIP") { "Yellow" } else { "Red" }
    Write-Host ("{0,-8} " -f $n) -NoNewline
    Write-Host ("{0,-20} " -f $detail) -ForegroundColor $uColor -NoNewline
    Write-Host ("{0,-10}" -f $b) -ForegroundColor $bColor
}

Write-Host ""
Write-Host "Detailed logs: $logDir"
Write-Host ""
