param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or newer (Gradle 9.1 also supports JDK 25).' }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.local/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.local/android'
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
$localGradle = Join-Path $projectRoot '.local/gradle/gradle-9.1.0/bin/gradle.bat'
if (Test-Path -LiteralPath $localGradle) { & $localGradle @Tasks --console plain --max-workers 2 }
else { & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console plain --max-workers 2 }
exit $LASTEXITCODE
