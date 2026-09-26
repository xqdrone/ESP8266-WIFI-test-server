# ============================================================
#  Optional: build a runnable jar offline (Windows PowerShell)
#
#  Why this script exists:
#    When there is no network access, the local Maven repository often has
#    only .pom stubs for maven-jar-plugin's own dependencies (maven-archiver,
#    plexus-archiver, ...), so `mvn -o package` cannot run.
#    This script uses the JDK's own java/jar tool instead and produces the
#    same runnable layout:
#        target\esp8266-tcp-server.jar   (executable)
#        target\lib\*.jar                (dependencies)
#
#  Usage (from the project root):
#        powershell -ExecutionPolicy Bypass -File .\package.ps1
#    Then run:
#        java -jar target\esp8266-tcp-server.jar
#
#  NOTE: this file is intentionally ASCII-only, because Windows PowerShell 5.1
#  decodes BOM-less .ps1 files as ANSI.
# ============================================================

$ErrorActionPreference = 'Stop'
$dir       = Split-Path -Parent $MyInvocation.MyCommand.Path
$target    = Join-Path $dir 'target'
$lib       = Join-Path $target 'lib'
$pom       = Join-Path $dir 'pom.xml'
$mainClass = 'com.example.esp8266.Esp8266TcpServerApplication'

function Invoke-Maven([string[]]$mavenArgs) {
    & mvn -o -B -q -f $pom @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: mvn $($mavenArgs -join ' ')" }
}

Write-Host '[1/4] Compiling...' -ForegroundColor Cyan
Invoke-Maven @('compile')

Write-Host '[2/4] Resolving runtime dependencies...' -ForegroundColor Cyan
$cpFile = Join-Path $target 'classpath-abs.txt'
Invoke-Maven @('dependency:build-classpath', "-Dmdep.outputFile=$cpFile")

Write-Host '[3/4] Copying dependencies to target\lib ...' -ForegroundColor Cyan
if (Test-Path $lib) { Remove-Item $lib -Recurse -Force }
New-Item -ItemType Directory -Path $lib -Force | Out-Null
Invoke-Maven @('dependency:copy-dependencies', "-DoutputDirectory=$lib", '-DincludeScope=runtime')

Write-Host '[4/4] Creating executable jar ...' -ForegroundColor Cyan

# The manifest only points at the tiny Launcher class, which loads lib\*.jar
# itself at runtime. This avoids the 72-bytes-per-line MANIFEST limit that makes
# long Class-Path values painful (the jar tool rejects/merges them).
$manifest = Join-Path $target 'MANIFEST.MF'
$jars = @(Get-ChildItem -Path $lib -Filter '*.jar' | Sort-Object Name)
if ($jars.Count -eq 0) { throw "No dependency jars found in $lib" }

$manifestLines = @(
    'Manifest-Version: 1.0'
    'Main-Class: com.example.esp8266.Launcher'
    ''
)

# Plain ASCII, CRLF line endings, no BOM (a BOM would break manifest parsing).
$manifestText = [string]::Join("`r`n", $manifestLines)
[System.IO.File]::WriteAllText($manifest, $manifestText, (New-Object System.Text.ASCIIEncoding))

$jarExe = 'jar'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jar.exe'))) {
    $jarExe = Join-Path $env:JAVA_HOME 'bin\jar.exe'
}

$outJar = Join-Path $target 'esp8266-tcp-server.jar'
if (Test-Path $outJar) { Remove-Item $outJar -Force }

& $jarExe --create --file $outJar --manifest $manifest -C (Join-Path $target 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar packaging failed' }

Remove-Item $manifest -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '[DONE] Artifacts:' -ForegroundColor Green
Write-Host "    $outJar"
Write-Host "    $lib   ($($jars.Count) dependency jars)"
Write-Host ''
Write-Host 'Run with:  java -jar target\esp8266-tcp-server.jar' -ForegroundColor Yellow
