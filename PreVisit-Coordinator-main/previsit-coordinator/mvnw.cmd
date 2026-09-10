@REM ----------------------------------------------------------------------------
@REM Minimal Maven Wrapper for Windows (self-contained, no Maven install needed).
@REM On first run it downloads Apache Maven into %USERPROFILE%\.m2\wrapper and
@REM reuses it afterwards. Requires a JDK on PATH (this project needs JDK 25).
@REM Usage:  .\mvnw spring-boot:run
@REM ----------------------------------------------------------------------------
@echo off
setlocal enabledelayedexpansion

set "MVN_VERSION=3.9.9"
set "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper"
set "MVN_HOME=%WRAPPER_HOME%\apache-maven-%MVN_VERSION%"
set "MVN_CMD=%MVN_HOME%\bin\mvn.cmd"
set "DIST_URL=https://archive.apache.org/dist/maven/maven-3/%MVN_VERSION%/binaries/apache-maven-%MVN_VERSION%-bin.zip"
set "ZIP=%TEMP%\apache-maven-%MVN_VERSION%-bin.zip"

if not exist "%MVN_CMD%" (
  echo [mvnw] Apache Maven %MVN_VERSION% not found. Downloading it once...
  if not exist "%WRAPPER_HOME%" mkdir "%WRAPPER_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP%' } catch { Write-Host $_; exit 1 }"
  if errorlevel 1 (
    echo [mvnw] Download failed. Check your internet connection or install Maven manually.
    exit /b 1
  )
  echo [mvnw] Extracting...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%WRAPPER_HOME%' } catch { Write-Host $_; exit 1 }"
  if errorlevel 1 (
    echo [mvnw] Extraction failed.
    exit /b 1
  )
)

call "%MVN_CMD%" %*
