param([ValidateSet('debug','check','validation','bootstrap','play')][string]$Mode = 'debug')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (Get-Command java -ErrorAction SilentlyContinue) {
    Push-Location -LiteralPath $projectRoot
    try {
        $tasks = switch ($Mode) {
            'debug' { @(':app:testDebugUnitTest', ':app:assembleDebug') }
            'check' { @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest') }
            'validation' { @(':app:assembleValidation', ':app:bundleValidation', ':app:lintValidation') }
            'play' { @(':app:testDebugUnitTest', ':app:lintRelease', ':app:bundleRelease') }
            'bootstrap' { @(':app:bundleBootstrap') }
        }
        & .\gradlew.bat @tasks --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. See Gradle output.' }
    } finally { Pop-Location }
} else {
    & wsl.exe -d Ubuntu-22.04 --cd $projectRoot -- bash scripts/build.sh $Mode
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. See Gradle output.' }
}
