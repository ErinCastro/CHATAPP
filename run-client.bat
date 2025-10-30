@echo off
setlocal
cd /d %~dp0
set CP=src
javac -encoding UTF-8 -d out src\client\ChatClient.java
if errorlevel 1 goto :e
java -cp out client.ChatClient 127.0.0.1 5050
goto :x
:e
echo Build failed
:x
endlocal
