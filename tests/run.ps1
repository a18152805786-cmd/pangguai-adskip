$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$testOut = Join-Path $repo 'build\test-classes'
New-Item -ItemType Directory -Path $testOut -Force | Out-Null
$jdkDir = (Get-ChildItem -LiteralPath 'E:\DevTools\jdk' -Directory | Select-Object -First 1).FullName
& "$jdkDir\bin\javac.exe" -encoding UTF-8 --release 11 -d $testOut `
    "$repo\src\com\zz\pgadskip\LaundryDevice.java" `
    "$repo\src\com\zz\pgadskip\LaundryTransitions.java" `
    "$PSScriptRoot\LaundryTransitionsTest.java"
if ($LASTEXITCODE) { throw 'Transition test compilation failed' }
& "$jdkDir\bin\java.exe" -cp $testOut com.zz.pgadskip.LaundryTransitionsTest
if ($LASTEXITCODE) { throw 'Transition tests failed' }
