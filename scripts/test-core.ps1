param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$compiler = Join-Path $repo '.tools/kotlin/kotlinc/lib'
$jars = @('junit.jar','hamcrest.jar','json.jar') | ForEach-Object { Join-Path $repo ".tools/$_" }
$classPath = $jars -join ';'
$sources = @(
    "$repo/android/app/src/main/java/com/familyledger/app/domain/LedgerRules.kt",
    "$repo/android/app/src/main/java/com/familyledger/app/data/BackupCodec.kt",
    "$repo/android/app/src/test/java/com/familyledger/app/domain/LedgerRulesTest.kt",
    "$repo/android/app/src/test/java/com/familyledger/app/data/BackupCodecTest.kt"
)
$java = Join-Path $JavaHome 'bin/java.exe'
& $java -cp "$compiler/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home (Split-Path -Parent $compiler) -jvm-target 17 -classpath $classPath -d "$repo/.tools/core-tests.jar" @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp "$repo/.tools/core-tests.jar;$compiler/*;$classPath" org.junit.runner.JUnitCore com.familyledger.app.domain.LedgerRulesTest com.familyledger.app.data.BackupCodecTest
exit $LASTEXITCODE
