@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
"%JAVA_HOME%\bin\java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*