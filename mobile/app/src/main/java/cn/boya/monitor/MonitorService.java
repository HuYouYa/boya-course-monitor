package cn.boya.monitor;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.view.View;
import android.widget.Toast;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class MonitorService extends Service {
    public static final String SITE_URL = "https://bykc.buaa.edu.cn/#/tabs/%E9%80%89%E8%AF%BE/course-home";
    public static final String ACTION_RUN = "cn.boya.monitor.RUN";
    public static final String ACTION_TEST = "cn.boya.monitor.TEST";
    private static final String CHANNEL = "boya_monitor";
    private final Handler handler = new Handler();
    private WebView page;
    private boolean loading;
    private boolean started;
    private int waitCount;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "博雅课程监控", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("博雅课程监控").setContentText("手机正在检查课程更新").setOngoing(true);
        startForeground(1001, b.build());
        createPage();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createPage() {
        page = new WebView(this);
        page.setVisibility(View.VISIBLE);
        WebSettings s = page.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true); s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true);
        page.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { CookieManager.getInstance().flush(); handler.postDelayed(() -> advance(), 3000); }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) { sendTest(); }
        if (intent != null && ACTION_RUN.equals(intent.getAction())) { started = true; if (!loading) runCycle(); }
        return START_STICKY;
    }

    private void sendTest() {
        JSONObject c = ConfigStore.config(this); JSONObject x = new JSONObject();
        try { x.put("name", "博雅通知测试").put("start", now()).put("end", now()).put("select_start", now()).put("select_end", now()).put("category", "测试课程").put("campus", c.optString("campus")).put("position", "手机端测试").put("current_count", 0).put("max_count", 0); } catch (Exception ignored) {}
        new Thread(() -> { try { SmtpSender.send(c, x); setStatus(false, "测试通知已发送"); } catch (Exception e) { setStatus(false, "测试通知失败：" + e.getMessage()); } }).start();
    }

    private void runCycle() {
        JSONObject c = ConfigStore.config(this);
        if (c.optString("username").isEmpty() || c.optString("password").isEmpty()) { setStatus(false, "请先填写博雅账号和密码"); scheduleNext(); return; }
        ConfigStore.restoreWebCookies(this);
        loading = true; waitCount = 0; setStatus(true, "正在打开博雅课程页面…"); page.loadUrl(SITE_URL);
    }

    private void advance() {
        if (!loading) return;
        String script = "(function(){var rows=[...document.querySelectorAll('.course-table tbody tr,table tbody tr')].map(r=>[...r.querySelectorAll('td')].map(x=>(x.innerText||'').trim())).filter(r=>r.length>1);if(rows.length)return JSON.stringify({kind:'courses',rows:rows});var p=[...document.querySelectorAll('input[type=password]')].find(x=>x.offsetParent!==null);if(p)return JSON.stringify({kind:'login'});var b=[...document.querySelectorAll('button,a')].find(x=>(x.innerText||'').includes('点我立即跳转'));if(b){b.click();return JSON.stringify({kind:'jump'});}var target=[...document.querySelectorAll('li')].find(x=>x.getAttribute('href')==='/system/course-select');if(target){target.click();return JSON.stringify({kind:'nav'});}var n=[...document.querySelectorAll('.nav-main,li')].find(x=>(x.innerText||'').includes('我的课程'));if(n){n.click();return JSON.stringify({kind:'nav'});}return JSON.stringify({kind:'wait'});})()";
        page.evaluateJavascript(script, value -> {
            try {
                Object parsed = new JSONTokener(value).nextValue();
                JSONObject result = parsed instanceof String ? new JSONObject((String) parsed) : (JSONObject) parsed;
                String kind = result.optString("kind");
                if ("courses".equals(kind)) { loading = false; handleCourses(result.optJSONArray("rows")); }
                else if ("login".equals(kind)) { fillLogin(); }
                else if ("jump".equals(kind) || "nav".equals(kind)) retryAdvance();
                else retryAdvance();
            } catch (Exception e) { retryAdvance(); }
        });
    }

    private void fillLogin() {
        JSONObject c = ConfigStore.config(this);
        String u = JSONObject.quote(c.optString("username")), p = JSONObject.quote(c.optString("password"));
        String script = "(function(){var u=document.querySelector('input[name=username],input#username,input[type=text]'),p=document.querySelector('input[name=password],input#password,input[type=password]');if(u){u.value="+u+";u.dispatchEvent(new Event('input',{bubbles:true}));}if(p){p.value="+p+";p.dispatchEvent(new Event('input',{bubbles:true}));var b=document.querySelector('button[type=submit],input[type=submit],.submit-btn');if(b)b.click();}return 'ok';})()";
        page.evaluateJavascript(script, value -> { setStatus(true, "正在完成统一认证登录…"); retryAdvance(); });
    }
    private void retryAdvance() { waitCount++; if (waitCount > 40) { loading = false; String url = page == null ? "" : page.getUrl(); setStatus(false, "未能读取课程表。当前页面：" + url + "。请在“打开博雅登录”中确认已回到博雅课程首页，再启动监控。"); scheduleNext(); } else handler.postDelayed(this::advance, 1800); }

    private void handleCourses(JSONArray rows) {
        JSONObject config = ConfigStore.config(this); JSONArray accepted = new JSONArray(); int fetched = rows == null ? 0 : rows.length(); int changed = 0, notified = 0;
        try {
            JSONObject fingerprints = new JSONObject(getSharedPreferences("boya_local", MODE_PRIVATE).getString("fingerprints", "{}"));
            for (int i=0; i<fetched; i++) {
                JSONObject course = parseCourse(rows.getJSONArray(i));
                if (!matches(course, config)) continue;
                accepted.put(course); String fp = fingerprint(course); String old = fingerprints.optString(course.optString("id"), "");
                if (!fp.equals(old)) { changed++; sendNotification(config, course, fp, fingerprints); notified++; }
            }
            JSONObject status = ConfigStore.status(this); status.put("running", true).put("last_run", now()).put("error", "").put("message", "抓取 "+fetched+" 门，符合条件 "+accepted.length()+" 门，更新 "+changed+" 门，已通知 "+notified+" 门").put("courses", accepted); ConfigStore.status(this, status);
        } catch (Exception e) { setStatus(false, "课程解析失败：" + e.getMessage()); }
        scheduleNext();
    }

    private void sendNotification(JSONObject config, JSONObject course, String fp, JSONObject fingerprints) {
        new Thread(() -> { try { SmtpSender.send(config, course); fingerprints.put(course.optString("id"), fp); getSharedPreferences("boya_local", MODE_PRIVATE).edit().putString("fingerprints", fingerprints.toString()).apply(); } catch (Exception e) { setStatus(true, "通知失败（将在下一轮重试）：" + e.getMessage()); } }).start();
    }

    private JSONObject parseCourse(JSONArray cells) throws Exception {
        String name = cell(cells,1), category=cell(cells,2), position=cell(cells,3), activity=cell(cells,4), campusText=cell(cells,5), select=cell(cells,6);
        JSONObject x = new JSONObject(); x.put("id", sha1(name+activity+position+select)); x.put("name", name); x.put("category", category); x.put("position", position); x.put("teacher", position); x.put("status", cell(cells,0)); x.put("campus", campus(campusText)); x.put("start", pair(activity,0)); x.put("end", pair(activity,1)); x.put("select_start", pair(select,0)); x.put("select_end", pair(select,1));
        String count=cell(cells,8); if(count.contains("/")){String[] a=count.split("/",2);x.put("current_count",integer(a[0]));x.put("max_count",integer(a[1]));} else {x.put("current_count",integer(count));x.put("max_count",JSONObject.NULL);} return x;
    }
    private String cell(JSONArray a,int i){return i<a.length()?a.optString(i,"").replaceAll("\\s+"," ").trim():"";}
    private Object integer(String s){try{return Integer.parseInt(s.replaceAll("[^0-9]",""));}catch(Exception e){return JSONObject.NULL;}}
    private JSONArray campus(String s)throws Exception{JSONArray a=new JSONArray(); if(!s.contains("全部")&&!s.contains("不限")&&!s.isEmpty()){Matcher m=Pattern.compile("校区[:：]\\s*([^ ]+)").matcher(s);a.put(m.find()?m.group(1):s.replace("校园：","").replace("校园:",""));}return a;}
    private String pair(String s,int index){Matcher m=Pattern.compile("20\\d{2}[年./-]\\d{1,2}[月./-]\\d{1,2}(?:日)?\\s*\\d{1,2}[:：]\\d{2}").matcher(s);int n=0;while(m.find()){if(n++==index)return formatDate(m.group());}return "";}
    private String formatDate(String s){String z=s.replace('年','-').replace('月','-').replace("日","").replace('：',':').replace('/','-').replace('.','-').replaceAll("\\s+"," ");for(String f:new String[]{"yyyy-M-d HH:mm","yyyy-MM-dd HH:mm"})try{return new SimpleDateFormat(f,Locale.CHINA).format(new SimpleDateFormat(f,Locale.CHINA).parse(z));}catch(Exception ignored){}return "";}
    private int hour(String s){try{return Integer.parseInt(s.substring(11,13));}catch(Exception e){return -1;}}
    private boolean matches(JSONObject x, JSONObject c) {
        try {
            String loc = x.optString("position") + x.optString("campus");
            boolean online = loc.contains("线上") || loc.contains("在线") || loc.contains("网络");
            if ("online".equals(c.optString("delivery_mode"))) return online;
            if (online) return false;
            String wanted = c.optString("campus");
            if (!wanted.isEmpty()) {
                boolean campusFound = loc.contains(wanted);
                if ("学院路".equals(wanted)) campusFound = campusFound || loc.matches(".*J\\d+.*");
                if ("沙河".equals(wanted)) campusFound = campusFound || loc.matches(".*SH\\d+.*");
                if (!campusFound && !loc.contains("全部") && !loc.contains("不限")) return false;
            }
            Date st = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(x.optString("start"));
            Date en = x.optString("end").isEmpty() ? new Date(st.getTime() + 3600000) : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(x.optString("end"));
            Calendar q = Calendar.getInstance(); q.setTime(st);
            while (q.getTime().before(en)) {
                if (!free(c.optJSONObject("free_ranges"), q.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY, q.get(Calendar.HOUR_OF_DAY))) return false;
                q.add(Calendar.HOUR_OF_DAY, 1);
            }
            return true;
        } catch (Exception e) { return false; }
    }
    private boolean free(JSONObject ranges,int day,int hour){if(ranges==null)return false;String s=ranges.optString(String.valueOf(day),"");for(String p:s.split("[,，、;；]+")){Matcher m=Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*[-~至到]\\s*(\\d{1,2})(?::(\\d{2}))?").matcher(p.trim());if(m.matches()){int a=Integer.parseInt(m.group(1)),b=Integer.parseInt(m.group(3));if(hour>=a&&hour<b)return true;}}return false;}
    private String fingerprint(JSONObject x)throws Exception{return sha1(x.toString());}
    private String sha1(String s){try{byte[] b=MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));StringBuilder z=new StringBuilder();for(byte x:b)z.append(String.format("%02x",x));return z.substring(0,16);}catch(Exception e){return String.valueOf(s.hashCode());}}
    private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date());}
    private void setStatus(boolean running,String message){try{JSONObject s=ConfigStore.status(this);s.put("running",running).put("error",running?"":message).put("message",message);ConfigStore.status(this,s);}catch(Exception ignored){}}
    private void scheduleNext(){if(started){int sec=Math.max(60,ConfigStore.config(this).optInt("poll_interval",300));handler.removeCallbacksAndMessages(null);handler.postDelayed(this::runCycle,sec*1000L);}}

    @Override public void onDestroy(){started=false;handler.removeCallbacksAndMessages(null);if(page!=null)page.destroy();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}

    private static class SmtpSender {
        static void send(JSONObject cfg,JSONObject course)throws Exception{String host=cfg.optString("smtp_host"),user=cfg.optString("smtp_username"),pass=cfg.optString("smtp_password"),to="qq".equals(cfg.optString("notify_channel"))?cfg.optString("qq_to"):cfg.optString("smtp_to");if(host.isEmpty()||user.isEmpty()||pass.isEmpty()||to.isEmpty())throw new Exception("请先完成 SMTP 配置");if(!user.contains("@")){if(host.toLowerCase(Locale.ROOT).contains("qq.com"))user+="@qq.com";else if(host.toLowerCase(Locale.ROOT).contains("163.com"))user+="@163.com";else if(host.toLowerCase(Locale.ROOT).contains("126.com"))user+="@126.com";}int port=cfg.optInt("smtp_port",465);Socket socket=port==465?((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(host,port):new Socket(host,port);BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));BufferedWriter w=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),"UTF-8"));read(r);cmd(w,r,"EHLO boya-monitor",250);if(port!=465){cmd(w,r,"STARTTLS",220);socket=((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,host,port,true);r=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));w=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),"UTF-8"));cmd(w,r,"EHLO boya-monitor",250);}cmd(w,r,"AUTH LOGIN",334);cmd(w,r,Base64.encodeToString(user.getBytes("UTF-8"),Base64.NO_WRAP),334);cmd(w,r,Base64.encodeToString(pass.getBytes("UTF-8"),Base64.NO_WRAP),235);String from=user;cmd(w,r,"MAIL FROM:<"+user+">",250);for(String target:to.split("[,;，；]"))cmd(w,r,"RCPT TO:<"+target.trim()+">",250);cmd(w,r,"DATA",354);String subject="=?UTF-8?B?"+Base64.encodeToString(("[博雅课程提醒] "+course.optString("name")).getBytes("UTF-8"),Base64.NO_WRAP)+"?=";String body="课程："+course.optString("name")+"\n开课时间："+course.optString("start")+" - "+course.optString("end")+"\n预选时间："+course.optString("select_start")+" - "+course.optString("select_end")+"\n课程分类："+course.optString("category")+"\n校区："+course.optString("campus")+"\n上课人数："+course.optString("current_count")+" / "+course.optString("max_count")+"\n上课地点："+course.optString("position");w.write("From: <"+from+">\r\nTo: "+to+"\r\nSubject: "+subject+"\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"+body.replace("\n","\r\n")+"\r\n.\r\n");w.flush();expect(read(r),250);w.write("QUIT\r\n");w.flush();socket.close();}
        static void cmd(BufferedWriter w,BufferedReader r,String s,int code)throws Exception{w.write(s+"\r\n");w.flush();expect(read(r),code);}
        static String read(BufferedReader r)throws Exception{String first=r.readLine();if(first==null)throw new Exception("SMTP 连接断开");StringBuilder x=new StringBuilder(first);while(first.length()>3&&first.charAt(3)=='-'){first=r.readLine();x.append(" ").append(first);}return x.toString();}
        static void expect(String s,int code)throws Exception{if(!s.startsWith(String.valueOf(code)))throw new Exception("SMTP 返回 "+s);}
    }
}
