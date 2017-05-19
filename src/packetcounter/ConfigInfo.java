/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package packetcounter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"rawtypes", "unchecked", "serial"})

/**
 *
 * @author dmcd2356
 */
public class ConfigInfo {

    final static private String newLine = System.getProperty("line.separator");
    
    // the following have corresponding widgets in AnalyzerFrame
    public String port;         // the server port
    public String command1;     // command 1 to run
    public String command2;     // command 2 to run
    public String command3;     // command 3 to run
    public String protocol;     // type (tcp, udp)
    public String verbose;      // verbose (ON, OFF)
        
    ConfigInfo ()  {
        this(null);
    }
    
    ConfigInfo (String name)  {
        port     = "8080";
        command1 = "";
        command2 = "";
        command3 = "";
        protocol = "TCP";
        verbose  = "OFF";
    }

    /**
     * set the value of the specified tag in the class.
     * If the tag is a List type, the corresponding list will be cleared if the
     * value is empty, and if not the value will be added to the end of the list.
     * 
     * @param tag   - the tag id of the parameter
     * @param value - the value to set the parameter to
     * @return the corresponding value from the class (null if invalid selection)
     */    
    public int setField (String tag, String value) {
        switch (tag) {
        case "serverport":  this.port     = value;    break;
        case "command1":    this.command1 = value;    break;
        case "command2":    this.command2 = value;    break;
        case "command3":    this.command3 = value;    break;
        case "protocol":    this.protocol = value;    break;
        case "verbose":     this.verbose  = value;    break;
        default:
            return -1;
        }

        return 0;
    }

    /**
     * get value corresponding to the specified tag.
     * 
     * @param tag - the tag id of the parameter
     * @return the corresponding value from the class (null if invalid selection)
     */    
    public String getField (String tag) {
        switch (tag) {
        case "serverport":  return this.port;
        case "command1":    return this.command1;
        case "command2":    return this.command2;
        case "command3":    return this.command3;
        case "protocol":    return this.protocol;
        case "verbose":     return this.verbose;
        default:
            return null;
        }
    }

    /**
     * creates a config file entry for the specified tag using the field value
     * from configInfo.
     * 
     * @param tag - the config file tag to add
     * @return the entry to add to the config file
     */
    private String addTagField (String tag) {
        int tagTabLength = 16;
        String field = getField (tag);
        if (field == null || field.isEmpty())
            return "";
        
        int length = tagTabLength - 2 - tag.length();
        length = (length > 0) ? length : 2;
        String spacing = new String(new char[length]).replace("\0", " ");

        return "<" + tag + ">" + spacing + field + newLine;
    }
    
    public String extractTagField (String content, String tag) {
        // we're looking for the tag enclosed in <> at the begining of the line
        // followed by whitespace and the field value terminating on whitespace.
        String search = "^(<" + tag + ">)[ \t]+(.*)$"; //(\\S+)";
        String entry = "";
        Pattern pattern = Pattern.compile(search, Pattern.MULTILINE);
        Matcher match = pattern.matcher(content);
        if (match.find()) {
            entry = match.group(2);
            System.out.println("- " + tag + ": " + entry);
        }
        else {
            System.out.println("- " + tag + ": ---");
        }

        // update the config parameter
        setField (tag, entry);
        return entry;
    }

    public String publishAllTagFields () {
        String content = "";
        content += addTagField ("serverport");
        content += addTagField ("command1");
        content += addTagField ("command2");
        content += addTagField ("command3");
        content += addTagField ("protocol");
        content += addTagField ("verbose");
        return content;
    }
    
    public void extractAllTagFields (String content) {
        extractTagField (content, "serverport");
        extractTagField (content, "command1");
        extractTagField (content, "command2");
        extractTagField (content, "command3");
        extractTagField (content, "protocol");
        extractTagField (content, "verbose");
    }
}
