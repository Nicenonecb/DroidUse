package dev.droiduse.probe;

import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Debug shell-UID transport on the PHONE. No desktop server or adb subprocess at runtime. */
public final class PhoneBridge {
    private static final String PACKAGE = "com.dragon.read";
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2);
    private final String token;
    private String session, mac, frame;
    private int display;
    private long started, captured, lastHeartbeat, budgetMs=240000;
    private static final long LEASE_MS=30000;
    private Process host;
    private PrintWriter commands;
    private BlockingQueue<String> output = new LinkedBlockingQueue<>(256);
    private final Set<String> requests = new HashSet<>();
    private final PhoneTargets targets=new PhoneTargets(PhoneBridge::command);
    private final Set<String> sessionApps=new HashSet<>();
    private String frameApp;
    private byte[] frameDigest;
    PhoneBridge(String token) { this.token=token; }
    private static String command(String... args) throws Exception {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ScheduledFuture<?> timeout=TIMER.schedule(p::destroyForcibly,20,TimeUnit.SECONDS);
        try {
            byte[] bytes=p.getInputStream().readNBytes(2_000_000);
            if(!p.waitFor(20,TimeUnit.SECONDS)) throw new IOException("command timeout");
            if(p.exitValue()!=0) throw new IOException("phone command failed: "+args[0]);
            return new String(bytes,StandardCharsets.UTF_8).trim();
        } finally { timeout.cancel(false); if(p.isAlive()) p.destroyForcibly(); }
    }
    private String foregroundGuard() throws Exception {
        if(command("dumpsys","window","policy").contains("mIsShowing=true")) throw new IOException("PHONE_LOCKED");
        String activities=command("dumpsys","activity","activities");
        String main=DisplayActivities.resumedPackage(activities,0);
        if(PACKAGE.equals(main) || sessionApps.contains(main)) throw new IOException("APP_USED_ON_MAIN_DISPLAY");
        return DisplayActivities.resumedPackage(activities,display);
    }
    private String await(String prefix) throws Exception {
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end) {
            String line=output.poll(1,TimeUnit.SECONDS);
            if(line==null) { if(!host.isAlive()) throw new IOException("HOST_EXITED"); continue; }
            if(line.contains("INJECTION_ERROR") || line.contains("FRAME_ERROR") || line.equals("EXIT")) throw new IOException("HOST_PROTECTION_LOST");
            if(line.startsWith(prefix)) return line;
        }
        throw new IOException("HOST_TIMEOUT");
    }
    private String healthy() throws Exception {
        if(session==null || host==null || !host.isAlive() || display<=0 || SystemClock.elapsedRealtime()-started>=budgetMs) throw new IOException("SESSION_NOT_READY");
        for(String line:output) if(line.contains("INJECTION_ERROR") || line.contains("FRAME_ERROR") || line.equals("EXIT")) throw new IOException("HOST_PROTECTION_LOST");
        return foregroundGuard();
    }
    private synchronized JSONObject begin() throws Exception {
        if(session!=null) throw new IOException("BUSY");
        foregroundGuard();started=SystemClock.elapsedRealtime();output=new LinkedBlockingQueue<>(256);requests.clear();
        targets.clear();sessionApps.clear();sessionApps.add(PACKAGE);targets.loadApps();
        mac=String.format("02:00:00:%02x:%02x:%02x",new java.security.SecureRandom().nextInt(256),new java.security.SecureRandom().nextInt(256),new java.security.SecureRandom().nextInt(256));
        try {
            command("cmd","companiondevice","associate","0","com.android.shell",mac,"android.app.role.COMPANION_DEVICE_APP_STREAMING","true");
            String id=null;
            for(String line:command("cmd","companiondevice","list","0").split("\n")) if(line.toLowerCase(Locale.ROOT).contains(mac)) {
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\d+").matcher(line);if(m.find()) id=m.group();
            }
            if(id==null) throw new IOException("ASSOCIATION_NOT_FOUND");
            ProcessBuilder builder=new ProcessBuilder("/system/bin/app_process","/","dev.droiduse.probe.DisplayHost",id).redirectErrorStream(true);
            builder.environment().put("CLASSPATH","/data/local/tmp/droiduse-phone.jar");host=builder.start();
            final Process child=host;final BlockingQueue<String> events=output;
            new Thread(() -> {
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;while((line=reader.readLine())!=null) if(!events.offer(line)) child.destroyForcibly();
                } catch(IOException ignored) { } finally { events.offer("EXIT"); }
            },"phone-display-output").start();
            commands=new PrintWriter(host.getOutputStream(),true);
            display=Integer.parseInt(await("DISPLAY_ID=").split("=")[1]);if(display<=0) throw new IOException("PRIMARY_DISPLAY_REFUSED");
            commands.println("deny-reader-mic");await("DEVICE_MIC_REVOKE_RETURNED=");
            String resolved=command("cmd","package","resolve-activity","--brief",PACKAGE);
            String component=resolved.substring(resolved.lastIndexOf('\n')+1);
            if(!component.startsWith(PACKAGE+"/")) throw new IOException("BAD_COMPONENT");
            command("am","start","-W","--display",Integer.toString(display),"-n",component);
            boolean resumed=false;
            long launchDeadline=SystemClock.elapsedRealtime()+3000;
            while(SystemClock.elapsedRealtime()<launchDeadline) {
                if(PACKAGE.equals(foregroundGuard())) { resumed=true;break; }
                Thread.sleep(100);
            }
            if(!resumed) throw new IOException("TARGET_APP_NOT_RESUMED");
            session=UUID.randomUUID().toString();
            return new JSONObject().put("ready",true).put("backend","PHONE_SHELL_EXPERIMENT").put("sessionId",session).put("supportedActions",new org.json.JSONArray(Arrays.asList("tap","swipe","back","double_tap","long_press","drag","multi_touch","select_file","open_app","open_link","select_file_at")));
        } catch(Exception e) { cancel();throw e; }
    }
    private synchronized JSONObject observe() throws Exception {
        String app=healthy();byte[] png=capturePixels();
        if(app==null || !app.equals(foregroundGuard())) throw new IOException("OBSERVATION_APP_CHANGED_OR_UNKNOWN");
        frame=UUID.randomUUID().toString();captured=SystemClock.elapsedRealtime();
        frameApp=app;frameDigest=MessageDigest.getInstance("SHA-256").digest(png);targets.clear();
        return new JSONObject().put("id",frame).put("display",display).put("width",720).put("height",1280).put("rotation",0)
            .put("capturedAt",captured).put("app",app).put("pngBase64",Base64.encodeToString(png,Base64.NO_WRAP));
    }
    private byte[] capturePixels() throws Exception {
        commands.println("capture");await("CAPTURED");
        Path capture=Path.of("/data/local/tmp/droiduse-probe.png");
        byte[] png;
        try { png=Files.readAllBytes(capture); } finally { Files.deleteIfExists(capture); }
        if(png.length>3_000_000) throw new IOException("FRAME_TOO_LARGE");
        return png;
    }
    private JSONObject enumerateTargets(JSONObject body) throws Exception {
        String app=healthy();
        if(!Objects.equals(frame,body.getString("frameId")) || !Objects.equals(frameApp,app) || SystemClock.elapsedRealtime()-captured>30000)
            throw new IOException("STALE_TARGET_FRAME");
        org.json.JSONArray rows=body.getJSONArray("rows");if(rows.length()>120) throw new IOException("TARGET_LIMIT");
        String activities=command("dumpsys","activity","activities");
        return new JSONObject().put("frameId",frame).put("targets",targets.enumerate(app,DisplayActivities.resumedPackage(activities,0),rows))
            .put("scopedActions",new org.json.JSONArray(PhoneTargets.isPicker(app) ? Arrays.asList("select_file_at") : Collections.emptyList()));
    }
    private String executeTarget(JSONObject action) throws Exception {
        PhoneTargets.Target target=targets.get(action.getString("targetId"));
        if(target==null || !target.kind.equals(action.getString("kind"))) return "STALE_OBSERVATION";
        if(!Objects.equals(frameApp,healthy()) || !MessageDigest.isEqual(frameDigest,MessageDigest.getInstance("SHA-256").digest(capturePixels()))) return "STALE_OBSERVATION";
        if(target.kind.equals("select_file")) {
            // Native DocumentsUI owns URI permissions and delivers ActivityResult to its caller.
            command("input","-d",Integer.toString(display),"tap",Integer.toString(target.x),Integer.toString(target.y));
            return "EXECUTED"; // A click receipt only; the next observation must verify selection/return.
        }
        String pkg=target.packageName();
        String main=DisplayActivities.resumedPackage(command("dumpsys","activity","activities"),0);
        if(pkg.equals(main)) return "ISOLATION_LOST";
        commands.println("deny-package-mic "+pkg);await("DEVICE_MIC_REVOKE_RETURNED=");
        sessionApps.add(pkg);
        List<String> args=new ArrayList<>(Arrays.asList("am","start","-W","--display",Integer.toString(display),"-f","0x18000000","-n",target.component));
        if(target.uri!=null) Collections.addAll(args,"-a","android.intent.action.VIEW","-c","android.intent.category.BROWSABLE","-d",target.uri);
        else Collections.addAll(args,"-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER");
        command(args.toArray(new String[0]));
        long until=SystemClock.elapsedRealtime()+3000;
        while(SystemClock.elapsedRealtime()<until) {
            try { if(pkg.equals(healthy())) return "EXECUTED"; }
            catch(IOException error) {
                if("APP_USED_ON_MAIN_DISPLAY".equals(error.getMessage()) || "HOST_PROTECTION_LOST".equals(error.getMessage())) return "ISOLATION_LOST";
                throw error;
            }
            Thread.sleep(100);
        }
        return "UNKNOWN_OUTCOME";
    }
    private static int integer(JSONObject a,String key) throws Exception {
        Object v=a.get(key);if(!(v instanceof Integer)) throw new IOException("INTEGER_REQUIRED");return (Integer)v;
    }
    private static void point(int x,int y) throws IOException { if(x<0 || x>=720 || y<0 || y>=1280) throw new IOException("POINT_OUT_OF_BOUNDS"); }
    private synchronized JSONObject action(JSONObject body) throws Exception {
        healthy();String request=body.getString("requestId");
        if(requests.contains(request)) return new JSONObject().put("code","UNKNOWN_OUTCOME");
        if(!Objects.equals(frame,body.getString("frameId")) || SystemClock.elapsedRealtime()-captured>30000) return new JSONObject().put("code","STALE_OBSERVATION");
        JSONObject a=body.getJSONObject("action");String d=Integer.toString(display);String kind=a.getString("kind");
        if(request.length()>100 || requests.size()>1000) throw new IOException("REQUEST_LIMIT");
        requests.add(request);
        if(kind.equals("select_file") || kind.equals("open_app") || kind.equals("open_link")) {
            String outcome;
            try { outcome=executeTarget(a); }
            finally { targets.clear();frame=null;frameDigest=null; }
            return new JSONObject().put("code",outcome);
        }
        switch(kind) {
            case "select_file_at": {
                if(!PhoneTargets.isPicker(frameApp) || !Objects.equals(frameApp,healthy())) return new JSONObject().put("code","UNSUPPORTED");
                if(!MessageDigest.isEqual(frameDigest,MessageDigest.getInstance("SHA-256").digest(capturePixels()))) return new JSONObject().put("code","STALE_OBSERVATION");
                int x=integer(a,"x"),y=integer(a,"y");point(x,y);
                command("input","-d",d,"tap",Integer.toString(x),Integer.toString(y));break;
            }
            case "tap": {
                int x=integer(a,"x"),y=integer(a,"y");point(x,y);
                command("input","-d",d,"tap",Integer.toString(x),Integer.toString(y));break;
            }
            case "multi_touch": {
                org.json.JSONArray paths=a.getJSONArray("fingers");
                int duration=integer(a,"durationMs");
                if(paths.length()!=2 || duration<100 || duration>3000) throw new IOException("GESTURE_SHAPE_INVALID");
                int count=paths.getJSONArray(0).length();
                if(count<2 || count>32 || paths.getJSONArray(1).length()!=count) throw new IOException("GESTURE_PATH_INVALID");
                int[][][] points=new int[2][count][2];
                for(int finger=0;finger<2;finger++) for(int i=0;i<count;i++) {
                    JSONObject p=paths.getJSONArray(finger).getJSONObject(i);
                    int x=integer(p,"x"),y=integer(p,"y");point(x,y);
                    points[finger][i][0]=x;points[finger][i][1]=y;
                }
                new MultiTouchGesture(display).perform(points,duration);break;
            }
            case "double_tap": {
                int x=integer(a,"x"),y=integer(a,"y");point(x,y);
                new TouchGesture(display).perform(true,x,y,x,y,40,0);break;
            }
            case "long_press": {
                int x=integer(a,"x"),y=integer(a,"y"),duration=integer(a,"durationMs");point(x,y);
                if(duration<500 || duration>3000) throw new IOException("DURATION_OUT_OF_BOUNDS");
                new TouchGesture(display).perform(false,x,y,x,y,duration,0);break;
            }
            case "drag": {
                int x1=integer(a,"x1"),y1=integer(a,"y1"),x2=integer(a,"x2"),y2=integer(a,"y2");
                int hold=integer(a,"holdMs"),duration=integer(a,"durationMs");point(x1,y1);point(x2,y2);
                if(hold<0 || hold>1500 || duration<100 || duration>3000) throw new IOException("DURATION_OUT_OF_BOUNDS");
                new TouchGesture(display).perform(false,x1,y1,x2,y2,hold,duration);break;
            }
            case "swipe": {
                int x1=integer(a,"x1"),y1=integer(a,"y1"),x2=integer(a,"x2"),y2=integer(a,"y2"),duration=integer(a,"durationMs");
                point(x1,y1);point(x2,y2);if(duration<100 || duration>2000) throw new IOException("DURATION_OUT_OF_BOUNDS");
                command("input","-d",d,"swipe",Integer.toString(x1),Integer.toString(y1),Integer.toString(x2),Integer.toString(y2),Integer.toString(duration));break;
            }
            case "back":command("input","-d",d,"keyevent","KEYCODE_BACK");break;
            default:return new JSONObject().put("code","UNSUPPORTED");
        }
        targets.clear();frame=null;frameDigest=null;
        return new JSONObject().put("code","EXECUTED");
    }
    private synchronized void cancel() {
        if(host!=null) {
            try { commands.println("quit");if(!host.waitFor(3,TimeUnit.SECONDS)) host.destroyForcibly(); }
            catch(Exception ignored) { host.destroyForcibly(); }
        }
        host=null;commands=null;session=null;display=0;frame=null;
        frameApp=null;frameDigest=null;targets.clear();sessionApps.clear();
        if(mac!=null) { try { command("cmd","companiondevice","disassociate","0","com.android.shell",mac); } catch(Exception ignored) { } mac=null; }
    }
    private synchronized JSONObject route(String path,JSONObject body) throws Exception {
        if(path.equals("/begin")) {
            long requested=body.optLong("budgetMs",240000);
            if(requested<1000 || requested>600000 || session!=null) throw new IOException("INVALID_BUDGET_OR_BUSY");
            budgetMs=requested;JSONObject result=begin();lastHeartbeat=SystemClock.elapsedRealtime();return result;
        }
        if(session==null || !session.equals(body.optString("sessionId"))) throw new IOException("INVALID_SESSION");
        if(SystemClock.elapsedRealtime()-lastHeartbeat>=LEASE_MS) { cancel();throw new IOException("CLIENT_LEASE_EXPIRED"); }
        switch(path) {
            case "/heartbeat":lastHeartbeat=SystemClock.elapsedRealtime();return new JSONObject().put("code","ALIVE");
            case "/observe":return observe();
            case "/targets":return enumerateTargets(body);
            case "/action":return action(body);
            case "/cancel":cancel();return new JSONObject().put("code","CANCELLED");
            default:throw new IOException("UNKNOWN_ROUTE");
        }
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1 && c!='\n') { if(out.size()>=2048) throw new IOException("HEADER_TOO_LONG");if(c!='\r') out.write(c); }
        if(c==-1) throw new EOFException();return out.toString(StandardCharsets.US_ASCII);
    }
    private void serve(Socket socket) {
        try(Socket s=socket) {
            s.setSoTimeout(5000);InputStream in=s.getInputStream();String[] request=line(in).split(" ");
            String auth="";int length=0,headers=0;String h;
            while(!(h=line(in)).isEmpty()) {
                if(++headers>30) throw new IOException("HEADERS_LIMIT");int colon=h.indexOf(':');if(colon<0) throw new IOException("BAD_HEADER");
                String name=h.substring(0,colon).toLowerCase(Locale.ROOT),value=h.substring(colon+1).trim();
                if(name.equals("authorization")) auth=value;if(name.equals("content-length")) length=Integer.parseInt(value);
            }
            if(request.length!=3 || !request[0].equals("POST") || length<0 || length>65536) throw new IOException("BAD_REQUEST");
            if(!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))) { reply(s,403,"{}");return; }
            byte[] bytes=in.readNBytes(length);if(bytes.length!=length) throw new EOFException();
            try { reply(s,200,route(request[1],new JSONObject(new String(bytes,StandardCharsets.UTF_8))).toString()); }
            catch(Exception e) { reply(s,409,new JSONObject().put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).toString()); }
        } catch(Exception ignored) { }
    }
    private static void reply(Socket socket,int status,String value) throws IOException {
        byte[] body=value.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
        out.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("Requires explicitly bootstrapped shell UID");
        String token=Files.readString(Path.of(args[0])).trim();if(token.length()<32) throw new SecurityException("Missing private token");
        PhoneBridge bridge=new PhoneBridge(token);
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::cancel));
        TIMER.scheduleAtFixedRate(() -> { synchronized(bridge) { if(bridge.session!=null && (SystemClock.elapsedRealtime()-bridge.started>=bridge.budgetMs || SystemClock.elapsedRealtime()-bridge.lastHeartbeat>=LEASE_MS || bridge.host==null || !bridge.host.isAlive())) bridge.cancel(); } },1,1,TimeUnit.SECONDS);
        ThreadPoolExecutor clients=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
        try(ServerSocket server=new ServerSocket(18765,16,InetAddress.getByName("127.0.0.1"))) {
            System.out.println("PHONE_BRIDGE_READY uid=2000 port=18765");
            while(true) { Socket socket=server.accept();try { clients.execute(() -> bridge.serve(socket)); } catch(RejectedExecutionException e) { socket.close(); } }
        } finally { bridge.cancel();clients.shutdownNow();TIMER.shutdownNow(); }
    }
}
