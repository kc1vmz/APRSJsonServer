#!/bin/bash
# APRS JSON Server Installer
# Copyright (c) 2026 John Rokicki KC1VMZ
# 
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
# 
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
# 
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# http://www.kc1vmz.com
# 
APP_UPGRADE=N
HOST_OS=$(uname)
if [ "$HOST_OS" = "Linux" ]; then
  echo "Welcome to the APRS JSON server installer for Linux"
  echo "Copyright (c) 2026 John Rokicki KC1VMZ - Licensed under GPL v3"
  echo "https://www.kc1vmz.com"

  if grep -q APRSJS_INSTALL_DIR /etc/environment; then
    echo "A previous version of APRS JSON server was detected."
    APP_UPGRADE=Y
    read -e -i $APP_UPGRADE -p "Do you wish to upgrade APRS JSON Server?: " APP_UPGRADE
    if [[ "$APP_UPGRADE" =~ ^[Nn]$ ]]; then
      echo "APRS JSON Server installation will now exit."
      exit 1
    fi
  fi

  # default environment variables
  APP_VERSION=1.0.0
  APP_INSTALL_DIR=~/aprsjsonserver
  APP_LOG_DIR=~/aprsjsonserver/logs
  APP_DATA_DIR=~/aprsjsonserver/data
  APP_MGMT_PORT=8180
  APP_API_PORT=8181
  APP_INSTALL_SERVICES=Y

  if [[ "$APP_INSTALL_SERVICES" =~ ^[Yy]$ ]]; then
    read -e -i $APP_VERSION -p "What version of APRS JSON Server?: " APP_VERSION
    if [[ "$APP_UPGRADE" =~ ^[Nn]$ ]]; then
      read -e -i $APP_INSTALL_DIR -p "Where should APRS JSON Server be installed?: " APP_INSTALL_DIR
      read -e -i $APP_LOG_DIR -p "Where should APRS JSON Server log space be located?: " APP_LOG_DIR
      read -e -i $APP_DATA_DIR -p "Where should APRS JSON Server data space be located?: " APP_DATA_DIR
      read -e -i $APP_MGMT_PORT -p "What port should the HTTP UI listen on?: " APP_MGMT_PORT
      read -e -i $APP_API_PORT -p "What port should the HTTP API listen on?: " APP_API_PORT
      read -e -i $APP_INSTALL_SERVICES -p "Do you want APRS JSON Server to be configured as services and started at boot time (Y/n)?: " APP_INSTALL_SERVICES
    else
      APRSJS_INSTALL_DIR=$(grep "^APRSJS_INSTALL_DIR=" /etc/environment | sed 's/^APRSJS_INSTALL_DIR=//' | tr -d '"')
      APP_INSTALL_DIR=$APRSJS_INSTALL_DIR
    fi
  fi

  sudo cp /etc/environment /etc/environment.pre_aprsjs_install

  JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{sub("^$", "0", $2); print $1$2}')
  if [ "$JAVA_VER" -ge 21 ]; then
    # fall through
    echo "Java 21+ verified"
  else
    echo "Java 21 or greater not installed - exiting."
    sudo rm /etc/environment.pre_aprsjs_install
    exit 1
  fi

  APRSJS_INSTALL_DIR=$APP_INSTALL_DIR
  if [[ "$APP_UPGRADE" =~ ^[Nn]$ ]]; then
    echo "APRSJS_INSTALL_DIR=$APRSJS_INSTALL_DIR" | sudo tee -a /etc/environment >  /dev/null
    mkdir $APP_INSTALL_DIR
    sudo mkdir $APP_LOG_DIR
    sudo chown $USER $APP_LOG_DIR
    sudo mkdir $APP_DATA_DIR
    sudo chown $USER $APP_DATA_DIR
  fi

  echo "Retrieving APRS JSON Server and Client binaries"
  cd $APP_INSTALL_DIR
  APP_REMOTE_SRC_URL_ROOT=https://github.com/kc1vmz/aprsjsonserver/releases/download/v$APP_VERSION
  wget -q -O aprs-json-server-$APP_VERSION.jar $APP_REMOTE_SRC_URL_ROOT/aprs-json-server-$APP_VERSION.jar
  wget -q -O aprs-json-client-$APP_VERSION.jar $APP_REMOTE_SRC_URL_ROOT/aprs-json-client-$APP_VERSION.jar
  APP_REMOTE_SRC_URL_ROOT=

  chmod +x aprs-json-server-$APP_VERSION.jar
  chmod +x aprs-json-client-$APP_VERSION.jar

  if [[ "$APP_UPGRADE" =~ ^[Nn]$ ]]; then
    APRSJS_LOG_DIR=$APP_LOG_DIR
    APRSJS_DATA_DIR=$APP_DATA_DIR
    APRSJS_MGMT_PORT=$APP_MGMT_PORT
    APRSJS_API_PORT=$APP_API_PORT
  else
    if [ -n "/etc/systemd/system/aprs-json-server.service+x" ]; then
      APP_INSTALL_SERVICES=Y
    else
      APP_INSTALL_SERVICES=N
    fi
  fi

  if [[ "$APP_INSTALL_SERVICES" =~ ^[Yy]$ ]]; then
    if [[ "$APP_UPGRADE" =~ ^[Nn]$ ]]; then
      echo '[Unit]' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Description=APRS JSON Server' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo '[Service]' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'User='$LOGNAME | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'WorkingDirectory='$APP_INSTALL_DIR | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Type=simple' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Restart=always' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'SuccessExitStatus=143' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'RemainAfterExit=yes' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'ExecStart=java -jar '$APP_INSTALL_DIR'/aprs-json-server-'$APP_VERSION'.jar' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Environment=APRSJS_LOG_DIR='$APRSJS_LOG_DIR | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Environment=APRSJS_DATA_DIR='$APRSJS_DATA_DIR | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Environment=SERVER_PORT='$APP_API_PORT | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Environment=APRSJS_API_PORT='$APP_API_PORT | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Environment=APRSJS_MGMT_PORT='$APP_MGMT_PORT | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'Environment=APRSJS_INSTALL_DIR='$APRSJS_INSTALL_DIR | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo '[Install]' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      echo 'WantedBy=multi-user.target' | sudo tee -a /etc/systemd/system/aprs-json-server.service >  /dev/null
      sudo systemctl daemon-reload
      sudo systemctl enable aprs-json-server
      sudo systemctl start aprs-json-server
      echo "APRS JSON Server running as background service."
    else
      sudo systemctl stop aprs-json-server
      sudo sed -i 's/aprs-json-server-.*.jar/aprs-json-server-'$APP_VERSION'.jar/g' /etc/systemd/system/aprs-json-server.service
      sudo systemctl daemon-reload
      sudo systemctl start aprs-json-server
      echo "APRS JSON Server restarted."
    fi
  else
    echo "Environment variables have been added to /etc/environment.  Make sure to reboot or run 'source /etc/environment' to add them into any new terminal."
    echo "You can run APRS JSON Server using the  'java -jar $APP_INSTALL_DIR/aprs-json-server-$APP_VERSION.jar' command"
  fi

  # cleanup environment
  APP_VERSION=
  APP_INSTALL_DIR=
  APP_LOG_DIR=
  APP_DATA_DIR=
  APP_MGMT_PORT=
  APP_API_PORT=
  APP_INSTALL_SERVICES=
  APP_UPGRADE=
elif [ "$HOST_OS" = "Darwin" ]; then
  echo "This is a Mac Machine - not yet supported"
  exit 1
else
  echo "Unsupported OS -" $HOST_OS
  exit 1
fi

echo "APRS JSON Server installation complete."