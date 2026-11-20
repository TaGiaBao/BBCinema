@echo off
rem Starts BBCinema with fresh demo data (profile h2): deletes the H2 database in data\ and runs the app.
rem Close the running app first (Ctrl+C in its window), otherwise the database file is still in use.
cd /d "%~dp0.."
if exist data (
    echo Xoa du lieu demo cu trong thu muc data\ ...
    rmdir /s /q data
    if exist data (
        echo Khong xoa duoc data\. Hay tat ung dung dang chay roi chay lai script nay.
        exit /b 1
    )
)
echo Khoi dong BBCinema voi du lieu mau moi...
call "%~dp0..\run.bat"
