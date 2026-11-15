@echo off
rd /s/q target
mkdir target
echo Gathering files for upload to Github

copy ..\README.md target
copy ..\installers\linux\aprs_json_server_installer.sh target
copy ..\installers\linux\aprs_json_server_uninstaller.sh target
copy ..\installers\windows\aprs_json_server_installer.bat target
copy ..\server\target\aprs-json-server-1.0.1-exec.jar target\aprs-json-server-1.0.1,jar
copy ..\client\target\aprs-json-client-1.0.1-exec.jar target\aprs-json-client-1.0.1,jar
