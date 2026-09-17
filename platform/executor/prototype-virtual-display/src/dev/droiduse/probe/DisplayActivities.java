package dev.droiduse.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse only resumed activity metadata in the requested display section; never retain dump text. */
public final class DisplayActivities {
    private static final Pattern DISPLAY=Pattern.compile("^Display #(\\d+) ");
    private static final Pattern ACTIVITY=Pattern.compile("topResumedActivity=ActivityRecord\\{[^}]* u\\d+ ([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[^ }]+ ");
    public static String resumedPackage(String dump,int requestedDisplay) {
        int current=-1;
        for(String line:dump.split("\n")) {
            Matcher display=DISPLAY.matcher(line);
            if(display.find()) current=Integer.parseInt(display.group(1));
            if(current!=requestedDisplay) continue;
            Matcher activity=ACTIVITY.matcher(line);
            if(activity.find()) return activity.group(1);
        }
        return null;
    }
}
