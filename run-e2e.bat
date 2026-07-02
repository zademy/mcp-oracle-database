@echo off
REM ============================================================
REM run-e2e.bat - End-to-end MCP STDIO test runner
REM
REM Prerequisites:
REM   1. src/test/resources/application-e2e.yaml exists and is filled in
REM      (copy application-e2e.example.yaml if you have not already).
REM   2. The server jar is built (this script builds it if needed).
REM
REM What it does:
REM   - Builds the jar (mvn package -DskipTests)
REM   - Runs ONLY McpStdioE2ETest (-Pmcp-e2e), which spawns the jar as a
REM     STDIO subprocess and drives it with the real MCP client SDK.
REM
REM Stack pinned: JDK 26.0.1 (C:\Java\jdk-26.0.1)
REM               Maven 3.8.5 (C:\Maven\apache-maven-3.8.5)
REM ============================================================
setlocal

set "JAVA_HOME=/path/to/jdk/bin/java"
set "MAVEN_HOME=/path/to/maven/bin/mvn"
set "MVN=%MAVEN_HOME%\bin\mvn.cmd"

if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo [ERROR] JDK not found: %JAVA_HOME%
    exit /b 1
)
if not exist "%MVN%" (
    echo [ERROR] Maven not found: %MVN%
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

cd /d "%~dp0"

if not exist "src\test\resources\application-e2e.yaml" (
    echo [ERROR] application-e2e.yaml not found.
    echo         Copy src\test\resources\application-e2e.example.yaml
    echo         to application-e2e.yaml and fill in your credentials.
    exit /b 1
)

echo JAVA_HOME=%JAVA_HOME%
echo MAVEN_HOME=%MAVEN_HOME%
echo.
echo === Building jar (mvn package -DskipTests) ===
call "%MVN%" package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed.
    exit /b %ERRORLEVEL%
)
echo.
echo === Running e2e test (mvn test -Pmcp-e2e) ===
call "%MVN%" test -Pmcp-e2e
exit /b %ERRORLEVEL%
