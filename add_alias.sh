#!/bin/bash
echo "alias dcafs_restart='sudo systemctl restart dcafs'" >> ~/.bashrc
echo "alias dcafs_start='sudo systemctl start dcafs'" >> ~/.bashrc
echo "alias dcafs_stop='sudo systemctl stop dcafs'" >> ~/.bashrc
echo "alias dcafs_log='sudo journalctl -u dcafs.service'" >> ~/.bashrc
echo "alias dcafs_track='sudo journalctl -u dcafs.service -f'" >> ~/.bashrc
echo "alias dcafs='telnet localhost 2323'" >> ~/.bashrc
echo "alias das='telnet localhost 2323'" >> ~/.bashrc