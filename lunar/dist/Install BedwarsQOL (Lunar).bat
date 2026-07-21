@echo off
setlocal
REM Cobblify - one-click installer for Lunar Client (Windows).
REM Double-click this file. It copies the Weave loader + the mod into place and
REM prints the exact line to paste into Lunar's JVM Arguments.

set "DIR=%~dp0"
set "AGENT=Weave-Loader-Agent-1.3.3.jar"
set "MOD=Cobblify-Lunar-0.6.0.jar"

echo.
echo   Installing Cobblify for Lunar Client...

if not exist "%DIR%%AGENT%" goto :missing
if not exist "%DIR%%MOD%" goto :missing

if not exist "%USERPROFILE%\.weave\mods" mkdir "%USERPROFILE%\.weave\mods"
copy /Y "%DIR%%AGENT%" "%USERPROFILE%\.weave\%AGENT%" >nul
copy /Y "%DIR%%MOD%"   "%USERPROFILE%\.weave\mods\%MOD%" >nul

echo   Done.
echo.
echo   ------------------------------------------------------------
echo   ONE-TIME setup in Lunar (only needed the first time):
echo.
echo    1. Open Lunar Client -^> Settings (gear icon).
echo    2. Turn ON "Advanced Mode" (toggle next to the search box).
echo    3. In the "JVM Arguments" box, paste EXACTLY this line:
echo.
echo       -javaagent:%USERPROFILE%\.weave\%AGENT%
echo.
echo    4. Save, choose version 1.8.9, and click Play.
echo.
echo   After it loads, set your stats backend once, in chat:
echo       /cobblify statsurl ^<your-backend-url^>
echo   Press Right Shift in-game to open the settings menu.
echo   ------------------------------------------------------------
echo.
echo   (If the path above contains spaces, wrap the path in quotes when pasting.)
echo.
pause
exit /b 0

:missing
echo   ERROR: Couldn't find the bundled jars next to this installer.
echo          Keep this file in the same folder as:
echo            %AGENT%
echo            %MOD%
echo.
pause
exit /b 1
