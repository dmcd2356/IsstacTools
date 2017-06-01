/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package packetcounter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author dmcd2356
 */
public class MainFrame extends javax.swing.JFrame {

    final static private String newLine = System.getProperty("line.separator");
    
    /**
     * Creates new form MainFrame
     */
    public MainFrame() {
        initComponents();

        // init to indicate we are not running yet
        lastline = 0;
        serverport = "";
        serverpid = -1;
        tsharkpid = -1;
        counter = 0;
        runpath = "";

        classProperties = new ClassProperties();
        
        this.standardOut = System.out;
        this.standardErr = System.err;         

        // this timer is for measuring the elapsed time of the commands in msec
        elapsedStart = -1;

        // this timer is used as a timestamp reference for debug messages only.
        // we start it when the program starts and never stop it.
        tstampTimer = new ElapsedTimer(null);
        tstampTimer.reset();
        tstampTimer.start();

        // create debugger window
        debug = new DebugMessage(statusTextPane, tstampTimer);

        // this creates a command launcher on a separate thread
        threadLauncher = new ThreadLauncher();
        threadLauncher.init(new StandardTermination());

        // this creates a command launcher that runs from the current thread
        // (this one will not produce any return information)
        commandLauncher = new CommandLauncher(false);

        // init results fields
        resetStats();

        // init the start buttons to disabled until they have a corresponding command
        startCmd1Button.setEnabled(false);
        startCmd2Button.setEnabled(false);
        startCmd3Button.setEnabled(false);
        
        // init config info
        configInfo = new ConfigInfo();
        setGUIFromConfig();

        // check if we previously loaded a config file
        String lastConfig = classProperties.getPropertiesItem(ClassProperties.PropertyTags.LastConfigFile);
        if (lastConfig != null) {
            loadSettings (lastConfig);
            // set the initial load/save file selection to the specified path
            this.configFileChooser.setSelectedFile(new File(lastConfig));
        }
            
        // verify tshark has been installed
        String[] command2 = { "tshark", "-v" };
        int rc = commandLauncher.start(command2, null);
        if (rc < 0) {
            JOptionPane.showMessageDialog(null,
                "The tool 'tshark' is not installed." + newLine + newLine +
                "You can install it using:" + newLine +
                "   sudo apt-get install tshark" + newLine + newLine +
                "You will then have to add your user name to the wireshark group by entering:" + newLine +
                "   sudo adduser $USER wireshark" + newLine + newLine +
                "and then logging out and logging back in again.",
                "Missing tool", JOptionPane.ERROR_MESSAGE);
        }
        else {
            // else, start tshark capture
            startTshark();
        }
    }

    /**
     * This performs the actions to take upon completion of the thread command.
     */
    private class StandardTermination implements ThreadLauncher.ThreadAction {

        @Override
        public void allcompleted(ThreadLauncher.ThreadInfo threadInfo) {

            // restore the stdout and stderr
            System.out.flush();
            System.err.flush();
            System.setOut(standardOut);
            System.setErr(standardErr);
            //debug.print(DebugMessage.StatusType.JobCompleted, "Job queue empty");
        }

        @Override
        public void jobprestart(ThreadLauncher.ThreadInfo threadInfo) {
        }

        @Override
        public void jobstarted(ThreadLauncher.ThreadInfo threadInfo) {
            debug.print(DebugMessage.StatusType.JobStarted, "Job: " + threadInfo.jobname + " (pid " + threadInfo.pid + ") started");
            if (threadInfo.jobname.equals("tshark")) {
                // indicate tshark has started
                tsharkpid = threadInfo.pid;
            }
        }
        
        @Override
        public void jobfinished(ThreadLauncher.ThreadInfo threadInfo) {
            debug.print(DebugMessage.StatusType.JobCompleted, "Job: " + threadInfo.jobname + " (pid " + threadInfo.pid + 
                        ") completed with exitcode = " + threadInfo.exitcode);
            
            // get copy of command output and append to output area
            String message = threadInfo.stdout.getText();
            outputTextArea.setText(outputTextArea.getText() + message);

            try {
                switch (threadInfo.jobname) {
                    case "pmap":
                        // check for error
                        if (threadInfo.exitcode != 0) {
                            debug.print(DebugMessage.StatusType.Error,
                                "pmap failure: " + threadInfo.exitcode);
                            return;
                        }

                        // read and display the response
                        readPeakmem(message);
                
                        // now start the tshark again
                        startTshark();
                        break;
                    case "tshark":
                        // check for error
                        if (threadInfo.exitcode != 0) {
                            debug.print(DebugMessage.StatusType.Error,
                                "tshark failure: make sure you have added permission (must re-login after): sudo adduser $USER wireshark");
                            return;
                        }

                        // read and display the response
                        readTshark(message);
                    
                        // re-start the next command (run peakmem if we have a valid pid)
                        if (serverpid >= 0)
                            startPeakmem();
                        else
                            startTshark();
                        break;
                    default:
                        break;
                }
            } catch (IOException ex) {
                debug.print(DebugMessage.StatusType.Error, ex.getMessage());
            }
        }
    }

    /**
     * resets all of the statistic parameters and GUI display
     */
    private void resetStats () {
        // reset the timer
        elapsedStart = -1;

        // reset the peak memory and packet cumulative info
        bytesfrom = 0;
        bytesto = 0;
        peakmem = 0;
        
        // reset the GUI
        this.elapsedTextField.setText("0 ms");
        this.sentTextField.setText("0");
        this.rcvdTextField.setText("0");
        this.peakmemTextField.setText("0");
        this.totalSentTextField.setText("0");
        this.totalRcvdTextField.setText("0");
        this.deltaPeakmemTextField.setText("0");
        
        // reset the output text area
        outputTextArea.setText("");
    }
    
    /**
     * run tshark with the current selections
     */
    private void startTshark () {
        if (tsharkpid >= 0) {
            debug.print(DebugMessage.StatusType.Info, "tshark already running. pid = " + tsharkpid);
            return;
        }
        int verbose = 1;
        if (verboseCheckBox.isSelected())
            verbose = 2;
        String type;
        if (tcpRadioButton.isSelected())
            type = "tcp";
        else
            type = "udp";
        
        threadLauncher.init(new StandardTermination());

        debug.print(DebugMessage.StatusType.Info, "Starting tshark capture of " + type + " packets on port " + serverport);
        switch (verbose) {
            default:
            case 0 :
                // quiet mode will only print the summary info
                String[] command0 = { "tshark", "-z", "conv," + type,
                                               "-p",
                                               "-q",
                                               "-i", "lo",
                                               type + " port " + serverport
                                   };
                threadLauncher.launch(command0, null, "tshark");
                break;
                
            case 1:
                // this will additionally print 1 line of info for each packet
                String[] command1 = { "tshark", "-z", "conv," + type,
                                               "-p",
                                               "-i", "lo",
                                               type + " port " + serverport
                                   };
                threadLauncher.launch(command1, null, "tshark");
                break;
                
            case 2:
                // this will print extensive detail for each packet
                String[] command2 = { "tshark", "-z", "conv," + type,
                                               "-p",
                                               "-V",
                                               "-i", "lo",
                                               type + " port " + serverport
                                   };
                threadLauncher.launch(command2, null, "tshark");
                break;
        }
        debug.print(DebugMessage.StatusType.Info, "tshark command in process");
    }

    /**
     * stops the tshark command so we can get its current measurement info.
     */
    private void stopTshark () {
        // if tshark is running, stop it
        ThreadLauncher.ThreadInfo threadInfo = threadLauncher.stopAll();
        if (threadInfo != null && threadInfo.pid >= 0) {
            debug.print(DebugMessage.StatusType.Info, "Killing tshark job " + threadInfo.jobid + ": pid " + threadInfo.pid);
            String[] command = { "kill", "-15", threadInfo.pid.toString() };
            commandLauncher.start(command, null);
        }

        // indicate tshark no longer running
        tsharkpid = -1;
    }

    /**
     * parses the response of the tshark command to extract the bytes of data
     * sent to/rcvd from the server.
     * 
     * @param message - the command response string
     * @throws IOException 
     */
    private void readTshark (String message) throws IOException {
        if (message == null || message.isEmpty())
            return;
        
        // parse tshark output
        // init current list entries to indicate no updates to any of them
        boolean bStartFound = false;
        BufferedReader reader = new BufferedReader(new StringReader(message));
        int lineix;
        String line;
        for (lineix = 0; (line = reader.readLine()) != null; lineix++) {
            // skip all data until we get to the summary info
            String type = "UDP";
            if (tcpRadioButton.isSelected())
                type = "TCP";
            if (line.startsWith(type + " Conversations")) {
                bStartFound = true;
                continue;
            }
            else if (!bStartFound) {
                continue;
            }
            debug.print(DebugMessage.StatusType.Results, line);
                        
            String[] words = line.trim().split("[ ]+");
            if (words.length != 11)
                continue; // invalid entry

            String portsrc = words[0];
            String portdst = words[2];
            //String pktsrcvd = words[3];
            String bytercvd = words[4];
            //String pktssent = words[5];
            String bytesent = words[6];
            //String pktstotal = words[7];
            //String bytetotal = words[8];

            if (!portsrc.startsWith("127.0.0.1") || !portdst.startsWith("127.0.0.1"))
                continue;
            int offset = portsrc.indexOf(":");
            if (offset >= 0)
                portsrc = portsrc.substring(offset+1);

            // get the latest results
            Integer latestFrom;
            Integer latestTo;
            if (portsrc.equals(serverport)) {
                latestTo   = Integer.parseInt(bytercvd);
                latestFrom = Integer.parseInt(bytesent);
            }
            else {
                latestFrom = Integer.parseInt(bytercvd);
                latestTo   = Integer.parseInt(bytesent);
            }
                        
            // keep track of the cumulative amount
            bytesfrom += latestFrom;
            bytesto   += latestTo;

            // display the results
            sentTextField.setText(latestTo.toString());
            rcvdTextField.setText(latestFrom.toString());
            totalSentTextField.setText(bytesto.toString());
            totalRcvdTextField.setText(bytesfrom.toString());
        }
                    
        // save last line read to skip on next pass
        lastline = lineix;
    }
    
    /**
     * run pmap to get the current peak memory usage for the given process
     */
    private void startPeakmem () {
        if (serverpid >= 0) {
            threadLauncher.init(new StandardTermination());

            debug.print(DebugMessage.StatusType.Info, "pmap command in process on pid " + serverpid);
            String[] command = { "pmap", "-x", Long.toString(serverpid)
                                };
            threadLauncher.launch(command, null, "pmap");
        }
    }

    /**
     * parses the response of the pmap command to extract the memory used
     * by the server.
     * 
     * @param message - the command response string
     * @throws IOException 
     */
    private void readPeakmem (String message) throws IOException {
        if (message == null || message.isEmpty())
            return;
        
        BufferedReader reader = new BufferedReader(new StringReader(message));
        int lineix;
        String line;
        for (lineix = 0; (line = reader.readLine()) != null; lineix++) {
            if (line.startsWith("total kB")) {
                String[] words = line.trim().split("[ ]+");
                if (words.length > 3) {
                    String peak = words[3];
                    int latestpeak = Integer.parseInt(peak);
                    int deltaPeak = latestpeak - peakmem;
                    peakmem = latestpeak;

                    debug.print(DebugMessage.StatusType.Results, "peakmem = " + peak);
                    peakmemTextField.setText(peak);
                    deltaPeakmemTextField.setText(Integer.toString(deltaPeak));
                }
            }
        }
                    
        // save last line read to skip on next pass
        lastline = lineix;
    }

    /**
     * get timestamp of when to measure elapes time
     */
    private void startElapsed () {
        elapsedStart = System.nanoTime();
    }
    
    /*
    * determine time elapsed from last startElapsed call and display results
    */
    private void readElapsed () {
        if (elapsedStart >= 0) {
            Long elapsedMsec = (System.nanoTime() - elapsedStart) / 1000000;
            debug.print(DebugMessage.StatusType.Info, "elapsed time = " + elapsedMsec + " ms");
            String elapsedField = "";
            if (elapsedMsec < 1000) {
                elapsedField = elapsedMsec + " ms";
            }
            else {
                Long elapsedSec = elapsedMsec / 1000;
                elapsedMsec %= 1000;
                elapsedField = "00" + elapsedMsec.toString();
                elapsedField = elapsedField.substring(elapsedField.length() - 3);
                elapsedField = elapsedSec + "." + elapsedField + " s";
            }
            elapsedTextField.setText(elapsedField);
            elapsedStart = -1;
        }
    }
    
    /**
     * run the selected user command and begin capture of stats for it
     * 
     * @param cmdstr - the user command to run
     */
    private void runCommand (String cmdstr) {
        // start the command timer
        startElapsed();

        // run the command
        String[] command = cmdstr.split(" ");
        debug.print(DebugMessage.StatusType.Separator, "-");
        debug.print(DebugMessage.StatusType.Info, "count: " + Integer.toString(++counter));
        debug.print(DebugMessage.StatusType.Info, "Starting command: " + cmdstr);
        int rc = commandLauncher.start(command, runpath);
        debug.print(DebugMessage.StatusType.Info, "returned: " + rc);
        //String result = commandLauncher.getResponse();
        //debug.print(DebugMessage.StatusType.Results, result);

        // get the elapsed time of the command
        readElapsed();
                    
        // terminate tshark to get results
        stopTshark();
    }

    /**
     * set the GUI from the config parameters
     */
    private void setGUIFromConfig () {
        this.portTextField.setText(configInfo.getField(ConfigInfo.ConfigTags.serverport));
        this.commandTextField1.setText(configInfo.getField(ConfigInfo.ConfigTags.command1));
        this.commandTextField2.setText(configInfo.getField(ConfigInfo.ConfigTags.command2));
        this.commandTextField3.setText(configInfo.getField(ConfigInfo.ConfigTags.command3));

        if (configInfo.getField(ConfigInfo.ConfigTags.protocol).equals("TCP")) {
            this.tcpRadioButton.setSelected(true);
            this.udpRadioButton.setSelected(false);
        }
        else {
            this.tcpRadioButton.setSelected(false);
            this.udpRadioButton.setSelected(true);
        }
        
        this.verboseCheckBox.setSelected(configInfo.getField(ConfigInfo.ConfigTags.verbose).equals("ON"));
        
        // enable the viable commands
        startCmd1Button.setEnabled(!this.commandTextField1.getText().isEmpty());
        startCmd2Button.setEnabled(!this.commandTextField2.getText().isEmpty());
        startCmd3Button.setEnabled(!this.commandTextField3.getText().isEmpty());

        // set the port value that is used by other settings
        serverport = this.portTextField.getText();
    }
    
    /**
     * set the config parameters from the GUI
     */
    private void setConfigFromGUI () {
        configInfo.setField(ConfigInfo.ConfigTags.serverport, this.portTextField.getText());
        configInfo.setField(ConfigInfo.ConfigTags.command1, this.commandTextField1.getText());
        configInfo.setField(ConfigInfo.ConfigTags.command2, this.commandTextField2.getText());
        configInfo.setField(ConfigInfo.ConfigTags.command3, this.commandTextField3.getText());

        if (this.tcpRadioButton.isSelected())
            configInfo.setField(ConfigInfo.ConfigTags.protocol, "TCP");
        else
            configInfo.setField(ConfigInfo.ConfigTags.protocol, "UDP");

        if (this.verboseCheckBox.isSelected())
            configInfo.setField(ConfigInfo.ConfigTags.verbose, "ON");
        else
            configInfo.setField(ConfigInfo.ConfigTags.verbose, "OFF");
    }

    private void updateServerSelection () {
        
        // this creates a command launcher that runs from the current thread
        // (this one will produce return information)
        CommandLauncher launcher = new CommandLauncher(true);

        // initialize the selection list to none
        this.serverSelectComboBox.removeAllItems();
        this.serverSelectComboBox.setEnabled(false);
        
        // create the list of server pids to select from
        if (serverport.isEmpty()) {
            return;
        }
        
        String command1[] = { "lsof", "-i", ":" + serverport };
        int rc = launcher.start(command1, null);
        if (rc < 0) {
            JOptionPane.showMessageDialog(null,
                "The command 'lsof' returned error: " + rc,
                "Command failure", JOptionPane.ERROR_MESSAGE);
        }
        else {
            // copy the list of processes into the combobox selections
            String results = launcher.getResponse();
            BufferedReader reader = new BufferedReader(new StringReader(results));
            try {
                String line;
                // read the list of running processes line by line
                while ((line = reader.readLine()) != null) {
                    String[] words = line.trim().split("[ ]+");
                    // word[0] = process name, word[1] = pid
                    // (skip the 1st entry that shows the column names & ignore this "ps" command)
                    if (words.length >= 2 && !"PID".equals(words[1])) {
                        String padding = "          ".substring(words[1].length());
                        String entry = words[1] + padding + words[0];
                        this.serverSelectComboBox.addItem(entry);
                    }
                }
                // if we found at least 1 entry, select the 1st entry in list as default
                if (serverSelectComboBox.getModel().getSize() > 0) {
                    // get the user selection and set the pid value for the server
                    serverSelectComboBox.setSelectedIndex(0);
                    
                    // now set the server pid to this selected value
                    String entry = (String)serverSelectComboBox.getSelectedItem();
                    if (entry != null) {
                        int offset = entry.indexOf(' ');
                        if (offset > 0) {
                            serverpid = Integer.parseInt(entry.substring(0, offset));
                        }
                    }

                    // if more than 1 process owns this port, show a selection
                    if (serverSelectComboBox.getModel().getSize() > 1)
                        this.serverSelectComboBox.setEnabled(true);
                }
                else {
                    debug.print(DebugMessage.StatusType.Error, "No process listening on port " + serverport);
                }
            } catch (IOException ex) {
                // ignore for now
            }
        }
    }    
    
  /**
   * this searches the 'content' block for the specified 'tag' and
   * returns the corresponding value associated with it. An additional 'type'
   * argument specifies whether to additionally verify if the value found
   * is a valid file or directory.
   * 
   * tags are defined in the configuration file at the begining of a
   * line and enclosed in <> brackets. The corresponding value must be placed
   * after 1 or more whitespace chars (space or tab only) and must not contain
   * any whitespace.
   * 
   * @param content - the content string to search
   * @param tag     - the field to search for
   * 
   * @return The corresponding value associated with the field. An empty string
   * will be returned if either the field was not found or no value was defined
   * for it or if was not a valid directory or file (only if 'type' field was set.
   */

    /**
     * saves the current user settings into the specified file
     * 
     * @param fname - file to save settings to
     */
    private void saveSettings (String fname) {
        // save the path to the config file as the execution path
        int offset = fname.lastIndexOf('/');
        if (offset >= 0) {
            runpath = fname.substring(0, offset);
        }
            
        // get the current config settings from the GUI
        setConfigFromGUI();
        
        // generate content from config settings
        String content = configInfo.publishAllTagFields();

        // if file currently exists, delete it first
        File file = new File(fname);
        if (file.isFile()) {
            file.delete();
        }

        // write content to file
        try {
            FileUtils.writeStringToFile(new File(fname), content, "UTF-8");
        } catch (IOException ex) {
            debug.print(DebugMessage.StatusType.Error, ex.getMessage());
        }
    }
    
    /**
     * loads the current user settings from the specified file
     * 
     * @param fname - file to load settings from
     */
    private void loadSettings (String fname) {
        File file = new File(fname);
        if (file.isFile()) {
            try {
                // save the path to the config file as the execution path
                int offset = fname.lastIndexOf('/');
                if (offset >= 0) {
                    runpath = fname.substring(0, offset);
                }
            
                // read the file into a String
                String content = FileUtils.readFileToString(new File(fname), "UTF-8");
                
                // parse entries into config struct
                configInfo = new ConfigInfo();
                configInfo.extractAllTagFields (content);
                
                // execute settings
                setGUIFromConfig();
                
                // set the server selection (based on the port selection)
                updateServerSelection();
            } catch (IOException ex) {
                debug.print(DebugMessage.StatusType.Error, ex.getMessage());
            }
        }
    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        configFileChooser = new javax.swing.JFileChooser();
        interfacePanel = new javax.swing.JPanel();
        runPanel = new javax.swing.JPanel();
        elapsedTextField = new javax.swing.JTextField();
        peakmemTextField = new javax.swing.JTextField();
        deltaPeakmemTextField = new javax.swing.JTextField();
        sentTextField = new javax.swing.JTextField();
        rcvdTextField = new javax.swing.JTextField();
        totalSentTextField = new javax.swing.JTextField();
        totalRcvdTextField = new javax.swing.JTextField();
        resetButton = new javax.swing.JButton();
        startCmd1Button = new javax.swing.JButton();
        stopButton = new javax.swing.JButton();
        startCmd2Button = new javax.swing.JButton();
        startCmd3Button = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        jLabel11 = new javax.swing.JLabel();
        setupPanel = new javax.swing.JPanel();
        jLabel4 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        commandTextField2 = new javax.swing.JTextField();
        tcpRadioButton = new javax.swing.JRadioButton();
        commandTextField1 = new javax.swing.JTextField();
        jLabel7 = new javax.swing.JLabel();
        verboseCheckBox = new javax.swing.JCheckBox();
        commandTextField3 = new javax.swing.JTextField();
        jLabel6 = new javax.swing.JLabel();
        portTextField = new javax.swing.JTextField();
        udpRadioButton = new javax.swing.JRadioButton();
        loadSetupButton = new javax.swing.JButton();
        saveSetupButton = new javax.swing.JButton();
        serverSelectComboBox = new javax.swing.JComboBox<>();
        jLabel10 = new javax.swing.JLabel();
        outputTabbedPane = new javax.swing.JTabbedPane();
        statusScrollPane = new javax.swing.JScrollPane();
        statusTextPane = new javax.swing.JTextPane();
        outputScrollPane = new javax.swing.JScrollPane();
        outputTextArea = new javax.swing.JTextArea();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("PacketCounter");
        setMinimumSize(new java.awt.Dimension(860, 600));
        setPreferredSize(new java.awt.Dimension(840, 600));
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });

        interfacePanel.setMinimumSize(new java.awt.Dimension(816, 202));

        runPanel.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        runPanel.setMinimumSize(new java.awt.Dimension(390, 200));
        runPanel.setPreferredSize(new java.awt.Dimension(400, 200));

        elapsedTextField.setEditable(false);
        elapsedTextField.setMinimumSize(new java.awt.Dimension(100, 21));
        elapsedTextField.setPreferredSize(new java.awt.Dimension(100, 21));

        peakmemTextField.setEditable(false);
        peakmemTextField.setMinimumSize(new java.awt.Dimension(80, 21));
        peakmemTextField.setPreferredSize(new java.awt.Dimension(80, 21));

        deltaPeakmemTextField.setEditable(false);
        deltaPeakmemTextField.setMinimumSize(new java.awt.Dimension(80, 21));
        deltaPeakmemTextField.setPreferredSize(new java.awt.Dimension(100, 21));

        sentTextField.setEditable(false);
        sentTextField.setMinimumSize(new java.awt.Dimension(80, 21));
        sentTextField.setPreferredSize(new java.awt.Dimension(80, 21));

        rcvdTextField.setEditable(false);
        rcvdTextField.setMinimumSize(new java.awt.Dimension(80, 21));
        rcvdTextField.setPreferredSize(new java.awt.Dimension(80, 21));

        totalSentTextField.setEditable(false);
        totalSentTextField.setMinimumSize(new java.awt.Dimension(80, 21));
        totalSentTextField.setPreferredSize(new java.awt.Dimension(100, 21));

        totalRcvdTextField.setEditable(false);
        totalRcvdTextField.setMinimumSize(new java.awt.Dimension(80, 21));
        totalRcvdTextField.setPreferredSize(new java.awt.Dimension(100, 21));

        resetButton.setText("Reset");
        resetButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                resetButtonActionPerformed(evt);
            }
        });

        startCmd1Button.setBackground(new java.awt.Color(204, 255, 204));
        startCmd1Button.setText("Cmd 1");
        startCmd1Button.setPreferredSize(new java.awt.Dimension(75, 25));
        startCmd1Button.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startCmd1ButtonActionPerformed(evt);
            }
        });

        stopButton.setBackground(new java.awt.Color(255, 204, 204));
        stopButton.setText("Stop");
        stopButton.setPreferredSize(new java.awt.Dimension(75, 25));
        stopButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopButtonActionPerformed(evt);
            }
        });

        startCmd2Button.setBackground(new java.awt.Color(204, 255, 204));
        startCmd2Button.setText("Cmd 2");
        startCmd2Button.setPreferredSize(new java.awt.Dimension(75, 25));
        startCmd2Button.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startCmd2ButtonActionPerformed(evt);
            }
        });

        startCmd3Button.setBackground(new java.awt.Color(204, 255, 204));
        startCmd3Button.setText("Cmd 3");
        startCmd3Button.setPreferredSize(new java.awt.Dimension(75, 25));
        startCmd3Button.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startCmd3ButtonActionPerformed(evt);
            }
        });

        jLabel1.setText("Elapsed:");

        jLabel2.setText("Bytes sent:");

        jLabel3.setText("Bytes rcvd:");

        jLabel9.setText("cumulative");

        jLabel11.setText("Peak mem:");

        javax.swing.GroupLayout runPanelLayout = new javax.swing.GroupLayout(runPanel);
        runPanel.setLayout(runPanelLayout);
        runPanelLayout.setHorizontalGroup(
            runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(runPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(runPanelLayout.createSequentialGroup()
                        .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(runPanelLayout.createSequentialGroup()
                                .addComponent(startCmd1Button, javax.swing.GroupLayout.PREFERRED_SIZE, 86, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(startCmd2Button, javax.swing.GroupLayout.PREFERRED_SIZE, 86, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(5, 5, 5)
                                .addComponent(startCmd3Button, javax.swing.GroupLayout.PREFERRED_SIZE, 86, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(runPanelLayout.createSequentialGroup()
                                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(jLabel1)
                                    .addComponent(jLabel11))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(elapsedTextField, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGroup(runPanelLayout.createSequentialGroup()
                                        .addGap(98, 98, 98)
                                        .addComponent(jLabel9))
                                    .addGroup(runPanelLayout.createSequentialGroup()
                                        .addComponent(peakmemTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(deltaPeakmemTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)))))
                        .addGap(26, 26, 26)
                        .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(resetButton, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(stopButton, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(runPanelLayout.createSequentialGroup()
                        .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel3)
                            .addComponent(jLabel2))
                        .addGap(6, 6, 6)
                        .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(rcvdTextField, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(sentTextField, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addGap(6, 6, 6)
                        .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(totalRcvdTextField, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(totalSentTextField, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))))
                .addContainerGap())
        );
        runPanelLayout.setVerticalGroup(
            runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(runPanelLayout.createSequentialGroup()
                .addGap(11, 11, 11)
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(jLabel1)
                        .addComponent(elapsedTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(resetButton))
                .addGap(8, 8, 8)
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(peakmemTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel11)
                    .addComponent(deltaPeakmemTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(12, 12, 12)
                .addComponent(jLabel9)
                .addGap(4, 4, 4)
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(sentTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel2)
                    .addComponent(totalSentTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(rcvdTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel3)
                    .addComponent(totalRcvdTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addGroup(runPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(startCmd1Button, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(stopButton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(startCmd2Button, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(startCmd3Button, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        setupPanel.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        setupPanel.setMinimumSize(new java.awt.Dimension(392, 200));
        setupPanel.setPreferredSize(new java.awt.Dimension(400, 200));

        jLabel4.setText("Server Port:");

        jLabel5.setText("Command 1:");

        commandTextField2.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                commandTextField2FocusLost(evt);
            }
        });
        commandTextField2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                commandTextField2ActionPerformed(evt);
            }
        });

        tcpRadioButton.setSelected(true);
        tcpRadioButton.setText("TCP");
        tcpRadioButton.setEnabled(false);
        tcpRadioButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                tcpRadioButtonActionPerformed(evt);
            }
        });

        commandTextField1.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                commandTextField1FocusLost(evt);
            }
        });
        commandTextField1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                commandTextField1ActionPerformed(evt);
            }
        });

        jLabel7.setText("Command 3:");

        verboseCheckBox.setText("Verbose tshark");

        commandTextField3.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                commandTextField3FocusLost(evt);
            }
        });
        commandTextField3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                commandTextField3ActionPerformed(evt);
            }
        });

        jLabel6.setText("Command 2:");

        portTextField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                portTextFieldFocusLost(evt);
            }
        });
        portTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                portTextFieldActionPerformed(evt);
            }
        });

        udpRadioButton.setText("UDP");
        udpRadioButton.setEnabled(false);
        udpRadioButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                udpRadioButtonActionPerformed(evt);
            }
        });

        loadSetupButton.setText("Load");
        loadSetupButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                loadSetupButtonActionPerformed(evt);
            }
        });

        saveSetupButton.setText("Save");
        saveSetupButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveSetupButtonActionPerformed(evt);
            }
        });

        serverSelectComboBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                serverSelectComboBoxActionPerformed(evt);
            }
        });

        jLabel10.setText("Server pid:");

        javax.swing.GroupLayout setupPanelLayout = new javax.swing.GroupLayout(setupPanel);
        setupPanel.setLayout(setupPanelLayout);
        setupPanelLayout.setHorizontalGroup(
            setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(setupPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(setupPanelLayout.createSequentialGroup()
                        .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel5)
                            .addComponent(jLabel6)
                            .addComponent(jLabel7)
                            .addComponent(jLabel10))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(commandTextField1)
                            .addComponent(commandTextField2)
                            .addComponent(commandTextField3)
                            .addComponent(serverSelectComboBox, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addGroup(setupPanelLayout.createSequentialGroup()
                        .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(setupPanelLayout.createSequentialGroup()
                                .addComponent(tcpRadioButton)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(udpRadioButton)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(verboseCheckBox))
                            .addGroup(setupPanelLayout.createSequentialGroup()
                                .addComponent(jLabel4)
                                .addGap(13, 13, 13)
                                .addComponent(portTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 97, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(18, 18, 18)
                                .addComponent(saveSetupButton)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(loadSetupButton)))
                        .addGap(0, 10, Short.MAX_VALUE)))
                .addContainerGap())
        );
        setupPanelLayout.setVerticalGroup(
            setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(setupPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel4)
                    .addComponent(portTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(loadSetupButton)
                    .addComponent(saveSetupButton))
                .addGap(7, 7, 7)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(serverSelectComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel10))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(commandTextField1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel6)
                    .addComponent(commandTextField2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel7)
                    .addComponent(commandTextField3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 22, Short.MAX_VALUE)
                .addGroup(setupPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(tcpRadioButton)
                    .addComponent(verboseCheckBox)
                    .addComponent(udpRadioButton))
                .addContainerGap())
        );

        javax.swing.GroupLayout interfacePanelLayout = new javax.swing.GroupLayout(interfacePanel);
        interfacePanel.setLayout(interfacePanelLayout);
        interfacePanelLayout.setHorizontalGroup(
            interfacePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(interfacePanelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(runPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(12, 12, 12)
                .addComponent(setupPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );
        interfacePanelLayout.setVerticalGroup(
            interfacePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(interfacePanelLayout.createSequentialGroup()
                .addGroup(interfacePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(runPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(setupPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(39, 39, 39))
        );

        outputTabbedPane.setMinimumSize(new java.awt.Dimension(80, 100));
        outputTabbedPane.setPreferredSize(new java.awt.Dimension(100, 250));

        statusScrollPane.setViewportView(statusTextPane);

        outputTabbedPane.addTab("Status", statusScrollPane);

        outputTextArea.setColumns(20);
        outputTextArea.setRows(5);
        outputScrollPane.setViewportView(outputTextArea);

        outputTabbedPane.addTab("Output", outputScrollPane);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(interfacePanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(outputTabbedPane, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(interfacePanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(36, 36, 36)
                .addComponent(outputTabbedPane, javax.swing.GroupLayout.DEFAULT_SIZE, 181, Short.MAX_VALUE)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void resetButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_resetButtonActionPerformed
        resetStats();
    }//GEN-LAST:event_resetButtonActionPerformed

    private void startCmd1ButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startCmd1ButtonActionPerformed
        runCommand(commandTextField1.getText());
    }//GEN-LAST:event_startCmd1ButtonActionPerformed

    private void stopButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_stopButtonActionPerformed
        if (tsharkpid >= 0)
            stopTshark();
    }//GEN-LAST:event_stopButtonActionPerformed

    private void tcpRadioButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_tcpRadioButtonActionPerformed
        if (tcpRadioButton.isSelected())
            udpRadioButton.setSelected(false);
        else
            udpRadioButton.setSelected(true);
    }//GEN-LAST:event_tcpRadioButtonActionPerformed

    private void udpRadioButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_udpRadioButtonActionPerformed
        if (udpRadioButton.isSelected())
            tcpRadioButton.setSelected(false);
        else
            tcpRadioButton.setSelected(true);
    }//GEN-LAST:event_udpRadioButtonActionPerformed

    private void startCmd2ButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startCmd2ButtonActionPerformed
        runCommand(commandTextField2.getText());
    }//GEN-LAST:event_startCmd2ButtonActionPerformed

    private void startCmd3ButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startCmd3ButtonActionPerformed
        runCommand(commandTextField3.getText());
    }//GEN-LAST:event_startCmd3ButtonActionPerformed

    private void commandTextField1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_commandTextField1ActionPerformed
        if (commandTextField1.getText().isEmpty())
            startCmd1Button.setEnabled(false);
        else
            startCmd1Button.setEnabled(true);
    }//GEN-LAST:event_commandTextField1ActionPerformed

    private void commandTextField2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_commandTextField2ActionPerformed
        if (commandTextField2.getText().isEmpty())
            startCmd2Button.setEnabled(false);
        else
            startCmd2Button.setEnabled(true);
    }//GEN-LAST:event_commandTextField2ActionPerformed

    private void commandTextField3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_commandTextField3ActionPerformed
        if (commandTextField3.getText().isEmpty())
            startCmd3Button.setEnabled(false);
        else
            startCmd3Button.setEnabled(true);
    }//GEN-LAST:event_commandTextField3ActionPerformed

    private void loadSetupButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_loadSetupButtonActionPerformed
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Config Files","config");
        this.configFileChooser.setFileFilter(filter);
        this.configFileChooser.setApproveButtonText("Open");
        this.configFileChooser.setMultiSelectionEnabled(false);
        int retVal = this.configFileChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            File file = this.configFileChooser.getSelectedFile();
            String fname = file.getAbsolutePath();
            
            // load the settings
            loadSettings(fname);
            
            // save as the last config file used
            classProperties.setPropertiesItem(ClassProperties.PropertyTags.LastConfigFile, fname);
        }
    }//GEN-LAST:event_loadSetupButtonActionPerformed

    private void commandTextField1FocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_commandTextField1FocusLost
        if (commandTextField1.getText().isEmpty())
            startCmd1Button.setEnabled(false);
        else
            startCmd1Button.setEnabled(true);
    }//GEN-LAST:event_commandTextField1FocusLost

    private void commandTextField2FocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_commandTextField2FocusLost
        if (commandTextField2.getText().isEmpty())
            startCmd2Button.setEnabled(false);
        else
            startCmd2Button.setEnabled(true);
    }//GEN-LAST:event_commandTextField2FocusLost

    private void commandTextField3FocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_commandTextField3FocusLost
        if (commandTextField3.getText().isEmpty())
            startCmd3Button.setEnabled(false);
        else
            startCmd3Button.setEnabled(true);
    }//GEN-LAST:event_commandTextField3FocusLost

    private void saveSetupButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveSetupButtonActionPerformed
        File defaultFile = new File("packetcounter.config");
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Config Files","config");
        this.configFileChooser.setFileFilter(filter);
        this.configFileChooser.setSelectedFile(defaultFile);
        this.configFileChooser.setApproveButtonText("Save");
        this.configFileChooser.setMultiSelectionEnabled(false);
        int retVal = this.configFileChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            File file = this.configFileChooser.getSelectedFile();
            String fname = file.getAbsolutePath();

            // generate and save the settings
            saveSettings(fname);
            
            // save as the last config file used
            classProperties.setPropertiesItem(ClassProperties.PropertyTags.LastConfigFile, fname);
        }
    }//GEN-LAST:event_saveSetupButtonActionPerformed

    private void serverSelectComboBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_serverSelectComboBoxActionPerformed
        // get the user selection and set the pid value for the server
        String entry = (String)serverSelectComboBox.getSelectedItem();
        if (entry != null) {
            int offset = entry.indexOf(' ');
            if (offset > 0) {
                serverpid = Integer.parseInt(entry.substring(0, offset));
            }
        }
    }//GEN-LAST:event_serverSelectComboBoxActionPerformed

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        // if tshark is still running, stop it before exiting
        if (tsharkpid >= 0)
            stopTshark();
    }//GEN-LAST:event_formWindowClosing

    private void portTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_portTextFieldActionPerformed
        // make sure the user entered a valid numeric
        String sport = portTextField.getText();
        try {
            int port = Integer.parseUnsignedInt(sport);
            if (port > 65535) {
                debug.print(DebugMessage.StatusType.Error, "Invalid port range");
                portTextField.setText(serverport);
                return;
            }
        }
        catch (NumberFormatException ex) {
            // invalid chars - restore previous entry
            debug.print(DebugMessage.StatusType.Error, "Invalid port format");
            portTextField.setText(serverport);
            return;
        }

        // valid entry, update setting
        serverport = sport;
        
        // set the server selection (based on the port selection)
        updateServerSelection();
    }//GEN-LAST:event_portTextFieldActionPerformed

    private void portTextFieldFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_portTextFieldFocusLost
        // make sure the user entered a valid numeric
        String sport = portTextField.getText();
        try {
            int port = Integer.parseUnsignedInt(sport);
            if (port > 65535) {
                debug.print(DebugMessage.StatusType.Error, "Invalid port range");
                portTextField.setText(serverport);
                return;
            }
        }
        catch (NumberFormatException ex) {
            // invalid chars - restore previous entry
            debug.print(DebugMessage.StatusType.Error, "Invalid port format");
            portTextField.setText(serverport);
            return;
        }

        // valid entry, update setting
        serverport = sport;
        
        // set the server selection (based on the port selection)
        updateServerSelection();
    }//GEN-LAST:event_portTextFieldFocusLost

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(MainFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(MainFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(MainFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(MainFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new MainFrame().setVisible(true);
            }
        });
    }

    private Integer  bytesto;
    private Integer  bytesfrom;
    private Integer  peakmem;
    private String   serverport;
    private long     serverpid;
    private long     tsharkpid;
    private long     elapsedStart;
    private int      lastline;
    private int      counter;
    private String   runpath;

    private ClassProperties classProperties;
    private ConfigInfo configInfo;
    private final PrintStream     standardOut;
    private final PrintStream     standardErr; 
    private final DebugMessage    debug;
    private final ElapsedTimer    tstampTimer;
    private final ThreadLauncher  threadLauncher;
    private final CommandLauncher commandLauncher;
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JTextField commandTextField1;
    private javax.swing.JTextField commandTextField2;
    private javax.swing.JTextField commandTextField3;
    private javax.swing.JFileChooser configFileChooser;
    private javax.swing.JTextField deltaPeakmemTextField;
    private javax.swing.JTextField elapsedTextField;
    private javax.swing.JPanel interfacePanel;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JButton loadSetupButton;
    private javax.swing.JScrollPane outputScrollPane;
    private javax.swing.JTabbedPane outputTabbedPane;
    private javax.swing.JTextArea outputTextArea;
    private javax.swing.JTextField peakmemTextField;
    private javax.swing.JTextField portTextField;
    private javax.swing.JTextField rcvdTextField;
    private javax.swing.JButton resetButton;
    private javax.swing.JPanel runPanel;
    private javax.swing.JButton saveSetupButton;
    private javax.swing.JTextField sentTextField;
    private javax.swing.JComboBox<String> serverSelectComboBox;
    private javax.swing.JPanel setupPanel;
    private javax.swing.JButton startCmd1Button;
    private javax.swing.JButton startCmd2Button;
    private javax.swing.JButton startCmd3Button;
    private javax.swing.JScrollPane statusScrollPane;
    private javax.swing.JTextPane statusTextPane;
    private javax.swing.JButton stopButton;
    private javax.swing.JRadioButton tcpRadioButton;
    private javax.swing.JTextField totalRcvdTextField;
    private javax.swing.JTextField totalSentTextField;
    private javax.swing.JRadioButton udpRadioButton;
    private javax.swing.JCheckBox verboseCheckBox;
    // End of variables declaration//GEN-END:variables
}
