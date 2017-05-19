/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package packetcounter;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JTextField;
import javax.swing.Timer;

/**
 *
 * @author dan
 */
    public class ElapsedTimer implements ActionListener{

        private final Timer       elapsedTimer;
        private final JTextField  elapsedLabel;
        private int     elapsedSecs;
        private boolean enabled;
        private String  timestamp;

        public ElapsedTimer(JTextField label){
            elapsedTimer = new Timer(1000, this);
            elapsedLabel = label;
            enabled = false;
            elapsedSecs = 0;
            timestamp = "00:00";
        }

        public void reset () {
            enabled = true;
            elapsedSecs = 0;
            timestamp = "00:00";
            elapsedLabel.setText(timestamp);
        }
        
        public void start () {
            elapsedTimer.start();
        }
        
        public void stop () {
            elapsedTimer.stop();
        }
        
        public String getElapsed () {
            return timestamp;
        }
        
        /**
         * handles the action to perform when the specified event occurs.
         * @param e - event that occurred
         */
        @Override
        public void actionPerformed(ActionEvent e) {
            if (enabled) {
                ++elapsedSecs;
                Integer secs = elapsedSecs % 60;
                Integer mins = elapsedSecs / 60;
                timestamp = ((mins < 10) ? "0" : "") + mins.toString() + ":" +
                            ((secs < 10) ? "0" : "") + secs.toString();
                elapsedLabel.setText(timestamp);
            }
        }
    }    

