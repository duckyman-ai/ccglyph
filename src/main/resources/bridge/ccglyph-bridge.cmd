@echo off
REM CCGlyph status bridge (Windows) — invoked by Claude Code hooks & the statusLine command.
REM Usage: ccglyph-bridge.cmd ^<status^|hook^> ^<sessionId^> ^<stateDir^>
REM Always exits 0 and writes nothing to stdout, so it never blocks Claude.
setlocal enableextensions

set "MODE=%~1"
set "SID=%~2"
set "DIR=%~3"

if "%SID%"=="" exit /b 0
if "%DIR%"=="" exit /b 0

set "SDIR=%DIR%\%SID%"
if not exist "%SDIR%" mkdir "%SDIR%" >nul 2>&1

if /i "%MODE%"=="status" (
  copy /y /b con "%SDIR%\status.json.tmp" >nul 2>&1
  move /y "%SDIR%\status.json.tmp" "%SDIR%\status.json" >nul 2>&1
  REM Pass the captured status JSON to the user's own statusLine command (CCGLYPH_USER_STATUSLINE) so it
  REM keeps rendering in the terminal instead of being swallowed. The chip still works (same JSON, two views).
  if not "%CCGLYPH_USER_STATUSLINE%"=="" (
    cmd /c "%CCGLYPH_USER_STATUSLINE%" < "%SDIR%\status.json" 2>nul
  )
) else if /i "%MODE%"=="hook" (
  REM Append each stdin line (JSON payloads are single-line) to the event log.
  findstr /r "." >> "%SDIR%\events.jsonl"
)

exit /b 0
