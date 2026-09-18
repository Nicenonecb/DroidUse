package dev.droiduse.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Frame-scoped native launch targets and OCR-visible DocumentsUI file rows. */
final class PhoneTargets {
    interface Shell { String run(String... args) throws Exception; }
    static final class Target {
        final String id=UUID.randomUUID().toString(), kind, label, component, uri;
        final int x,y;
        Target(String kind,String label,String component,String uri,int x,int y) {
            this.kind=kind;this.label=label;this.component=component;this.uri=uri;this.x=x;this.y=y;
        }
        String packageName() { return component.substring(0,component.indexOf('/')); }
        JSONObject json() throws Exception { return new JSONObject().put("targetId",id).put("kind",kind).put("label",label); }
    }
    private static final Pattern COMPONENT=Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+" );
    private static final Pattern FILE=Pattern.compile("(?i)[^/\\\\<>:|?*\\r\\n]{1,180}\\.(?:pdf|txt|csv|json|xml|md|docx?|xlsx?|pptx?|jpe?g|png|webp|gif|heic|mp[34]|m4a|wav|ogg|flac|mov|webm|zip|7z|rar)");
    private final Shell shell;
    private final LinkedHashMap<String,Target> targets=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> apps=new LinkedHashMap<>();
    PhoneTargets(Shell shell) { this.shell=shell; }
    static boolean isPicker(String pkg) {
        return Arrays.asList("com.google.android.documentsui","com.android.documentsui",
            "com.google.android.providers.media.module","com.android.providers.media.module","com.android.photopicker").contains(pkg);
    }
    void clear() { targets.clear(); }
    Target get(String id) { return targets.get(id); }
    void loadApps() throws Exception {
        apps.clear();Set<String> installed=new HashSet<>();
        for(String line:shell.run("cmd","package","list","packages","-3").split("\n"))
            if(line.startsWith("package:")) installed.add(line.substring(8).trim());
        for(String line:shell.run("cmd","package","query-activities","--brief","--components","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER").split("\n")) {
            String component=line.trim();if(!COMPONENT.matcher(component).matches()) continue;
            String pkg=component.substring(0,component.indexOf('/'));
            if(installed.contains(pkg) && !pkg.startsWith("dev.droiduse.")) apps.putIfAbsent(pkg,component);
        }
    }
    JSONArray enumerate(String app,String mainApp,JSONArray rows) throws Exception {
        clear();
        // Visible rows have priority over the installed-app catalogue.
        boolean picker=app.equals("com.google.android.documentsui") || app.equals("com.android.documentsui");
        Map<String,Integer> counts=new HashMap<>();
        for(int i=0;i<rows.length();i++) {
            String text=rows.getJSONObject(i).getString("text").trim();counts.put(text,counts.getOrDefault(text,0)+1);
        }
        int links=0;
        for(int i=0;i<rows.length() && targets.size()<60;i++) {
            JSONObject row=rows.getJSONObject(i);String text=row.getString("text").trim();
            if(text.length()>500 || counts.get(text)!=1) continue;
            Object x=row.get("x"),y=row.get("y");
            if(!(x instanceof Integer) || !(y instanceof Integer)) continue;
            if((int)x<0 || (int)x>=720 || (int)y<0 || (int)y>=1280) continue;
            if(picker && FILE.matcher(text).matches()) add(new Target("select_file",text,null,null,(int)x,(int)y));
            if(!picker && links<3 && (text.startsWith("https://") || text.startsWith("http://"))) {
                URI uri;
                try { uri=new URI(text); } catch(Exception invalid) { continue; }
                if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null) continue;
                links++;
                String component=shell.run("cmd","package","resolve-activity","--brief","-a","android.intent.action.VIEW","-c","android.intent.category.BROWSABLE","-d",text).trim();
                component=component.substring(component.lastIndexOf('\n')+1);
                if(!COMPONENT.matcher(component).matches()) continue;
                String pkg=component.substring(0,component.indexOf('/'));
                if(pkg.equals("android") || pkg.endsWith(".intentresolver") || pkg.endsWith(".permissioncontroller") || pkg.equals(mainApp) || pkg.startsWith("dev.droiduse.")) continue;
                add(new Target("open_link",text,component,text,0,0));
            }
        }
        for(Map.Entry<String,String> entry:apps.entrySet()) {
            if(targets.size()>=100) break;
            if(!entry.getKey().equals(mainApp) && !entry.getKey().equals(app)) add(new Target("open_app",entry.getKey(),entry.getValue(),null,0,0));
        }
        JSONArray result=new JSONArray();for(Target target:targets.values()) result.put(target.json());return result;
    }
    private void add(Target target) { targets.put(target.id,target); }
}
