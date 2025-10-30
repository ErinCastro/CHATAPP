@echo off
setlocal
cd /d %~dp0
set CP=src
javac -encoding UTF-8 -d out src\server\ChatServer.java
if errorlevel 1 goto :e
java -cp out server.ChatServer 5050
goto :x
:e
echo Build failed
:x
endlocal
