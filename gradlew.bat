@echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME is not set and no 'java' command could be found
goto fail
:execute
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% -Dorg.gradle.appname=Gradle -classpath "" -jar "%APP_HOME%/gradle/wrapper/gradle-wrapper.jar" %*
:end
