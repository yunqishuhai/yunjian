param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot 'signing.properties'
$keyPath = Join-Path $projectRoot '.local/signing/yunjian-release.p12'
if ((Test-Path -LiteralPath $configPath) -or (Test-Path -LiteralPath $keyPath)) { throw 'Signing material already exists. Preserve it; do not regenerate an existing app signing key.' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME before creating the release key.' }
$keytoolPath = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytoolPath)) { throw 'JDK keytool.exe was not found.' }
New-Item -ItemType Directory -Force (Split-Path -Parent $keyPath) | Out-Null
$releasePassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:YUNJIAN_KEY_GENERATION_PASSWORD = $releasePassword
try {
    & $keytoolPath -genkeypair -keystore $keyPath -storetype PKCS12 -storepass:env YUNJIAN_KEY_GENERATION_PASSWORD -keypass:env YUNJIAN_KEY_GENERATION_PASSWORD -alias yunjian -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10950 -dname 'CN=Yunjian' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Key creation failed. Inspect the local signing directory before retrying.' }
    $configuration = "storeFile=.local/signing/yunjian-release.p12`nstorePassword=$releasePassword`nkeyAlias=yunjian`nkeyPassword=$releasePassword`n"
    [IO.File]::WriteAllText($configPath,$configuration,[Text.UTF8Encoding]::new($false))
    Write-Output 'Created the private release key and ignored signing.properties. Back up both securely; neither belongs in Git.'
} finally {
    Remove-Item Env:YUNJIAN_KEY_GENERATION_PASSWORD -ErrorAction SilentlyContinue
    $releasePassword = $null
    $configuration = $null
}
