/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package packetcounter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import javax.swing.JTextArea;

/**
 * @author dmcd2356
 *
 */
public class CommandLauncher {
    private JTextArea response;
    private boolean redirect_stdout = false;
    private long pid;
    
    public CommandLauncher (boolean redirect) {
        this.response = null;
        redirect_stdout = redirect;
        this.pid = -1;
    }
    
    public String getResponse () {
        return response.getText();
    }
    
    /**
     * returns the pid of the current job
     * 
     * @return the PID of the current job thread
     */
    public long getJobPid() {
        return pid;
    }
    
    /**
     * returns the pid of the current job
     * 
     * @return the PID of the current job thread
     */
    private synchronized long getPidOfProcess(Process proc) {
        long procpid = -1;

        if (proc == null || proc.getClass() == null)
            return -1;
        
        try {
            if (proc.getClass().getName().equals("java.lang.UNIXProcess")) {
                Field f = proc.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                procpid = f.getLong(proc);
                f.setAccessible(false);
            }
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            procpid = -1;
        }
        return procpid;
    }

    /**
     * starts the specified command in the current thread.
     * 
     * @param command - an array of the command and each argument it requires
     * @param workdir - the path from which to launch the command
     * 
     * @return the status of the command (0 = success) 
     */
    public int start(String[] command, String workdir) {
        int retcode = 0;

        // create an output area for stderr and stdout
        this.response = new JTextArea();
        this.pid = -1;

        // build up the command and argument string
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workdir != null) {
            File workingdir = new File(workdir);
            if (workingdir.isDirectory())
                builder.directory(workingdir);
        }
        
        PrintStream standardOut = System.out;
        PrintStream standardErr = System.err;         

        if (redirect_stdout) {
            // re-direct stdout and stderr to the text window
            // merge stderr into stdout so both go to the specified text area
            builder.redirectErrorStream(true);
            PrintStream printStream = new PrintStream(new RedirectOutputStream(response)); 
            System.setOut(printStream);
            System.setErr(printStream);
        }
            
        // run the command
        Process p;
        try {
            p = builder.start();
        } catch (IOException ex) {
            System.err.println("builder failure: " + ex);
            return -1;
        }

        // get the pid of the process
        this.pid = getPidOfProcess(p);
        String status;
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        while (p.isAlive()) {
            try {
                while ((status = br.readLine()) != null) {
                    System.out.println(status);
                }
            } catch (IOException ex) {
                System.err.println("BufferedReader failure: " + ex);
                retcode = -1;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
//                System.err.println("Thread.sleep failure: " + ex);
                break;
            }
        }

        if (retcode == 0)
            retcode = p.exitValue();
        p.destroy();

        // restore the stdout and stderr
        if (redirect_stdout) {
            System.setOut(standardOut);
            System.setErr(standardErr);
        }

        return retcode;
    }
    
}

