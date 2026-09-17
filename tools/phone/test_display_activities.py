#!/usr/bin/env python3
"""JVM parser regression: display isolation and unknown activity metadata."""
import os
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
java_home = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
source = root / 'platform/executor/prototype-virtual-display/src/dev/droiduse/probe/DisplayActivities.java'
with tempfile.TemporaryDirectory(prefix='droiduse-display-parser-') as directory:
    test = Path(directory) / 'DisplayActivitiesTest.java'
    test.write_text('''
import dev.droiduse.probe.DisplayActivities;
public class DisplayActivitiesTest {
    static void eq(String expected,String actual) {
        if(!java.util.Objects.equals(expected,actual)) throw new AssertionError(actual);
    }
    public static void main(String[] args) {
        String dump="Display #0 (activities from top to bottom):\\n"
            +"  topResumedActivity=ActivityRecord{abc u0 main.app/.Home t1}\\n"
            +"Display #29 (activities from top to bottom):\\n"
            +"  topResumedActivity=ActivityRecord{abc u10 background.app/full.Activity t2}\\n"
            +"Display #30 (activities from top to bottom):\\n"
            +"  topResumedActivity=null\\n";
        eq("main.app",DisplayActivities.resumedPackage(dump,0));
        eq("background.app",DisplayActivities.resumedPackage(dump,29));
        eq(null,DisplayActivities.resumedPackage(dump,30));
        eq(null,DisplayActivities.resumedPackage(dump,2));
        eq(null,DisplayActivities.resumedPackage("topResumedActivity=ActivityRecord{a u0 fake.app/.A t2}",0));
        System.out.println("5 display activity parser checks passed");
    }
}
''')
    subprocess.run([str(java_home/'bin/javac'), '-d', directory, str(source), str(test)], check=True)
    subprocess.run([str(java_home/'bin/java'), '-cp', directory, 'DisplayActivitiesTest'], check=True)
