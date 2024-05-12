#!/bin/bash
SCRIPT="$(readlink --canonicalize-existing "$0")"
SCRIPT_PATH="$(dirname "$SCRIPT")"

echo "[Unit]" >> dcafs.service
echo "Description=Dcafs Data Acquisition System Service" >> dcafs.service
echo "[Service]" >> dcafs.service
echo "Type=simple" >> dcafs.service
echo "ExecStart=/bin/sh -c 'java -jar $SCRIPT_PATH/dcafs-*.jar'" >> dcafs.service
echo "Restart=on-failure" >> dcafs.service
echo "RestartSec=3s" >> dcafs.service
echo "[Install]" >> dcafs.service
echo "WantedBy=default.target" >> dcafs.service
sudo mv dcafs.service /etc/systemd/user

# Create the alias's
echo "alias dcafs_restart='systemctl --user restart dcafs.service'" >> ~/.bashrc
echo "alias dcafs_status='systemctl --user status dcafs.service'" >> ~/.bashrc
echo "alias dcafs_start='systemctl --user start dcafs.service'" >> ~/.bashrc
echo "alias dcafs_stop='systemctl --user stop dcafs.service'" >> ~/.bashrc
echo "alias dcafs_log='sudo journalctl --user -u dcafs.service'" >> ~/.bashrc
echo "alias dcafs_track='sudo journalctl --user -u dcafs.service -f'" >> ~/.bashrc
echo "alias dcafs='telnet localhost 2323'" >> ~/.bashrc
# Apply those changes
source ~/.bashrc

# Add user to dialout because needed for serialports
sudo adduser $USER dialout

systemctl --user daemon-reload
systemctl --user enable dcafs.service
systemctl --user start dcafs.service