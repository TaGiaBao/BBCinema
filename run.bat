@echo off
rem Double-click to start BBCinema (profile h2, data kept in data\). The browser opens when the app is ready.
rem For fresh demo data use scripts\reset-demo.cmd instead. Stop the app with Ctrl+C in this window.
title BBCinema
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Khong tim thay Java. Cai JDK 21:  winget install EclipseAdoptium.Temurin.21.JDK
    pause
    exit /b 1
)

netstat -ano | findstr /r /c:":8080 .*LISTENING" >nul
if not errorlevel 1 (
    echo Cong 8080 dang duoc dung: BBCinema co the dang chay o cua so khac.
    echo Mo trinh duyet: http://localhost:8080
    start "" http://localhost:8080
    pause
    exit /b 0
)

echo Dang khoi dong BBCinema... trinh duyet se tu mo khi san sang (khoang 20-40 giay).
rem Waits in the background until the site answers, then opens the browser.
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "for ($i=0; $i -lt 120; $i++) { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://localhost:8080/ | Out-Null; Start-Process 'http://localhost:8080'; break } catch { Start-Sleep 2 } }"

call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=h2
echo.
echo BBCinema da dung.
pause