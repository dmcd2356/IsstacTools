# PacketCounter
This is a tool for monitoring the budget status for the ISSTAC project challenge problems.

Currently this consists of the following:
 - measuring the elapsed time (in ms) that the command takes to execute
 - measuring the bytes sent/received in packets through the localhost to the server
 - measuring the total memory used by the server as measured by pmap

You must specify the port number the server is listening on. This will be used for 'tshark'
 to limit the packet capture to this port only (TCP only for now). It is also used by 'lsof'
 for determining the process id corresponding to the process that is listening on that port.
 This pid is used by 'pmap' to determine the memory usage of the server. The commands that
 you specify typically are a bash script that either run a client or simply issue 'curl'
 commands to interface with the server. By pressing the "Run X" button, it will run the
 specified command and take the measurements, displaying the last measurement taken as well
 as a cumulative value.

The commands will be referenced to the directory of the last Load or Save location, which
 loads or saves the current user settings. This makes entering the data easier. If no Load
 or Save has been performed, the command values must be specified as absolute paths.

Note that this assumes that the 'tshark' executable has been installed. If not you will
 need to install it, then add your login name to the tshark group to get priviledge, then
 logout and log back in again. The other executables used (lsof and pmap) should come
 installed in most Linux installations.
 