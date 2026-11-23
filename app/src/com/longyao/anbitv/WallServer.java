package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 扫码上传壁纸用的临时局域网服务：
 * 只在「扫码上传」窗口打开期间监听一个随机端口，窗口关闭 / 上传完成 / 超时立刻关掉，绝不留常开端口。
 * 地址里带一次性随机令牌，令牌随服务重启而变，过期即失效。
 */
public class WallServer {

    public interface Listener {
        void onStarted(String url, int seconds);      // 服务起来了，给出二维码内容
        void onOpened();                              // 手机打开了上传页
        void onProgress(int pct);                     // 接收进度 0~100
        void onFile(File f, String mime, long bytes); // 收到并校验通过的图片
        void onError(String msg);
        void onExpired();
    }

    /** 单张图最大 32MB */
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    private final Context app;
    private final Listener cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ServerSocket ss;
    private String token = "";
    private volatile boolean running;
    private long expireAt;
    private int port;
    private boolean openedOnce;
    private final int ttlSec;

    public WallServer(Context c, int ttlSec, Listener l) {
        this.app = c.getApplicationContext();
        this.ttlSec = ttlSec <= 0 ? 180 : ttlSec;
        this.cb = l;
    }

    public boolean isRunning() { return running; }
    public String token() { return token; }

    /** 剩余秒数（窗口倒计时用） */
    public int leftSec() {
        long left = expireAt - System.currentTimeMillis();
        int s = (int) (left / 1000);
        return s < 0 ? 0 : s;
    }

    public synchronized void start() {
        stop();
        try {
            token = randomHex(24);
            ss = new ServerSocket(0);                 // 随机空闲端口
            ss.setReuseAddress(true);
            port = ss.getLocalPort();
            expireAt = System.currentTimeMillis() + ttlSec * 1000L;
            running = true;
            openedOnce = false;
            final String url = url();
            android.util.Log.i("WallServer", "started " + url);
            ui.post(new Runnable() {
                @Override public void run() { if (cb != null) cb.onStarted(url, ttlSec); }
            });
            Thread t = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "wall-upload");
            t.setDaemon(true);
            t.start();
            // 到点自动关闭（不弹任何东西，只让窗口显示「已过期，请刷新」）
            Thread exp = new Thread(new Runnable() {
                @Override public void run() {
                    while (running) {
                        if (System.currentTimeMillis() >= expireAt) {
                            stop();
                            ui.post(new Runnable() {
                                @Override public void run() { if (cb != null) cb.onExpired(); }
                            });
                            return;
                        }
                        try { Thread.sleep(500); } catch (Throwable ignored) { return; }
                    }
                }
            }, "wall-expire");
            exp.setDaemon(true);
            exp.start();
        } catch (Throwable e) {
            running = false;
            closeQuiet(ss);
            ss = null;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (cb != null) cb.onError(L.t("无法开启上传服务：") + e.getMessage());
                }
            });
        }
    }

    public synchronized void stop() {
        running = false;
        closeQuiet(ss);
        ss = null;
    }

    private void acceptLoop() {
        ServerSocket s0 = ss;
        if (s0 == null) return;
        while (running) {
            Socket c = null;
            try {
                c = s0.accept();
            } catch (Throwable e) {
                return;
            }
            final Socket cs = c;
            Thread th = new Thread(new Runnable() {
                @Override public void run() { handle(cs); }
            }, "wall-req");
            th.setDaemon(true);
            th.start();
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String reqLine = readLine(in);
            if (reqLine == null) { closeQuiet(s); return; }
            String method = "", path = "";
            String[] parts = reqLine.split(" ");
            if (parts.length >= 2) { method = parts[0]; path = parts[1]; }
            Map<String, String> head = new HashMap<String, String>();
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && line.length() > 0 && guard++ < 60) {
                int p = line.indexOf(':');
                if (p > 0) head.put(line.substring(0, p).trim().toLowerCase(), line.substring(p + 1).trim());
            }
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

            boolean okToken = checkToken(query);
            if (!okToken) {
                boolean expired = !running;
                send(out, expired ? 410 : 403, "application/json; charset=utf-8",
                        "{\"ok\":false,\"msg\":\"" + (expired ? "二维码已过期，请在电视上刷新" : "授权令牌无效") + "\"}");
                closeQuiet(s);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && ("/w".equals(path) || "/".equals(path))) {
                if (!openedOnce) {
                    openedOnce = true;
                    ui.post(new Runnable() {
                        @Override public void run() { if (cb != null) cb.onOpened(); }
                    });
                }
                send(out, 200, "text/html; charset=utf-8", page());
                closeQuiet(s);
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/upload".equals(path)) {
                long len = 0;
                String cl = head.get("content-length");
                if (cl != null) {
                    try { len = Long.parseLong(cl.trim()); } catch (Throwable ignored) { }
                }
                if (len <= 0) {
                    send(out, 400, "application/json; charset=utf-8", "{\"ok\":false,\"msg\":\"空文件\"}");
                    closeQuiet(s);
                    return;
                }
                if (len > MAX_BYTES) {
                    send(out, 413, "application/json; charset=utf-8",
                            "{\"ok\":false,\"msg\":\"图片超过 32MB，请压缩后再传\"}");
                    closeQuiet(s);
                    return;
                }
                File tmp = new File(app.getCacheDir(), "wall_up.tmp");
                String mime = saveBody(in, len, tmp);
                if ("!partial".equals(mime)) {
                    try { tmp.delete(); } catch (Throwable ignored) { }
                    send(out, 400, "application/json; charset=utf-8",
                            "{\"ok\":false,\"msg\":\"上传中断了，请在手机上重新点上传\"}");
                    closeQuiet(s);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (cb != null) cb.onError(L.t("上传中断了，请在手机上重新上传"));
                        }
                    });
                    return;
                }
                if (mime == null) {
                    try { tmp.delete(); } catch (Throwable ignored) { }
                    send(out, 415, "application/json; charset=utf-8",
                            "{\"ok\":false,\"msg\":\"只支持 JPG / PNG / WEBP 图片\"}");
                    closeQuiet(s);
                    return;
                }
                final File ff = tmp;
                final String mm = mime;
                final long bb = tmp.length();
                send(out, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"bytes\":" + bb + "}");
                closeQuiet(s);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onFile(ff, mm, bb);
                    }
                });
                return;
            }

            send(out, 404, "application/json; charset=utf-8", "{\"ok\":false,\"msg\":\"not found\"}");
            closeQuiet(s);
        } catch (Throwable e) {
            closeQuiet(s);
        }
    }

    private boolean checkToken(String query) {
        if (!running) return false;
        if (System.currentTimeMillis() > expireAt) return false;
        for (String kv : query.split("&")) {
            int p = kv.indexOf('=');
            if (p > 0 && "t".equals(kv.substring(0, p))) {
                return token.equals(kv.substring(p + 1));
            }
        }
        return false;
    }

    /** 读 body 落盘，并按文件头判断格式；返回 mime，非图片返回 null */
    private String saveBody(InputStream in, long len, File dst) {
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            long got = 0;
            int lastPct = -1;
            byte[] head4 = new byte[4];
            int headN = 0;
            while (got < len) {
                int want = (int) Math.min(buf.length, len - got);
                int n = in.read(buf, 0, want);
                if (n <= 0) break;
                if (headN < 4) {
                    for (int i = 0; i < n && headN < 4; i++) head4[headN++] = buf[i];
                }
                fo.write(buf, 0, n);
                got += n;
                int pct = (int) (got * 100L / len);
                if (pct != lastPct && pct % 5 == 0) {
                    lastPct = pct;
                    final int fp = pct;
                    ui.post(new Runnable() {
                        @Override public void run() { if (cb != null) cb.onProgress(fp); }
                    });
                }
            }
            fo.flush();
            if (got < len) return "!partial";             // 上传中断
            String mime = sniff(head4, headN);
            return mime;
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuiet(fo);
        }
    }

    /** 只认 JPG / PNG / WEBP 的文件头（byte 有符号，必须 &0xFF 再比） */
    private static String sniff(byte[] b, int n) {
        if (n < 4) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50 && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47)
            return "image/png";
        if ((b[0] & 0xFF) == 0x52 && (b[1] & 0xFF) == 0x49 && (b[2] & 0xFF) == 0x46 && (b[3] & 0xFF) == 0x46)
            return "image/webp";
        return null;
    }

    private String url() {
        return "http://" + localIp() + ":" + port + "/w?t=" + token;
    }

    /** 电视在局域网里的 IPv4（优先 wlan0/eth0，跳过回环与 169.254） */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("p2p") || name.startsWith("rmnet") || name.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) return ip;
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
                    return ip;
                }
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }

    private static String readLine(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                if (c != '\r') bo.write(c);
                if (bo.size() > 4096) break;
            }
            if (c < 0 && bo.size() == 0) return null;
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    private static void send(OutputStream out, int code, String type, String body) {
        try {
            byte[] b = body.getBytes("UTF-8");
            String h = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(h.getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Throwable ignored) { }
    }

    private static void closeQuiet(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Throwable ignored) { }
    }

    private static String randomHex(int bytes) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes; i++) sb.append(String.format("%02x", r.nextInt(256)));
        return sb.toString();
    }

    /** 生成二维码位图（zxing 已在工程依赖里） */
    public static Bitmap qr(String text, int size) {
        try {
            Map<com.google.zxing.EncodeHintType, Object> hints =
                    new HashMap<com.google.zxing.EncodeHintType, Object>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    px[off + x] = m.get(x, y) ? 0xFF101010 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 手机端上传页（单文件，无外部资源，不依赖第三方） */
    private static String page() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=zh-CN><head><meta charset=utf-8>");
        sb.append("<meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1'>");
        sb.append("<title>上传壁纸</title><style>");
        sb.append("*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}");
        sb.append("body{margin:0;padding:20px 18px 34px;background:#0c0f14;color:#eef1f5;");
        sb.append("font:15px/1.5 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif}");
        sb.append("h1{font-size:19px;margin:2px 0 4px;font-weight:700}");
        sb.append(".sub{color:#8f9aa8;font-size:13px;margin-bottom:18px}");
        sb.append(".card{background:#151a21;border:1px solid #232b35;border-radius:12px;padding:14px;margin-bottom:14px}");
        sb.append(".pick{display:block;width:100%;text-align:center;padding:16px;border:1px dashed #3a4553;");
        sb.append("border-radius:10px;color:#cfe4ff;background:#10151b;font-size:14px}");
        sb.append("#f{display:none}");
        sb.append("#prev{width:100%;border-radius:8px;display:none;margin-top:12px;background:#0a0d11}");
        sb.append(".meta{color:#8f9aa8;font-size:12px;margin-top:10px;display:none;word-break:break-all}");
        sb.append("button{width:100%;border:0;border-radius:10px;padding:15px;font-size:16px;font-weight:700;");
        sb.append("color:#08110c;background:#38d39f;margin-top:14px}button:disabled{background:#2a3540;color:#5d6b7a}");
        sb.append(".bar{height:6px;background:#202832;border-radius:4px;margin-top:14px;overflow:hidden;display:none}");
        sb.append(".bar i{display:block;height:100%;width:0;background:#38d39f;transition:width .12s linear}");
        sb.append(".st{font-size:13px;color:#9aa6b4;margin-top:10px;min-height:19px}");
        sb.append(".ok{color:#38d39f}.bad{color:#ff6b6b}");
        sb.append("</style></head><body>");
        sb.append("<h1>上传电视壁纸</h1>");
        sb.append("<div class=sub>从相册选一张图，传完回电视点「立即应用」</div>");
        sb.append("<div class=card>");
        sb.append("<label class=pick for=f>点击选择相册图片<div style='font-size:12px;color:#7d8896;margin-top:6px'>支持 JPG / PNG / WEBP，最大 32MB</div></label>");
        sb.append("<input id=f type=file accept='image/jpeg,image/png,image/webp'>");
        sb.append("<img id=prev alt=''>");
        sb.append("<div class=meta id=meta></div>");
        sb.append("<button id=go disabled>上传壁纸</button>");
        sb.append("<div class=bar id=bar><i id=bi></i></div>");
        sb.append("<div class=st id=st></div>");
        sb.append("</div></body><script>");
        sb.append("function Q(n){var m=new RegExp('[?&]'+n+'=([^&]*)');var r=m.exec(location.search);return r?r[1]:''}");
        sb.append("var f=document.getElementById('f'),pv=document.getElementById('prev'),");
        sb.append("mt=document.getElementById('meta'),go=document.getElementById('go'),");
        sb.append("bar=document.getElementById('bar'),bi=document.getElementById('bi'),st=document.getElementById('st');");
        sb.append("var file=null;f.onchange=function(){file=f.files[0];if(!file)return;");
        sb.append("if(!/^image\\/(jpeg|png|webp)$/.test(file.type)){st.className='st bad';st.textContent='不支持该格式，请选 JPG / PNG / WEBP';go.disabled=true;pv.style.display='none';return}");
        sb.append("pv.src=URL.createObjectURL(file);pv.style.display='block';");
        sb.append("mt.style.display='block';mt.textContent=file.name+'  ·  '+(file.size/1048576).toFixed(2)+'MB';");
        sb.append("go.disabled=false;st.className='st';st.textContent='';bi.style.width='0';");
        sb.append("if(file.size>33554432){st.className='st bad';st.textContent='图片超过 32MB，请先压缩';go.disabled=true}};");
        sb.append("go.onclick=function(){if(!file)return;if(file.size>33554432){st.className='st bad';st.textContent='图片超过 32MB';return}");
        sb.append("go.disabled=true;bar.style.display='block';st.className='st';st.textContent='正在上传…';");
        sb.append("var x=new XMLHttpRequest();x.open('POST','/upload?t='+encodeURIComponent(Q('t')));");
        sb.append("x.setRequestHeader('Content-Type',file.type||'application/octet-stream');");
        sb.append("x.upload.onprogress=function(e){if(e.lengthComputable){var p=Math.round(e.loaded*100/e.total);bi.style.width=p+'%';st.textContent='正在上传 '+p+'%'}};");
        sb.append("x.onload=function(){bar.style.display='none';var ok=false;try{ok=JSON.parse(x.responseText).ok===true}catch(e){}");
        sb.append("if(ok){st.className='st ok';st.textContent='已发送到电视，请回到电视点「立即应用」';go.textContent='重新选择';go.disabled=false}");
        sb.append("else{st.className='st bad';st.textContent='上传失败：'+(x.responseText||('HTTP '+x.status));go.disabled=false}};");
        sb.append("x.onerror=function(){bar.style.display='none';st.className='st bad';st.textContent='网络中断：手机与电视要在同一个 Wi-Fi 下';go.disabled=false};");
        sb.append("x.send(file)};");
        sb.append("</script></html>");
        return sb.toString();
    }
}
