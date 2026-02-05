@ECHO OFF
SETLOCAL

REM Minimal Gradle wrapper script for this sample project.

SET DIR=%~dp0
SET CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar
SET MAIN_CLASS=org.gradle.wrapper.GradleWrapperMain

IF NOT "%JAVA_HOME%"=="" (
    SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
    SET JAVA_EXE=java.exe
)

"%JAVA_EXE%" -cp "%CLASSPATH%" %MAIN_CLASS% %*

ENDLOCAL
