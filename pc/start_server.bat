@echo off
cd /d "%~dp0"
echo KeyBridge PC 端启动中...
python keybridge_server.py %*
pause
