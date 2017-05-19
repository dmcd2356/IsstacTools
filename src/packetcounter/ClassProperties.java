/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package packetcounter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 *
 * @author dmcd2356
 */
public class ClassProperties {
    
    final static private String PROPERTIES_PATH = System.getProperty("user.home") + "/.pktcounter/";
    final static private String PROPERTIES_FILE = PROPERTIES_PATH + "site.properties";
  
    private Properties props;

    ClassProperties() {
        props = new Properties();
        File propfile = new File(PROPERTIES_FILE);
        if (propfile.exists()) {
            try {
                // property file exists, read it in
                FileInputStream in = new FileInputStream(PROPERTIES_FILE);
                props.load(in);
                in.close();
            } catch (FileNotFoundException ex) {
                System.err.println(ex + " <" + PROPERTIES_FILE + ">");
                props = null;
            } catch (IOException ex) {
                System.err.println(ex + " <" + PROPERTIES_FILE + ">");
                props = null;
            }
        }
        else {
            // property file does not exist - create a default one
            System.err.println("<FILE_NOT_FOUND> - " + PROPERTIES_FILE);
            
            // set the default content of the properties
            props.setProperty("LastConfigFile", "");

            try {
                // first, check if directory exists
                File proppath = new File (PROPERTIES_PATH);
                if (!proppath.exists())
                    proppath.mkdir();

                // now create the file and save the initial blank data
                System.out.println("Creating initial site.properties file.");
                File file = new File(PROPERTIES_FILE);
                try (FileOutputStream fileOut = new FileOutputStream(file)) {
                    props.store(fileOut, "Initialization");
                }
            } catch (IOException ex) {
                System.err.println(ex + " <" + PROPERTIES_FILE + ">");
                props = null;
            }
        }
    }

    public String getPropertiesItem (String tag) {
        String value = null;
        if (props != null) {
            value = props.getProperty(tag);
            if (value != null && !value.isEmpty())
                System.out.println("site.properties <" + tag + "> = " + value);
            else
                System.err.println("site.properties <" + tag + "> : not found ");
        }

        return (value == null) ? "" : value; // guarantee no null return
    }
  
    public void setPropertiesItem (String tag, String value) {

        // save changes to properties file
        // (currently the only parameter is the last configuration file loaded)
        if (props == null)
            return;

        // make sure the properties file exists
        File propsfile = new File(PROPERTIES_FILE);
        if (!propsfile.exists()) {
            System.err.println("<FILE_NOT_FOUND> - site.properties");
            return;
        }
        try {
            System.out.println("site.properties <" + tag + "> set to " + value);
            props.setProperty(tag, value);
            FileOutputStream out = new FileOutputStream(PROPERTIES_FILE);
            props.store(out, "---No Comment---");
            out.close();
        } catch (FileNotFoundException ex) {
            System.err.println(ex + "- site.properties");
        } catch (IOException ex) {
            System.err.println(ex + "- site.properties");
        }
    }  
  
}
