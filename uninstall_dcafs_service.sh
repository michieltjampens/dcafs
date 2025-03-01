#!/bin/bash

# Stop and disable the service
sudo systemctl stop dcafs.service
sudo systemctl disable dcafs.service

# Remove the service definition
sudo rm -f /lib/systemd/system/dcafs.service

# Reload the systemd manager configuration
sudo systemctl daemon-reload

# Reset the failed state of the service
sudo systemctl reset-failed

