@echo off
set "JAR_PATH=%~dp0target\queuectl-1.0.0.jar"
if not exist "%JAR_PATH%" (
    echo [QueueCTL] target\queuectl-1.0.0.jar not found. Auto-building project...
    call mvn clean package -DskipTests
    if not exist "%JAR_PATH%" (
        echo [Error] Maven build failed. Make sure Maven is installed and in your PATH.
        exit /b 1
    )
)
java -jar "%JAR_PATH%" %*
