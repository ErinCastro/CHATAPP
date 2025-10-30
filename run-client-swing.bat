@echo off
setlocal
cd /d %~dp0
mkdir out 2>nul

javac -encoding UTF-8 -d out src\clientsw\ChatSwingApp.java
if errorlevel 1 (echo Build failed & exit /b 1)

java -cp out clientsw.ChatSwingApp
endlocal
