param(
    [switch]$Offline
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$logsDir = Join-Path $projectRoot 'var\logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

# Descoberta de JDK 21+.
#
# A ordem importa e o motivo e pratico:
#   1. JAVA_HOME  - se o dev configurou, e isso que ele quer. Respeitar.
#   2. ~/.jdks/*  - onde a IDE baixa JDKs. Caminho estavel, no perfil do usuario.
#   3. JetBrains\*\jbr - ultimo recurso, com curinga: cravar a versao da IDE
#      ("IntelliJ IDEA Community Edition 2025.2") quebra no proximo update.
#
# Cada candidato e TESTADO de verdade rodando "java -version". Antes o script
# pegava o primeiro caminho existente e so depois checava a versao, dando throw
# se fosse Java 17 - mesmo havendo um Java 21 valido logo adiante na lista.
function Get-JavaVersionMajor {
    param([string]$JavaHome)

    $javaExe = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { return 0 }

    # java -version escreve no STDERR por design (comportamento historico da JVM).
    # Com $ErrorActionPreference = 'Stop' no escopo do script, o "2>&1" faria essa
    # saida virar ErrorRecord terminante e derrubar tudo na primeira linha do
    # banner. Por isso relaxamos o EAP so nesta chamada.
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $javaExe -version 2>&1 | Out-String)
    } catch {
        return 0
    } finally {
        $ErrorActionPreference = $previousEap
    }

    if ($output -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += $env:JAVA_HOME }
$javaCandidates += (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -ExpandProperty FullName)
$javaCandidates += (Get-ChildItem 'C:\Program Files\JetBrains' -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'jbr' } |
    Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') })

$javaHome = $javaCandidates |
    Select-Object -Unique |
    Where-Object { (Get-JavaVersionMajor $_) -ge 21 } |
    Select-Object -First 1

if (-not $javaHome) {
    throw @'
Nenhuma JDK 21+ encontrada.

Procurei em (nesta ordem): $env:JAVA_HOME, ~\.jdks\* e C:\Program Files\JetBrains\*\jbr.

Configure o JAVA_HOME apontando para uma JDK 21, por exemplo:
  [Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\caminho\para\jdk-21', 'User')
e abra um terminal novo.
'@
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path

$settingsPath = 'C:\Users\GFURQUI\.m2\settings.xml'
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$cachedMaven = Get-ChildItem 'C:\Users\GFURQUI\.m2\wrapper\dists\apache-maven-*' -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName

if ($cachedMaven) {
    $mavenCommand = $cachedMaven
} elseif (Test-Path $mavenWrapper) {
    $mavenCommand = $mavenWrapper
} else {
    throw 'Nenhum Maven disponível. Nem o wrapper do projeto nem uma distribuição cacheada foram encontrados.'
}

$stdoutLog = Join-Path $logsDir 'run-local.log'
$stderrLog = Join-Path $logsDir 'run-local-err.log'
Remove-Item $stdoutLog, $stderrLog -ErrorAction SilentlyContinue

$mavenArgs = @()
if (Test-Path $settingsPath) {
    $mavenArgs += @('-s', $settingsPath)
}
if ($Offline) {
    $mavenArgs += '-o'
}
# As versoes de thymeleaf e tomcat NAO sao mais passadas por -D aqui: elas
# viraram properties no pom.xml. O motivo e que o override precisa valer tambem
# para a IDE, para "mvn" na mao e para o build do Docker, e nao so para este
# script. Ver o comentario no pom.xml sobre o 403 da curadoria do Artifactory.
$mavenArgs += @(
    '-Dmaven.test.skip=true',
    'spring-boot:run',
    '-Dspring-boot.run.profiles=local'
)

Push-Location $projectRoot
try {
    Write-Host "Projeto: $projectRoot"
    Write-Host "JAVA_HOME: $javaHome"
    Write-Host "Maven: $mavenCommand"
    Write-Host "Logs: $stdoutLog"
    Write-Host "Perfil Spring: local"
    if ($Offline) {
        Write-Host 'Modo Maven: offline'
    }

    & $mavenCommand @mavenArgs 1>> $stdoutLog 2>> $stderrLog
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
