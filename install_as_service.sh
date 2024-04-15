#!/bin/bash
SERVICE_NAME='dcafs.service'
SCRIPT="$(readlink --canonicalize-existing "$0")"
SCRIPT_PATH="$(dirname "$SCRIPT")"

SERVICE_PATH="/lib/systemd/system"
SERVICE_FILE=$SERVICE_PATH/$SERVICE_NAME

sudo cat > $SERVICE_FILE << EOF
[Unit]
Description=Dcafs Data Acquisition System Service
After=multi-user.target
[Service]
Type=simple
ExecStart=/bin/sh -c 'java -jar $SCRIPT_PATH/dcafs-*.jar'
Restart=on-failure
RestartSec=3s
[Install]
WantedBy=multi-user.target
EOF

chmod 644 $SERVICE_FILE

systemctl daemon-reload
systemctl enable $SERVICE_NAME
systemctl start $SERVICE_NAME
systemctl status $SERVICE_NAME


echo "alias dcafs_restart='sudo systemctl restart dcafs'" >> ~/.bashrc
echo "alias dcafs_start='sudo systemctl start dcafs'" >> ~/.bashrc
echo "alias dcafs_stop='sudo systemctl stop dcafs'" >> ~/.bashrc
echo "alias dcafs_log='sudo journalctl -u dcafs.service'" >> ~/.bashrc
echo "alias dcafs_track='sudo journalctl -u dcafs.service -f'" >> ~/.bashrc
echo "alias dcafs='telnet localhost'" >> ~/.bashrc

