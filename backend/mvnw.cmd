@REM Maven Wrapper startup script for Windows
@echo off
setlocal

if "%JAVA_HOME%"=="" (
  if exist "%USERPROFILE%\.antigravity\extensions\redhat.java-1.53.0-win32-x64\jre\21.0.10-win32-x86_64\bin\java.exe" (
    set JAVA_EXE=%USERPROFILE%\.antigravity\extensions\redhat.java-1.53.0-win32-x64\jre\21.0.10-win32-x86_64\bin\java.exe
  ) else (
    set JAVA_EXE=java
  )
) else (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
)

set WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar
set WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain
set MAVEN_PROJECTBASEDIR=%~dp0.

"%JAVA_EXE%" -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" -classpath "%WRAPPER_JAR%" %WRAPPER_LAUNCHER% %*
