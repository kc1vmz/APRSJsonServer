#!/bin/bash
HOST_OS=$(uname)
if [ "$HOST_OS" = "Linux" ]; then
  echo "Welcome to the APRS JSON Server UNinstaller for Linux"

  # default environment variables
  APP_CONFIRM_UNINSTALL=Y

  read -e -i $APP_CONFIRM_UNINSTALL -p "Do you want to uninstall APRS JSON Server (Y/n)?: " APP_CONFIRM_UNINSTALL

  if [[ "$APP_CONFIRM_UNINSTALL" =~ ^[Yy]$ ]]; then
    # fall through
    echo Starting uninstall of APRS JSON Server
  else
    #any other answer - exit
    exit 1
  fi

  echo Backing up /etc/environment
  sudo cp /etc/environment /etc/environment.pre_aprsjs_uninstall

  echo Removing environment variables from /etc/environment
  sudo sed -i '/APRSJS_INSTALL_DIR/d' /etc/environment

  echo Removing services
  sudo systemctl stop aprs-json-server
  sudo systemctl disable aprs-json-server
  sudo rm /etc/systemd/system/aprs-json-server.service
  sudo systemctl daemon-reload

  if [ -n "${APRSJS_INSTALL_DIR+x}" ]; then
    echo Deleting APRS JSON Server application
    sudo rm aprs-json-server*.jar
    sudo rm aprs-json-client*.jar
    echo Not deleting ${APRSJS_INSTALL_DIR} - delete independently.
  else
    echo APRS JSON Server installation directory unknown - delete independently.
  fi

  if [ -n "${APRSJS_LOG_DIR+x}" ]; then
    echo Not deleting ${APRSJS_LOG_DIR} - delete independently.
  else
    echo APRS JSON Server log directory unknown - delete independently.
  fi

  if [ -n "${APRSJS_DATA_DIR+x}" ]; then
    echo Not deleting ${APRSJS_DATA_DIR} - delete independently.
  else
    echo APRS JSON Server data directory unknown - delete independently.
  fi

  APP_CONFIRM_UNINSTALL=

elif [ "$HOST_OS" = "Darwin" ]; then
  echo "This is a Mac Machine - not yet supported"
  exit 1
else
  echo "Unsupported OS -" $HOST_OS
  exit 1
fi

echo "APRS JSON Server uninstallation complete."
