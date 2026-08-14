@echo off
REM Convenience shim so the runner can be started from any shell:
REM
REM   scripts\claude-task.cmd
REM   scripts\claude-task.cmd -Status
REM   scripts\claude-task.cmd -SimulateLimit
REM   scripts\claude-task.cmd -SelfTest
REM
REM This repository has no package.json at the root (the Maven build is the root
REM build, and web-ui does not exist until Phase 1), so there is no
REM `npm run claude:task` to hang this off. This is the equivalent entry point.

setlocal
set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%claude-task-runner.ps1" %*
exit /b %ERRORLEVEL%
