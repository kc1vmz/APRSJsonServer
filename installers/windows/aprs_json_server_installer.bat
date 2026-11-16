@echo off
rem APRS JSON Server Installer
rem Copyright (c) 2026 John Rokicki KC1VMZ
rem 
rem This program is free software: you can redistribute it and/or modify
rem it under the terms of the GNU General Public License as published by
rem the Free Software Foundation, either version 3 of the License, or
rem (at your option) any later version.
rem 
rem This program is distributed in the hope that it will be useful,
rem but WITHOUT ANY WARRANTY; without even the implied warranty of
rem MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
rem GNU General Public License for more details.
rem 
rem You should have received a copy of the GNU General Public License
rem along with this program.  If not, see <https://www.gnu.org/licenses/>.
rem
rem http://www.kc1vmz.com
rem 

setlocal enabledelayedexpansion

echo Welcome to the APRS JSON Server installer for Microsoft Windows.
echo Copyright (c) 2026 John Rokicki KC1VMZ - Licensed under GPL v3
echo https://www.kc1vmz.com
echo.
echo This installer expects pre-requisite software to be installed - Java 21+
echo Please install these pre-requisites prior to installation.
echo Java can be found at https://www.java.com/en/download/manual.jsp
echo.
pause
SET APP_INSTALL_DIR=%USERPROFILE%\APRSJSONServer
SET APP_LOG_DIR=%TEMP%\APRSJSONServer
SET APP_DATA_DIR=%APP_INSTALL_DIR%\data
SET APP_VERSION=1.0.3
SET APP_MGMT_PORT=8180
SET APP_API_PORT=8181

REM Prompt the user for input
SET /P "APP_VERSION=What version of APRS JSON Server? (Default: %APP_VERSION%): "
SET /P "APP_INSTALL_DIR=Where should APRS JSON Server be installed? (Default: %APP_INSTALL_DIR%): "
SET /P "APP_LOG_DIR=Where should APRS JSON Server log space be located? (Default: %APP_LOG_DIR%): "
SET /P "APP_DATA_DIR=Where should APRS JSON Server data space be located? (Default: %APP_DATA_DIR%): "
SET /P "APP_MGMT_PORT=What port should the HTTP UI listen on? (Default: %APP_MGMT_PORT%): "
SET /P "APP_API_PORT=What port should the HTTP API listen on? (Default: %APP_API_PORT%): "

pushd .

echo Checking for pre-requisite - Java 21
rem Step 1: Check if Java is installed at all
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java is not installed or not added to your system PATH.
    goto done
)
rem Step 2: Extract the major version number
rem This runs 'java -version', grabs the first line, and parses the version string.
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "java_version=%%g"
)
rem Remove surrounding quotes from the version string (e.g., "17.0.1" -> 17.0.1)
set "java_version=%java_version:"=%"
rem Extract the main major version number before the first dot
for /f "delims=." %%v in ("!java_version!") do (
    set "major_version=%%v"
)
rem Handle legacy naming convention (e.g., Java 8 outputs "1.8", so major is 1)
if "!major_version!"=="1" (
    for /f "tokens=2 delims=." %%v in ("!java_version!") do (
        set "major_version=%%v"
    )
)
rem Step 3: Validate if version is 21 or greater
if !major_version! lss 21 (
    echo [ERROR] Java version is too old. Required: 21 or greater.
    goto done
)

echo Starting installation

echo Creating directories
mkdir %APP_INSTALL_DIR%
mkdir %APP_LOG_DIR%
mkdir %APP_DATA_DIR%

cd %APP_INSTALL_DIR%

echo Downloading built components for version %APP_VERSION% from Github
SET APP_REMOTE_SRC_URL_ROOT=https://github.com/kc1vmz/aprsjsonserver/releases/download/v%APP_VERSION%
curl -L -o aprs-json-server-%APP_VERSION%.jar %APP_REMOTE_SRC_URL_ROOT%/aprs-json-server-%APP_VERSION%.jar
curl -L -o aprs-json-client-%APP_VERSION%.jar %APP_REMOTE_SRC_URL_ROOT%/aprs-json-client-%APP_VERSION%.jar
SET APP_REMOTE_SRC_URL_ROOT=

echo Creating startup script
echo @ECHO OFF> aprsjsonserver_start.bat
echo pushd .>> aprsjsonserver_start.bat
echo cd %APP_INSTALL_DIR%>> aprsjsonserver_start.bat
echo REM Set Environment Variables>> aprsjsonserver_start.bat
echo SET APRSJS_DATA_DIR=%APP_DATA_DIR%>> aprsjsonserver_start.bat
echo SET APRSJS_CONFIG_FILE=%APP_DATA_DIR%\configuration.json>> aprsjsonserver_start.bat
echo SET APRSJS_LOG_DIR=%APP_LOG_DIR%>> aprsjsonserver_start.bat
echo SET SERVER_PORT=%APP_MGMT_PORT%>> aprsjsonserver_start.bat
echo SET APRSJS_API_HTTP_PORT=%APP_API_PORT%>> aprsjsonserver_start.bat
echo SET APRSJS_INSTALL_DIR=%APP_INSTALL_DIR%>> aprsjsonserver_start.bat
echo REM Start Processes>> aprsjsonserver_start.bat
echo start java -jar aprs-json-server-%APP_VERSION%.jar>> aprsjsonserver_start.bat
echo echo APRS JSON Server started>> aprsjsonserver_start.bat
echo popd>> aprsjsonserver_start.bat

echo Finishing up
popd

SET APP_INSTALL_DIR=
SET APP_LOG_DIR=
SET APP_VERSION=
SET APP_DATA_DIR=

echo.
echo Installation complete.
echo.
echo Run 'aprsjsonserver_start.bat' to start APRS JSON Server.
exit /b 0

:done
exit /b 1