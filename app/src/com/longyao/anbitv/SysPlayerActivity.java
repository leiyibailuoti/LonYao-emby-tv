package com.longyao.anbitv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FFmpegOnlyRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

/**
 * 统一播放器内核：ExoPlayer（走系统 MediaCodec 硬解，4K/HEVC 不会像 libVLC 那样退软解）。
 * 老编码 / 解不出来的片源自动退回 libVLC（PlayerActivity）并继承进度。
 * HUD：标题、播放状态图标、时间、进度条、网速。
 */
public class SysPlayerActivity extends Activity {
    private SurfaceView surface;
    private android.view.TextureView tex;
    private android.view.Surface texSurface;
    private ExoPlayer player;
    /** 共用播放器界面层（顶部 HUD / 底部进度栏 / 音量条 / 提示气泡 / 统计卡） */
    private PlayerUI pui;
    private final Handler ui = new Handler();

    private String url, directUrl, titleS, itemId, server, token, userId, deviceId, playSessionId = "";
    /** 服务器报的原始媒体流（含序号），详情页存的「序号」键靠它换算成第几条 */
    private java.util.List<Api.MStream> srvStreams = null;
    /** 当前播的是某一集时，这部剧的 id（按上键弹选集用） */
    private String seriesId = "";
    private long startMs;
    private boolean prepared, paused, ended, bailing, seekDone, trackPrefDone;
    /** 硬解自称支持却实际解不了时置位：本次播放强制走 FFmpeg 纯软解重试 */
    private boolean forceFfmpeg;
    private float vFps = 0f;
    /** 首帧是否真的渲染出来过：播了几秒还没有首帧 = 硬解"自称支持"但其实出不来画面 */
    private volatile boolean firstFrameRendered = false;
    /** 字幕文件初始位置只在该轨字幕第一次出字时套一次，之后用户拖滑块不再被覆盖 */
    private volatile boolean nativeSubPosDone = false;
    /** 硬解出不来画面时，先用系统软解（PREFER_SOFTWARE）重试一次，再退回 libVLC */
    private boolean softMc = false, softMcTried = false;
    private long playStartedAt = 0L;
    /** 片源是不是这台机硬解不了的 VP9（10bit / Profile 2 HDR） */
    private boolean vp9Unsupported = false;
    /** 杜比视界硬解被判不支持时，把码流当 HEVC 基础层解（profile 7/8 才有基础层） */
    private static volatile boolean DV_TO_HEVC = false;
    private boolean dvFallbackTried = false;
    /** PGS 图形字幕改交 libVLC：把「自动选字幕」一并带过去 */
    private boolean bailAutoSub = false;
    /**
     * nextlib 的 FFmpeg 视频渲染器（FfmpegVideoRenderer）要求 dex 里有
     * androidx.media3.exoplayer.RendererCapabilities$_CC 这个默认方法伴生类；
     * 我们这包 media3 混淆过，实际生成的是 $-CC，缺了就一 instantiate 就 NoClassDefFoundError（直接闪退）。
     * 所以开机检测一次，没有就彻底不用 FFmpeg 视频解，解不了的片源统一交 libVLC。
     */
    private static final boolean ffVideoRendererOk;
    static {
        boolean ok = false;
        try {
            // nextlib 的 FFmpeg 渲染器写死引用 RendererCapabilities$_CC（下划线版伴生类）。
            // 我们自带了一个同名垫片类（app/src/androidx/media3/exoplayer），
            // 这里就按它的真实名字检测：有垫片 = FFmpeg 视频软解可用。
            Class.forName("androidx.media3.exoplayer.RendererCapabilities$_CC");
            ok = true;
        } catch (Throwable ignored) { }
        ffVideoRendererOk = ok;
    }
    /** FFmpeg 重试后连续检查到"无视频轨"的次数，用于判定 FFmpeg 编内没有该解码器 */
    private int ffmpegNoVideoTicks;
    /** 转码流的起点（毫秒）：渐进式转码流不能原地 seek，靠服务器从该点重新起转 */
    private long tcStartMs;
    /** mp4v(Xvid) 已切过一次 FFmpeg 软解，别反复重启 */
    private boolean mp4vRouted;
    /** 416 续播越界已经从 0 重开过一次 */
    private boolean zeroRetried;
    /** 直连 416 修不好时强制改走服务器转码 */
    private boolean tcForce;
    private long durMs, posMs;
    private long lastSave, statT, statB;
    /** 连续卡在 STATE_BUFFERING 的起点；0 表示当前不在缓冲 */
    private long bufferingSince;
    /** 卡缓冲看门狗是否已经触发过回退 */
    private boolean stallBailed;

    private VMenu menu;
    private Store pstore;
    private FrameLayout content;
    private int videoW, videoH;
    private SubCueView subCue;
    private int cueLog = 0;
    private float targetSpeed = 1f;
    private long speedGuardUntil = 0;

    /** 用户已经按返回/切走，窗口正在关闭：此时绝不能再自动"回退 libVLC"重开一个播放器 */
    private volatile boolean closing = false;
    /** 播放器内部数字音量（0~1）：切音轨 / AC3 直通时系统音量键会失效，用它兜住 */
    private float numVol = 1f;

    /** 当前活着的系统播放器窗口数（主界面用它区分"进播放器"和"真的切出去了"） */
    public static int alive = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        alive++;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        url = getIntent().getStringExtra("url");
        directUrl = url;
        titleS = getIntent().getStringExtra("title");
        itemId = getIntent().getStringExtra("itemId");
        server = getIntent().getStringExtra("server");
        token = getIntent().getStringExtra("token");
        userId = getIntent().getStringExtra("userId");
        deviceId = getIntent().getStringExtra("deviceId");
        String sid0 = getIntent().getStringExtra("seriesId");
        seriesId = sid0 == null ? "" : sid0;
        startMs = getIntent().getLongExtra("startMs", 0);
        long dh = getIntent().getLongExtra("durMs", 0);
        if (dh > 0) durMs = dh;      // 片长预置：进度条右端和快进边界

        pstore = new Store(this);
        curAspect = pstore.aspect();   // 画面比例沿用上次的选择，两个内核共用
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        content = new FrameLayout(this);
        // 渲染模式：0 = SurfaceView（解码直写，最省资源）/ 1 = TextureView（可做变换，代价是多一次拷贝）
        int rm0 = pstore.renderMode();
        if (rm0 == 1) {
            tex = new android.view.TextureView(this);
            tex.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    attachTexture();
                }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    releaseTexture();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
            });
            content.addView(tex, new FrameLayout.LayoutParams(-1, -1));
        } else {
            surface = new SurfaceView(this);
            content.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // 内嵌字幕渲染层（ExoPlayer 解析出的 cue 画在这里）
        subCue = new SubCueView(this);
        root.addView(subCue, new FrameLayout.LayoutParams(-1, -1));
        // 调试用：intent 带 fc=1 强制走转码，带 fc=0 强制直连，便于 adb 验证播放统计
        String fc = getIntent().getStringExtra("fc");
        if (fc != null) pstore.forceTranscode("1".equals(fc));
        url = buildUrl();
        // 两个内核共用同一套播放器界面
        pui = new PlayerUI(this, root, titleS, getIntent().getStringExtra("sub"));

        // 字幕层 + 右侧级联菜单（外观与 libVLC 版完全一致）
        menu = new VMenu(this, new VMenuHost());
        menu.attach(root);

        setContentView(root);

        open();
        menu.loadSubtitlesAsync();
        menu.overlayRefresh();
        ui.postDelayed(tick, 500);
        ui.postDelayed(subTick, 200);
        showHud();

        // 调试用：intent 带 autoSub=1 时自动列内嵌轨并选第一条，便于 adb 验证
        if (getIntent().getStringExtra("subDump") != null) {
            enableDump();
            try { menu.enableDump(); } catch (Throwable ignored) {}
        }
        if (getIntent().getStringExtra("autoSub") != null) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    String[] n = embedSubNamesImpl();
                    android.util.Log.e("LMBY", "内嵌自动测试 轨数=" + n.length
                            + L.t(" 名称=") + java.util.Arrays.toString(n) + L.t(" 当前=") + curEmbedSubImpl());
                    if (n.length > 0) {
                        selectEmbedSubImpl(0);
                        android.util.Log.e("LMBY", "内嵌自动测试 已选第 0 条，当前=" + curEmbedSubImpl());
                    }
                }
            }, 4000);
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    android.util.Log.e("LMBY", "内嵌自动测试 5s 后 cueView 字幕=" + (subCue == null ? "null" : "ok"));
                }
            }, 9000);
        }
        // 调试用：intent 带 autoStats=1 时自动弹出播放统计面板，便于 adb 验证版式
        if (getIntent().getStringExtra("autoStats") != null) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (menu != null) menu.statsNow();
                    } catch (Throwable e) {
                        android.util.Log.e("LMBY", "statsNow err " + e);
                    }
                }
            }, 8000);
        }
        // 调试用：intent 带 autoSpeed=1.5 时自动切倍速并观察是否被内核拉回
        String sp = getIntent().getStringExtra("autoSpeed");
        if (sp != null) {
            final float v = Float.parseFloat(sp);
            ui.postDelayed(new Runnable() {
                @Override public void run() { applySpeed(v); }
            }, 6000);
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    android.util.Log.e("LMBY", "倍速观察 目标=" + v + " 实际=" + curSpeed());
                }
            }, 8200);
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    android.util.Log.e("LMBY", "倍速观察(后段) 目标=" + v + " 实际=" + curSpeed());
                }
            }, 11000);
        }
    }

    // HUD 与统计卡统一由 PlayerUI 提供（与 libVLC 版同一套皮肤）

    private void open() {
        // 续播时转码流也要从上次位置起转，否则播的是开头
        if (isTranscoding() && tcStartMs <= 0 && startMs > 0) tcStartMs = startMs;
        pendingAudioIdx = -1;   // 换片/重建内核：音轨选择重新以 tracks 为准
        firstFrameRendered = false;
        nativeSubPosDone = false;
        playStartedAt = 0L;
        try {
            // 这台 Amlogic 的音频 HAL 走 offload 会没声音，强制普通 AudioTrack 输出
            final DefaultAudioSink sink = new DefaultAudioSink.Builder(this)
                    .setEnableFloatOutput(false)
                    // 只走 PCM 输出：AC3/DTS 这类不直通给功放，交给解码器出 PCM，音量键才管用
                    .setAudioCapabilities(new AudioCapabilities(
                            new int[]{androidx.media3.common.C.ENCODING_PCM_16BIT}, 8))
                    .setAudioOffloadSupportProvider(new DefaultAudioSink.AudioOffloadSupportProvider() {
                        @Override public AudioOffloadSupport getAudioOffloadSupport(Format f, androidx.media3.common.AudioAttributes a) {
                            return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
                        }
                    })
                    .build();
            // 渲染器基座：MediaCodec 优先，硬解失败自动落 FFmpeg 软解（nextlib）
            if (FfmpegLibrary.isAvailable()) {
                android.util.Log.e("LMBY", "FFmpeg 扩展已加载: " + FfmpegLibrary.getVersion());
            } else {
                android.util.Log.e("LMBY", "FFmpeg 扩展不可用，只有 MediaCodec");
            }
            final DefaultRenderersFactory def;
            int dm = pstore == null ? 0 : pstore.decodeMode();
            if ((dm == 1 || forceFfmpeg) && ffVideoRendererOk) {
                // 菜单选「软件解码」或硬解重试 → 纯 FFmpeg 软解，绕开系统硬解
                android.util.Log.e("LMBY", "解码方式=" + (forceFfmpeg ? "硬解失败重试" : "软件") + " → FFmpeg 纯软解");
                def = new FFmpegOnlyRenderersFactory(this).setEnableAudioFloatOutput(false);
            } else {
                if (dm == 1 && !ffVideoRendererOk) {
                    android.util.Log.e("LMBY", "FFmpeg 渲染器缺失（dex 里没有 RendererCapabilities$-CC），软件解码不可用 → 退回 MediaCodec，解不了自动转 libVLC");
                    toast(L.t("这个包没带 FFmpeg 视频解码器，软件解码已跳过"));
                }
                android.util.Log.e("LMBY", "解码方式=" + (dm == 2 ? "系统" : "硬件")
                        + (softMc ? "（软解重试）" : "") + " → MediaCodec 优先"
                        + (dm == 0 ? "（强制硬解，不静默兜底）" : " + FFmpeg 兜底"));
                // 0 硬件：强制走 MediaCodec 硬解，解不了由 onPlayerError 明确提示后重试软解
                // 2 系统：系统自动，硬解失败在渲染器内部静默落 FFmpeg 软解
                def = new NextRenderersFactory(this)
                        .setEnableDecoderFallback(dm != 0 && ffVideoRendererOk)
                        .setMediaCodecSelector(softMc ? MediaCodecSelector.PREFER_SOFTWARE : MediaCodecSelector.DEFAULT)
                        .setEnableAudioFloatOutput(false);
            }
            // 用默认工厂的渲染器，但把音频渲染器换成我们自己配的 sink
            final RenderersFactory rf = new RenderersFactory() {
                @Override public Renderer[] createRenderers(android.os.Handler handler,
                        androidx.media3.exoplayer.video.VideoRendererEventListener vl,
                        androidx.media3.exoplayer.audio.AudioRendererEventListener al,
                        androidx.media3.exoplayer.text.TextOutput to,
                        androidx.media3.exoplayer.metadata.MetadataOutput mo) {
                    Renderer[] orig = def.createRenderers(handler, vl, al, to, mo);
                    Renderer[] out = new Renderer[orig.length];
                    for (int i = 0; i < orig.length; i++) {
                        if (orig[i] instanceof MediaCodecAudioRenderer) {
                            // 音频统一交给 FFmpeg 软解：AC3/DTS 这类不再以 bitstream 直通给 HDMI
                            // （DIRECT 输出会绕过系统音量，导致音量键怎么按声音都不变），解成 PCM 走 MIXER 就能调音量
                            if (FfmpegLibrary.isAvailable()) {
                                out[i] = new FfmpegAudioRenderer(handler, al, sink);
                            } else {
                                out[i] = new MediaCodecAudioRenderer(SysPlayerActivity.this,
                                        MediaCodecSelector.DEFAULT, handler, al, sink);
                            }
                        } else {
                            out[i] = orig[i];
                        }
                    }
                    return out;
                }
            };
            // 缓冲放大一点，局域网 4K 高码率不至于一抖就断
            LoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20000, 50000, 2000, 6000)
                    .build();
            // 转码(HLS)首片要等服务器起 ffmpeg，默认 8 秒读超时太短会直接 SocketTimeout
            androidx.media3.datasource.DataSource.Factory dsf =
                    new androidx.media3.datasource.DefaultDataSource.Factory(this,
                            new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                    .setConnectTimeoutMs(20000)
                                    .setReadTimeoutMs(45000)
                                    .setAllowCrossProtocolRedirects(true));
            player = new ExoPlayer.Builder(this, rf)
                    .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsf,
                            new DvHevcExtractors(new androidx.media3.extractor.DefaultExtractorsFactory())))
                    .setLoadControl(lc).build();
            player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                @Override public void onRenderedFirstFrame(EventTime et, Object output, long renderTimeMs) {
                    firstFrameRendered = true;
                    android.util.Log.e("LMBY", "首帧已渲染 renderTimeMs=" + renderTimeMs
                            + " after=" + (playStartedAt == 0 ? -1 : SystemClock.elapsedRealtime() - playStartedAt));
                }
                @Override public void onDroppedVideoFrames(EventTime et, int dropped, long elapsedMs) {
                    android.util.Log.e("LMBY", "丢帧 dropped=" + dropped + " / " + elapsedMs + "ms");
                }
                @Override public void onAudioUnderrun(EventTime et, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
                    android.util.Log.e("LMBY", "音频欠载 buf=" + bufferSize + " " + bufferSizeMs + "ms sinceFeed=" + elapsedSinceLastFeedMs);
                }
                @Override public void onAudioSinkError(EventTime et, Exception e) {
                    android.util.Log.e("LMBY", "音频输出错误: " + e);
                }
                @Override public void onVideoSizeChanged(EventTime et, androidx.media3.common.VideoSize vs) {
                    videoW = vs.width;
                    videoH = vs.height;
                    android.util.Log.e("LMBY", "分辨率 " + vs.width + "x" + vs.height);
                    ui.post(new Runnable() {
                        @Override public void run() { applyAspect(curAspect); }
                    });
                }
                @Override public void onLoadError(EventTime et, androidx.media3.exoplayer.source.LoadEventInfo info,
                        androidx.media3.exoplayer.source.MediaLoadData data, java.io.IOException e, boolean wasCanceled) {
                    android.util.Log.e("LMBY", "加载失败 " + e + " canceled=" + wasCanceled + " type=" + data.dataType);
                }
            });
            if (tex != null) attachTexture(); else player.setVideoSurfaceView(surface);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    android.util.Log.e("LMBY", "state=" + state + " pos=" + (player == null ? -1 : player.getCurrentPosition()));
                    if (state == Player.STATE_READY) {
                        prepared = true;
                        bufferingSince = 0;
                        long d = player.getDuration();
                        if (d != C.TIME_UNSET && d > 0) durMs = d;   // 拿不到就保留预置片长
                        // 转码流没有总时长，用服务器侧片长补上，进度条和快进范围才正常
                        if (durMs <= 0 && itemId != null && !itemId.isEmpty()) {
                            final String iid = itemId;
                            new Thread(new Runnable() { public void run() {
                                final long r = Api.runtimeMs(session(), iid);
                                if (r > 0) ui.post(new Runnable() { public void run() {
                                    if (durMs <= 0) { durMs = r; refreshBar(); refreshTime(); }
                                }});
                            }}).start();
                        }
                        if (!seekDone && startMs > 3000 && durMs > 0 && startMs < durMs - 5000) {
                            seekDone = true;
                            player.seekTo(startMs);
                        } else if (!seekDone) {
                            seekDone = true;
                        }
                        refreshPlayIcon();
                        ui.post(new Runnable() {
                            @Override public void run() { applyAspect(curAspect); }
                        });
                        // 轨道表出来后再套详情页选的音轨 / 字幕（每次起播只套一次）
                        if (!trackPrefDone) {
                            trackPrefDone = true;
                            ui.postDelayed(new Runnable() {
                                @Override public void run() { applyTrackPrefsExo(); }
                            }, 3800);
                        }
                    } else if (state == Player.STATE_BUFFERING) {
                        // 与 libVLC 版一致：缓冲时显示转圈图标
                        if (pui != null) pui.setPlayState(PlayerUI.ST_BUFFER);
                        if (bufferingSince == 0) bufferingSince = SystemClock.elapsedRealtime();
                    } else if (state == Player.STATE_ENDED) {
                        ended = true;
                        refreshPlayIcon();
                        showHud();
                        if (itemId != null && !itemId.isEmpty())
                            Api.reportStop(session(), itemId, durMs, playSessionId);
                    }
                }

                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    if (prepared) {
                        paused = !isPlaying && !ended;
                        refreshPlayIcon();
                    }
                }

                @Override public void onPlayerError(PlaybackException e) {
                    int code = e.errorCode;
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    android.util.Log.e("LMBY", "播放错误: " + e.getErrorCodeName() + " " + msg);
                    // 416：续播位置超过了文件长度（老 AVI/Xvid 片很常见）→ 先从头重开，
                    // 还不行说明直连索引本身有问题，改走服务器转码
                    if (msg.contains("Response code: 416") || msg.contains("416")) {
                        if (!zeroRetried) {
                            zeroRetried = true;
                            android.util.Log.e("LMBY", "416 续播越界 → 从头重开");
                            toast(L.t("续播位置越界，从头播放"));
                            restartFromZero();
                            return;
                        }
                        if (!tcForce) {
                            tcForce = true;
                            android.util.Log.e("LMBY", "416 从头也拉不动 → 改走服务器转码");
                            toast(L.t("这个片源直连拉不动，改走服务器转码"));
                            restartFromZero();
                            return;
                        }
                    }
                    // 硬解自称支持却实际解不了（MPEG-4 Part2 / VC-1 这类冷门编码）：
                    // MediaCodec 一旦 supported 就不会再轮到 FFmpeg，所以这里主动换纯软解重试一次
                    if (ffVideoRendererOk && !forceFfmpeg && code >= 4001 && code <= 4005) {
                        forceFfmpeg = true;
                        android.util.Log.e("LMBY", "硬解失败 → 改用 FFmpeg 纯软解重试");
                        toast(L.t("硬解不支持，改用 FFmpeg 软解"));
                        restartEngine();
                        return;
                    }
                    // 解不出来（冷门容器 / 无音频解码器）→ 退回 libVLC
                    bailToVlc();
                }

                /** 从头重开（不动当前进度）：给 416 越界 / 转码兜底用 */
                private void restartFromZero() {
                    startMs = 0; tcStartMs = 0; seekDone = true;
                    try { if (player != null) { player.release(); player = null; } } catch (Throwable ignored) { }
                    open();
                    showHud();
                }

                /** ExoPlayer 解析出的字幕 cue（内嵌字幕） */
                @Override public void onCues(androidx.media3.common.text.CueGroup cg) {
                    if (subCue == null) return;
                    int n = (cg == null || cg.cues == null) ? 0 : cg.cues.size();
                    if (cueLog < 3 || cueLog % 300 == 0)
                        android.util.Log.e("LMBY", "cue n=" + n + " #" + cueLog);
                    cueLog++;
                    subCue.style(subSizePx(), subColorVal(), subStyleVal(), subPosFrac(), subXFrac());
                    android.util.Log.e("LMBY", "Exo字幕字号 idx="
                            + (pstore == null ? 1 : pstore.subSize()) + " px=" + subSizePx()
                            + " w=" + (int) U.subTextWidth(SysPlayerActivity.this, pstore == null ? 1 : pstore.subSize(), null));
                    // 用第一条文字 cue 的位置信息（line/lineType）自动摆字幕；
                    // PGS 位图 cue 的 line 常是 0/NaN，用它会算出越界值，所以跳过
                    if (menu != null && !nativeSubPosDone && cg != null && cg.cues != null && !cg.cues.isEmpty()) {
                        androidx.media3.common.text.Cue c0 = cg.cues.get(0);
                        if (c0.text != null && c0.bitmap == null) {
                            nativeSubPosDone = true;
                            float np = -1f;
                            if (c0.lineType == androidx.media3.common.text.Cue.LINE_TYPE_FRACTION
                                    && !Float.isNaN(c0.line)) {
                                float lp = Math.max(0f, Math.min(1f, c0.line));
                                np = 1f - lp;
                            }
                            if (np >= 0.02f && np <= 0.90f) {
                                android.util.Log.e("LMBY", "cue位置线 line=" + c0.line + " → 字幕自动定位 " + np);
                                menu.setAutoPos(np);
                            }
                        }
                    }
                    subCue.set(cg == null ? null : cg.cues);
                    if (dumpOn) dumpLayer();
                }

                @Override public void onTracksChanged(Tracks tracks) {
                    for (Tracks.Group g : tracks.getGroups()) {
                        try {
                            android.util.Log.e("LMBY", "轨道 type=" + g.getType() + " mime="
                                    + g.getTrackFormat(0).sampleMimeType + " " + g.getTrackFormat(0).width + "x"
                                    + g.getTrackFormat(0).height + " supported=" + g.isSupported()
                                    + " selected=" + g.isSelected()
                                    + (g.getType() == C.TRACK_TYPE_TEXT ? (" codecs=" + g.getTrackFormat(0).codecs) : ""));
                            if (g.getType() == C.TRACK_TYPE_VIDEO) {
                                androidx.media3.common.Format f0 = g.getTrackFormat(0);
                                if ("video/dolby-vision".equals(f0.sampleMimeType)) logDvSupport(f0);
                                if ("video/dolby-vision".equals(f0.sampleMimeType) && !g.isSupported() && !dvFallbackTried) {
                                    String dvCodecs = f0.codecs == null ? "" : f0.codecs;
                                    dvFallbackTried = true;
                                    boolean p5 = dvCodecs.startsWith("dvhe.05") || dvCodecs.startsWith("dvh1.05");
                                    android.util.Log.e("LMBY", "DV 硬解不支持 → 按 HEVC 基础层重开一次 codecs=" + dvCodecs
                                            + (p5 ? "（Profile5 单层，色彩可能不准）" : ""));
                                    retryDvAsHevc();
                                    return;
                                }
                                String cd = f0.codecs == null ? "" : f0.codecs;
                                // mp4v（Xvid/DivX）：这台机的 OMX.MS.M4V.Decoder 一开就报 0x90000008，
                                // 提前认清直接换 FFmpeg 软解，别让用户先盯着黑屏等报错
                                String cdl = cd.toLowerCase();
                                String ml = f0.sampleMimeType == null ? "" : f0.sampleMimeType.toLowerCase();
                                if (!mp4vRouted && !forceFfmpeg && ffVideoRendererOk
                                        && (pstore == null || pstore.decodeMode() != 1)
                                        && (ml.contains("mp4v") || cdl.startsWith("mp4v")
                                            || cdl.contains("xvid") || cdl.contains("divx"))) {
                                    mp4vRouted = true;
                                    forceFfmpeg = true;
                                    android.util.Log.e("LMBY", "mp4v/Xvid 硬解在这台机上必然报错 → 直接 FFmpeg 软解 codecs=" + cd);
                                    toast(L.t("这条片的编码硬解不支持，改用软解"));
                                    restartEngine();
                                    return;
                                }
                                // VP9 Profile 2/3 = 10bit（HDR 那批 4K 片基本都是）：这颗芯片的 VP9 硬解只认 8bit，
                                // 它还会报 supported=true，但一帧都出不来 → 必须提前交给 libVLC
                                if ("video/x-vnd.on2.vp9".equals(f0.sampleMimeType)
                                        && (cd.startsWith("vp09.02") || cd.startsWith("vp09.03"))) {
                                    vp9Unsupported = true;
                                    android.util.Log.e("LMBY", "VP9 10bit/Profile2 硬解不支持 codecs=" + cd + " → 交给 libVLC");
                                }
                            }
                            if (vFps <= 0 && g.getTrackFormat(0).frameRate > 0) {
                                vFps = g.getTrackFormat(0).frameRate;
                                android.util.Log.e("LMBY", "片源帧率=" + vFps);
                                if (menu != null) menu.refreshFrameRate();
                            }
                        } catch (Throwable ignore) { }
                    }
                    // 10bit 的 VP9（Profile 2 HDR）硬解出不了画面：直接交 libVLC，别让用户看黑屏
                    if (vp9Unsupported) {
                        vp9Unsupported = false;
                        android.util.Log.e("LMBY", "VP9 10bit 走 libVLC，Exo 这边硬解无画面");
                        toast(L.t("这条片是 10bit VP9（HDR），硬解不支持，改用 libVLC"));
                        bailToVlc();
                        return;
                    }
                    // 音频轨系统解不了（AC3/DTS 之类）也交给 VLC，避免没声音
                    for (Tracks.Group g : tracks.getGroups()) {
                        if (g.getType() == C.TRACK_TYPE_AUDIO && !g.isSupported()) {
                            bailToVlc();
                            return;
                        }
                    }
                }
            });
            player.setMediaItem(MediaItem.fromUri(url));
            // 详情页存的是「流序号」键：拿到服务器的媒体流列表后重套一次音轨/字幕
            if (itemId != null && !itemId.isEmpty() && srvStreams == null) {
                final Api.Session ss2 = session();
                new Thread(new Runnable() { @Override public void run() {
                    final java.util.List<Api.MStream> ms = Api.mediaStreams(ss2, itemId);
                    ui.post(new Runnable() { @Override public void run() {
                        srvStreams = ms;
                        applyTrackPrefsExo();
                    } });
                } }).start();
            }
            // 重建内核后恢复用户设定的倍速
            if (targetSpeed != 1f) {
                try {
                    player.setPlaybackParameters(
                            new androidx.media3.common.PlaybackParameters(targetSpeed, 1f));
                } catch (Throwable ignored) { }
            }
            seekDone = false;
            try {
                int pm = pstore == null ? 0 : pstore.playMode();
                // 电影这类单个文件：列表播放/列表循环没意义，别用 REPEAT_ALL 把同一部片子反复循环
                if ((seriesId == null || seriesId.isEmpty()) && (pm == 1 || pm == 2)) pm = 0;
                player.setRepeatMode(pm == 3 ? Player.REPEAT_MODE_ONE
                        : (pm == 2 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF));
            } catch (Throwable ignored) { }
            player.prepare();
            player.play();
            android.util.Log.e("LMBY", "prepare 已调用 url=" + url);
            if (itemId != null && !itemId.isEmpty()) {
                // 取 PlaySessionId 要发网络请求，必须离开主线程；取不到就自己造一个，Start 与 Progress 用同一个
                new Thread(new Runnable() { @Override public void run() {
                    try {
                        String psid = Api.playSession(session(), itemId);
                        if (psid == null || psid.isEmpty())
                            psid = "loyao-" + System.currentTimeMillis();
                        playSessionId = psid;
                        Api.reportStart(session(), itemId, psid);
                    } catch (Throwable ignored) { }
                }}).start();
            }
            hideHudSoon();
        } catch (Throwable e) {
            bailToVlc();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        // 菜单打开时所有按键先交给级联菜单
        if (menu != null && menu.isOpen()) {
            boolean handled = menu.onKey(kc, e.getAction());
            if (handled) {
                // 因这次按键把菜单关掉了 → 恢复 HUD，和 libVLC 版手感一致
                if (!menu.isOpen()) showHud();
                return true;
            }
        }
        // 选集面板打开时：左右/确认交给卡片焦点，返回或上键收起
        if (pui != null && pui.epOpen()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_DPAD_UP
                        || kc == KeyEvent.KEYCODE_MENU) {
                    pui.hideEpisodes();
                    showHud();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
                        || kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
                    return super.dispatchKeyEvent(e);
                }
            }
            if (e.getAction() == KeyEvent.ACTION_UP) return true;
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (kc == KeyEvent.KEYCODE_MENU) {
                pui.hide();
                if (menu != null) menu.openMenu();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
                    || kc == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || kc == KeyEvent.KEYCODE_MEDIA_REWIND
                    || kc == KeyEvent.KEYCODE_MEDIA_NEXT || kc == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                // 与 libVLC 版同一套「按下走一步 + 按住连发」逻辑，遥控器 ⏩/⏪ 键也走这里
                int dir = (kc == KeyEvent.KEYCODE_DPAD_RIGHT || kc == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        || kc == KeyEvent.KEYCODE_MEDIA_NEXT) ? 1 : -1;
                seekKeyDown(dir, e.getRepeatCount() > 0);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_SPACE) {
                toggle();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
                // 音量键自己处理，保证任何音轨（含切轨后）都有音量条、且能真正改系统媒体音量
                try {
                    android.media.AudioManager am =
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            kc == KeyEvent.KEYCODE_VOLUME_UP
                                    ? android.media.AudioManager.ADJUST_RAISE
                                    : android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.FLAG_SHOW_UI);
                } catch (Throwable ignored) { }
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                // 是某一集的话，上键弹出这部剧的选集条
                if (!seriesId.isEmpty()) { openEpisodes(); return true; }
                showHud();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                showHud();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_BACK) {
                closing = true;   // 标记"人在退出"，后续任何错误/卡顿都不要再回退另一个内核
                finish();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
                    || kc == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || kc == KeyEvent.KEYCODE_MEDIA_REWIND
                    || kc == KeyEvent.KEYCODE_MEDIA_NEXT || kc == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                int dir = (kc == KeyEvent.KEYCODE_DPAD_RIGHT || kc == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        || kc == KeyEvent.KEYCODE_MEDIA_NEXT) ? 1 : -1;
                seekKeyUp(dir);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void toggle() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            paused = true;
            if (itemId != null && !itemId.isEmpty())
                Api.reportProgress(session(), itemId, player.getCurrentPosition(), true, playSessionId);
        } else {
            player.play();
            paused = false;
        }
        refreshPlayIcon();
        showHud();
    }

    private void refreshPlayIcon() {
        if (pui == null) return;
        if (ended) pui.setPlayState(PlayerUI.ST_END);
        else if (!prepared) pui.setPlayState(PlayerUI.ST_BUFFER);
        // 图标表示"按下会做什么"：正在播放显示暂停键，暂停时显示播放键
        else pui.setPlayState(paused ? PlayerUI.ST_PLAY : PlayerUI.ST_PAUSE);
    }

    private final Runnable subTick = new Runnable() {
        @Override public void run() {
            try { if (menu != null && !menu.isOpen()) menu.tickSub(); } catch (Throwable ignored) { }
            ui.postDelayed(this, 200);
        }
    };

    private final Runnable hideStats = new Runnable() {
        @Override public void run() { if (pui != null) pui.hideStats(); }
    };

    private int curAspect = 0;

    // ==================== 菜单 ↔ 内核 桥接 ====================
    private class VMenuHost implements VMenu.Host {
        public Store store() { return pstore; }
        public String itemId() { return itemId; }
        public Api.Session session() { return SysPlayerActivity.this.session(); }
        public long posMs() { return SysPlayerActivity.this.posMs; }
        public String[] audioNames() { return audioNamesImpl(); }
        public int curAudio() { return curAudioImpl(); }
        public void selectAudio(int i) { selectAudioImpl(i); }
        public void applyAspect(int idx) { SysPlayerActivity.this.applyAspect(idx); }
        public void setSpeed(float v) { applySpeed(v); }
        public void restart() { restartEngine(); }
        public void bailVlc() { SysPlayerActivity.this.bailToVlc(); }
        public void toast(String s) { SysPlayerActivity.this.toast(s); }
        public String[] embedSubNames() { return embedSubNamesImpl(); }
        public int curEmbedSub() { return curEmbedSubImpl(); }
        public void selectEmbedSub(int i) { selectEmbedSubImpl(i); }
        public void clearEmbedSub() { clearEmbedSubImpl(); }

        public int kernelKind() { return 1; }

        /** Exo 侧没有播放队列：只有剧集（seriesId 非空）才算「有列表」，电影是单个文件 */
        public int listCount() { return (seriesId != null && seriesId.length() > 0) ? 2 : 1; }

        public float videoFps() { return vFps; }

        /** 播放切换：只对本次播放生效，不写 engine 设置，退出后仍按主界面设置走 */
        public void switchPlayer(int kind) {
            if (kind == 1) { toast(L.t("当前已经在用 ExoPlayer")); return; }
            if (kind == 0) {
                toast(L.t("临时切到 libVLC"));
                bailToVlc();
                return;
            }
            toast(L.t("临时改用第三方播放器"));
            bailing = true;
            U.launchThird(SysPlayerActivity.this, url, titleS == null ? "" : titleS, pstore, new Runnable() {
                @Override public void run() { finish(); }
            });
        }
        public void showStats(String[][] groups) {
            if (pui == null) return;
            ui.removeCallbacks(hideStats);
            pui.showStats(groups);
        }

        /** 播放统计运行时信息：{播放器名, HDR, 宽, 高, 视频codec, 音频codec, 视频码率} */
        public boolean directPlay() { return url != null && url.contains("static=true"); }
        public String playSessionId() { return playSessionId; }

        public String[] statsRuntime() {
            String[] r = new String[7];
            r[0] = "ExoPlayer";
            r[1] = "";
            long vbr = 0;
            String vc = "", ac = "";
            // 真实解码尺寸优先：转码时容器的 Format 仍可能标源片分辨率，onVideoSizeChanged 才是实际输出
            int w = videoW, h = videoH;
            try {
                Tracks tks = player.getCurrentTracks();
                for (Tracks.Group g : tks.getGroups()) {
                    for (int i = 0; i < g.length; i++) {
                        if (!g.isTrackSelected(i)) continue;
                        Format f = g.getTrackFormat(i);
                        if (g.getType() == C.TRACK_TYPE_VIDEO) {
                            if (w <= 0 || h <= 0) {
                                w = f.width;
                                h = f.height;
                            }
                            vc = mimeCodec(f.sampleMimeType);
                            if (f.bitrate > 0) vbr = f.bitrate;
                            r[1] = hdrName(f);
                        } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                            ac = mimeCodec(f.sampleMimeType);
                        }
                    }
                }
            } catch (Throwable ignored) { }
            // 转码时用设定目标码率；设的是「自动」就留空，避免拿源片码率误导
            if (pstore != null && pstore.forceTranscode())
                vbr = pstore.maxKbps() > 0 ? pstore.maxKbps() * 1000L : 0;
            r[2] = w > 0 ? String.valueOf(w) : "";
            r[3] = h > 0 ? String.valueOf(h) : "";
            r[4] = vc;
            r[5] = ac;
            r[6] = vbr > 0 ? String.valueOf(vbr) : "";
            return r;
        }

        private String mimeCodec(String mime) {
            if (mime == null) return "";
            String l = mime.toLowerCase();
            if (l.contains("avc")) return "h264";
            if (l.contains("hevc")) return "hevc";
            if (l.contains("av01")) return "av1";
            if (l.contains("vp9")) return "vp9";
            if (l.contains("mpeg2")) return "mpeg2video";
            if (l.contains("eac3")) return "eac3";
            if (l.contains("ac3")) return "ac3";
            if (l.contains("mp4a") || l.contains("aac")) return "aac";
            if (l.contains("opus")) return "opus";
            if (l.contains("flac")) return "flac";
            if (l.contains("truehd")) return "truehd";
            if (l.contains("dts")) return "dts";
            if (l.contains("mp3")) return "mp3";
            return "";
        }

        private String hdrName(Format f) {
            try {
                if (f.colorInfo == null) return "";
                int ct = f.colorInfo.colorTransfer;
                if (ct == 6) return "HDR10";
                if (ct == 7) return "HLG";
                if (ct == 8) return "HDR10+";
                if (f.colorInfo.hdrStaticInfo != null) return "HDR10";
                return "";
            } catch (Throwable e) {
                return "";
            }
        }
    }

    // ---------- 内嵌字幕：直接读 ExoPlayer 解析出的原字幕轨 ----------

    /** 套用详情页选过的音轨 / 字幕（按语言或 label 匹配 Exo 轨道，匹配不到就不动） */
    private void applyTrackPrefsExo() {
        if (player == null || pstore == null) return;
        String a = itemId == null ? "" : pstore.audioTrack(itemId);
        if (a.isEmpty() && seriesId != null && !seriesId.isEmpty()) a = pstore.audioTrack(seriesId);
        if (!a.isEmpty()) {
            java.util.List<Tracks.Group> gs = audioGroups();
            int posA = digits(a) ? streamPosByIndex("Audio", Integer.parseInt(a)) : -1;
            if (posA >= 0 && posA < gs.size()) {
                selectAudioImpl(posA);
                android.util.Log.e("LMBY", "套用音轨(序号) " + a + " -> pos " + posA);
            } else for (int i = 0; i < gs.size(); i++) {
                String nm = "";
                try {
                    Format f = gs.get(i).getTrackFormat(0);
                    nm = (f.label == null ? "" : f.label) + " " + (f.language == null ? "" : f.language);
                } catch (Throwable ignored) { }
                if (nameMatches(nm, a)) {
                    selectAudioImpl(i);
                    toast(L.t("音轨：") + (nm.trim().isEmpty() ? ("#" + (i + 1)) : nm.trim()));
                    break;
                }
            }
        }
        String s = itemId == null ? "" : pstore.subTrack(itemId);
        if (s.isEmpty() && seriesId != null && !seriesId.isEmpty()) s = pstore.subTrack(seriesId);
        if (!s.isEmpty()) {
            if ("off".equals(s)) {
                clearEmbedSubImpl();
            } else {
                java.util.List<Tracks.Group> gs = embedGroups();
                int posS = digits(s) ? streamPosByIndex("Subtitle", Integer.parseInt(s)) : -1;
                if (posS >= 0 && posS < gs.size()) {
                    selectEmbedSubImpl(posS);
                    android.util.Log.e("LMBY", "套用字幕(序号) " + s + " -> pos " + posS);
                } else {
                for (int i = 0; i < gs.size(); i++) {
                    String nm = "";
                    try {
                        Format f = gs.get(i).getTrackFormat(0);
                        nm = (f.label == null ? "" : f.label) + " " + (f.language == null ? "" : f.language);
                    } catch (Throwable ignored) { }
                    if (nameMatches(nm, s)) { selectEmbedSubImpl(i); break; }
                    }
                }
            }
        }
    }

    /** 键是不是详情页存的「流序号」 */
    private boolean digits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** 流序号 → 同类型流里的第几条 */
    private int streamPosByIndex(String type, int idx) {
        if (srvStreams == null) return -1;
        int pos = 0;
        for (int i = 0; i < srvStreams.size(); i++) {
            Api.MStream m = srvStreams.get(i);
            if (!type.equals(m.type)) continue;
            if (m.index == idx) return pos;
            pos++;
        }
        return -1;
    }

    /** 轨道名里有没有这个语言 / 名称关键字（含常见语言别名） */
    private boolean nameMatches(String name, String key) {
        if (name == null || key == null) return false;
        String a = name.toLowerCase(), b = key.toLowerCase().trim();
        if (b.isEmpty()) return false;
        if (a.contains(b) || b.contains(a)) return true;
        String[][] dict = {
                {"chi", "chi|chinese|中文|mandarin|zho"}, {"zho", "chi|chinese|中文|mandarin"},
                {"jpn", "jpn|japanese|日语|日本語|日本"}, {"eng", "eng|english|英语|英文"},
                {"kor", "kor|korean|韩语|韩国"}, {"fre", "fre|french|法语"},
                {"ger", "ger|german|德语"}, {"spa", "spa|spanish|西班牙"}
        };
        for (int i = 0; i < dict.length; i++) {
            if (!dict[i][0].equals(b)) continue;
            String[] alts = dict[i][1].split("\\|");
            for (int k = 0; k < alts.length; k++) if (a.contains(alts[k])) return true;
        }
        return false;
    }

    private java.util.List<Tracks.Group> embedGroups() {
        ArrayList<Tracks.Group> out = new ArrayList<Tracks.Group>();
        try {
            if (player != null) {
                for (Tracks.Group g : player.getCurrentTracks().getGroups())
                    if (g.getType() == C.TRACK_TYPE_TEXT) out.add(g);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private String[] embedSubNamesImpl() {
        java.util.List<Tracks.Group> gs = embedGroups();
        String[] n = new String[gs.size()];
        for (int i = 0; i < gs.size(); i++) {
            String name = L.t("字幕 ") + (i + 1);
            try {
                Format f = gs.get(i).getTrackFormat(0);
                String mime = f.sampleMimeType == null ? "" : f.sampleMimeType.toLowerCase();
                if (mime.contains("pgs")) name = "图形字幕 " + (i + 1);
                else if (f.label != null && !f.label.trim().isEmpty()) name = f.label.trim();
                else if (f.language != null && !f.language.trim().isEmpty()) name = Api.langName(f.language);
                else if (mime.contains("ssa") || mime.contains("ass")) name = "ASS 字幕 " + (i + 1);
            } catch (Throwable ignored) { }
            n[i] = name;
        }
        return n;
    }

    private int curEmbedSubImpl() {
        java.util.List<Tracks.Group> gs = embedGroups();
        for (int i = 0; i < gs.size(); i++) {
            try { if (gs.get(i).isSelected()) return i; } catch (Throwable ignored) { }
        }
        return -1;
    }

    private void selectEmbedSubImpl(int i) {
        java.util.List<Tracks.Group> gs = embedGroups();
        if (player == null || i < 0 || i >= gs.size()) return;
        try {
            Tracks.Group g = gs.get(i);
            Format f0 = g.getTrackFormat(0);
            String cs = f0.codecs == null ? "" : f0.codecs.toUpperCase();
            String mime = f0.sampleMimeType == null ? "" : f0.sampleMimeType;
            // PGS/VobSub 是图形字幕：media3 只给出 1x1 占位图，Exo 这条永远画不出来。
            // 直接把这轨交给 libVLC（它自己能解图形字幕），别让用户盯着空画面。
            if (cs.contains("PGS") || cs.contains("VOBSUB") || cs.contains("DVDSUB")
                    || mime.contains("pgs")) {
                android.util.Log.e("LMBY", "图形字幕交给 libVLC codecs=" + f0.codecs + " mime=" + mime);
                toast(L.t("这条是图形字幕（PGS），ExoPlayer 画不出来，已改用 libVLC 显示"));
                bailAutoSub = true;
                bailToVlc();
                return;
            }
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(
                            g.getMediaTrackGroup(), 0))
                    .build());
            android.util.Log.e("LMBY", "内嵌字幕切换到 idx=" + i);
            nativeSubPosDone = false;   // 换轨后重新套一次这轨的初始位置
        } catch (Throwable e) {
            toast(L.t("切换内嵌字幕失败：") + e.getMessage());
        }
    }

    private void clearEmbedSubImpl() {
        if (player == null) return;
        try {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build());
            if (subCue != null) subCue.set(null);
            android.util.Log.e("LMBY", "内嵌字幕已关闭");
        } catch (Throwable ignored) { }
    }

    private int subSizePx() {
        int i = pstore == null ? 1 : pstore.subSize();
        return U.subTextPx(this, i);
    }

    private int subColorVal() {
        int i = pstore == null ? 0 : pstore.subColor();
        int[] cols = {0xFFFFFFFF, 0xFFFFF000, 0xFF00FFFF, 0xFF00FF00};
        return cols[i < 0 ? 0 : (i > 3 ? 3 : i)];
    }

    private int subStyleVal() { return pstore == null ? 0 : pstore.subStyle(); }

    private int subPosVal() { return 2; }
    private float subPosFrac() { return pstore == null ? 0.075f : pstore.subPosFrac(); }

    private float subXFrac() { return pstore == null ? 0f : pstore.subXFrac(); }

    private boolean dumpOn = false;

    /** 仅 debug：把真实字幕层整屏抓成位图 */
    public void enableDump() { dumpOn = true; }

    private void dumpLayer() {
        try {
            if (subCue == null) return;
            android.view.View rt = subCue.getRootView();
            int w = rt.getWidth(), h = rt.getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            cv.drawColor(0xFF000000);
            subCue.draw(cv);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(getExternalFilesDir(null), "subdump.png"));
            bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            android.util.Log.e("LMBY", "DUMP exo style=" + subStyleVal() + " size="
                    + (pstore == null ? 1 : pstore.subSize()) + " color=" + subColorVal()
                    + " posFrac=" + subPosFrac() + " xFrac=" + subXFrac());
        } catch (Throwable e) {
            android.util.Log.e("LMBY", "DUMP FAIL " + e);
        }
    }

    /** 内嵌字幕渲染层：文字 cue 底部居中，PGS 之类位图 cue 贴底缩放 */
    private static class SubCueView extends View {
        private java.util.List<androidx.media3.common.text.Cue> cues =
                java.util.Collections.<androidx.media3.common.text.Cue>emptyList();
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int fsPx = 30;
        private int color = 0xFFFFFFFF;
        private int sty = 0;
        private float posFrac = 0.075f;
        private float xFrac = 0f;
        private final Paint op = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int bmLog = 0;
        private static final int[] STY_FG = {0, 0xFFFFFFFF, 0xFFFFFFFF};
        private static final int[] STY_EDGE = {0xFF000000, 0xFF000000, 0xFF9B4DFF};
        private static final float[] STY_W = {0.14f, 0.08f, 0.18f};

        SubCueView(Context c) {
            super(c);
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tp.setStyle(Paint.Style.FILL);
            bp.setFilterBitmap(true);
            op.setStyle(Paint.Style.FILL);   // 边用实心偏移描边，避免 STROKE 文本与填充错位
            op.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            op.setTextAlign(Paint.Align.CENTER);   // 关键：边和字身必须同一种对齐，否则边会整体偏右
        }

        void style(int px, int col, int style, float pos, float xf) {
            fsPx = px;
            color = col;
            sty = style;
            posFrac = pos;
            xFrac = xf;
        }

        void set(java.util.List<androidx.media3.common.text.Cue> c) {
            cues = (c == null) ? java.util.Collections.<androidx.media3.common.text.Cue>emptyList() : c;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            if (cues.isEmpty()) return;
            float w = getWidth(), h = getHeight();
            int st = sty < 0 ? 0 : (sty > 2 ? 2 : sty);
            int fg = st == 0 ? color : STY_FG[st];
            int edge = STY_EDGE[st];
            float ew = Math.max(1.5f, fsPx * STY_W[st]);
            tp.setTextSize(fsPx);
            tp.setColor(fg);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setShadowLayer(0f, 0f, 0f, 0);
            op.setTextSize(fsPx);
            op.setColor(edge);
            int pi = 0;
            float pf = posFrac < 0.005f ? 0.005f : (posFrac > 0.30f ? 0.30f : posFrac);
            float y = h * (1f - pf);
            float cx = w * (0.5f + (xFrac < -0.3f ? -0.3f : (xFrac > 0.3f ? 0.3f : xFrac)));
            int drawn = 0;
            for (androidx.media3.common.text.Cue c : cues) {
                if (c == null || c.bitmap != null) continue;
                CharSequence t = c.text;
                if (t == null || t.length() == 0) continue;
                String[] ls = t.toString().split("\n");
                for (int i = ls.length - 1; i >= 0 && drawn < 4; i--) {
                    String s = ls[i].trim();
                    if (s.isEmpty()) continue;
                    U.outlinedText(cv, s, cx, y, tp, op, Math.max(0f, ew * 0.55f));
                    y -= fsPx * 1.16f;
                    drawn++;
                }
            }
            for (androidx.media3.common.text.Cue c : cues) {
                if (c == null || c.bitmap == null) continue;
                android.graphics.Bitmap bm = c.bitmap;
                // PGS/位图字幕本身就是一张整幅画面（1920x1080 带透明底），字幕画在哪由
                // 内核按原始坐标摆好。这里按等比铺满整帧，再把用户的上下/左右偏移叠上去，
                // 既不会把字幕算到画面外，滑块也能继续微调。
                float sc = Math.min(w / (float) bm.getWidth(), h / (float) bm.getHeight());
                float dw = bm.getWidth() * sc, dh = bm.getHeight() * sc;
                float dx = w * ((xFrac < -0.3f ? -0.3f : (xFrac > 0.3f ? 0.3f : xFrac)));
                float dy = -h * (pf - 0.075f);
                float l = (w - dw) / 2f + dx, t = (h - dh) / 2f + dy;
                cv.drawBitmap(bm, null, new android.graphics.RectF(l, t, l + dw, t + dh), bp);
                if (bmLog < 40 || bmLog % 60 == 0) {
                    android.util.Log.e("LMBY", "位图字幕 bm=" + bm.getWidth() + "x" + bm.getHeight()
                            + " view=" + (int) w + "x" + (int) h + " sc=" + sc
                            + " rect=" + (int) l + "," + (int) t + "," + (int) (l + dw) + "," + (int) (t + dh)
                            + " 上下=" + pf + " 左右=" + xFrac + " bmLog=" + bmLog);
                }
                bmLog++;
            }
        }
    }

    private java.util.List<Tracks.Group> audioGroups() {
        ArrayList<Tracks.Group> out = new ArrayList<Tracks.Group>();
        try {
            if (player != null) {
                for (Tracks.Group g : player.getCurrentTracks().getGroups())
                    if (g.getType() == C.TRACK_TYPE_AUDIO) out.add(g);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** 刚切换、但播放器还没回新的 tracks 时，先用这个记住用户选的是第几条，菜单文字才不会停在旧音轨上 */
    private volatile int pendingAudioIdx = -1;

    private String[] audioNamesImpl() {
        java.util.List<Tracks.Group> gs = audioGroups();
        String[] n = new String[gs.size()];
        for (int i = 0; i < gs.size(); i++) {
            String name = L.t("音轨 ") + (i + 1);
            int ch = 0;
            try {
                Format f = gs.get(i).getTrackFormat(0);
                if (f.label != null && !f.label.isEmpty()) name = f.label;
                else if (f.language != null && !f.language.isEmpty()) name = f.language;
                else if (f.sampleMimeType != null) name = f.sampleMimeType.replace("audio/", "").toUpperCase();
                ch = f.channelCount;
            } catch (Throwable ignored) { }
            n[i] = ch > 0 ? (name + " · " + ch + "ch") : name;
        }
        return n;
    }

    private int curAudioImpl() {
        java.util.List<Tracks.Group> gs = audioGroups();
        // 切换后 tracks 还没回来：先按用户刚选的那条显示（否则菜单/按钮停在旧音轨上）
        int p = pendingAudioIdx;
        if (p >= 0 && p < gs.size()) return p;
        for (int i = 0; i < gs.size(); i++) if (gs.get(i).isSelected()) return i;
        return 0;
    }

    private void selectAudioImpl(int i) {
        java.util.List<Tracks.Group> gs = audioGroups();
        if (player == null || i < 0 || i >= gs.size()) return;
        try {
            Tracks.Group g = gs.get(i);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(g.getMediaTrackGroup(), 0))
                    .build());
            // 立刻记住，菜单立刻显示成新音轨；等 onTracksChanged 回来确认后再交回给 tracks 判断
            pendingAudioIdx = i;
            android.util.Log.e("LMBY", "selectAudio idx=" + i + " name=" + audioNamesImpl()[i]);
        } catch (Throwable ignored) { }
    }

    /** 显示比例：0 原画 1 全屏铺满 2 4:3 3 16:9 */
    private void applyAspect(int idx) {
        curAspect = idx;
        if (surface == null || content == null) return;
        int cw = content.getWidth();
        int ch = content.getHeight();
        if (cw <= 0 || ch <= 0) {
            cw = getResources().getDisplayMetrics().widthPixels;
            ch = getResources().getDisplayMetrics().heightPixels;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.gravity = Gravity.CENTER;
        if (idx == 1) {
            lp.width = cw; lp.height = ch;                      // 铺满
        } else if (idx == 2) {
            lp.height = ch; lp.width = (int) (ch * 4f / 3f);     // 4:3
        } else if (idx == 3) {
            lp.height = ch; lp.width = (int) (ch * 16f / 9f);    // 16:9
        } else {
            float va = (videoW > 0 && videoH > 0) ? (float) videoW / videoH : 16f / 9f;
            if (va >= (float) cw / ch) { lp.width = cw; lp.height = (int) (cw / va); }
            else { lp.height = ch; lp.width = (int) (ch * va); }
        }
        android.util.Log.e("LMBY", "applyAspect idx=" + idx + " content=" + cw + "x" + ch
                + " -> surface " + lp.width + "x" + lp.height + " video=" + videoW + "x" + videoH);
        if (tex != null) tex.setLayoutParams(lp); else surface.setLayoutParams(lp);
    }

    /** TextureView 就绪后把它的 SurfaceTexture 包成 Surface 交给 Exo */
    private void attachTexture() {
        try {
            if (tex == null) return;
            android.graphics.SurfaceTexture st = tex.getSurfaceTexture();
            if (st == null) return;
            if (texSurface == null) texSurface = new android.view.Surface(st);
            if (player != null) {
                player.setVideoSurface(texSurface);
                android.util.Log.e("LMBY", "渲染层=TextureView " + tex.getWidth() + "x" + tex.getHeight());
            }
        } catch (Throwable e) {
            android.util.Log.e("LMBY", "attachTexture err " + e);
        }
    }

    private void releaseTexture() {
        try {
            if (player != null) player.clearVideoSurface();
            if (texSurface != null) { texSurface.release(); texSurface = null; }
        } catch (Throwable ignored) { }
    }

    /** 应用倍速（同时开一个 4 秒守护窗口，抵消音频重配导致的回落） */
    private void applySpeed(float v) {
        targetSpeed = v;
        speedGuardUntil = SystemClock.elapsedRealtime() + 4000;
        try {
            if (player != null)
                player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(v, 1f));
        } catch (Throwable ignored) { }
        android.util.Log.e("LMBY", "设置倍速 " + v + " 当前=" + curSpeed());
    }

    private float curSpeed() {
        try {
            return player == null ? -1f : player.getPlaybackParameters().speed;
        } catch (Throwable e) {
            return -1f;
        }
    }

    // ---------- 选集（播放器里按上键） ----------

    /** 按上键：拉出这部剧的所有集，缩略图 + 集名，选中即切集 */
    private void openEpisodes() {
        if (pui == null) return;
        final String sid = seriesId;
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<Api.Item> eps = null;
                try { eps = Api.episodes(session(), sid); } catch (Throwable ignored) { }
                if (eps == null || eps.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (pui != null) pui.toast(L.t("没有可选的集"));
                            showHud();
                        }
                    });
                    return;
                }
                final java.util.List<Api.Item> list = eps;
                int cur = 0;
                for (int i = 0; i < list.size(); i++) {
                    Api.Item e = list.get(i);
                    if (e.id != null && e.id.equals(itemId)) { cur = i; break; }
                }
                String[] labels = new String[list.size()];
                String[] urls = new String[list.size()];
                String[] badges = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    Api.Item e = list.get(i);
                    int num = e.index > 0 ? e.index : (i + 1);
                    labels[i] = (e.name == null || e.name.isEmpty())
                            ? (L.t("第 ") + num + L.t("集"))
                            : e.name;
                    badges[i] = e.pctBadge();
                    if (e.imgTag != null && !e.imgTag.isEmpty()) {
                        urls[i] = Api.imageUrl(session(), e.id, e.imgTag, 300);
                    } else if (e.thumbTag != null && !e.thumbTag.isEmpty()) {
                        urls[i] = Api.thumbUrl(session(), e.id, e.thumbTag, 320);
                    } else {
                        urls[i] = Api.imageUrl(session(), sid, "", 300);
                    }
                }
                final String head = L.t("选集") + " · " + list.size() + " " + L.t("集");
                final int curIdx = cur;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (pui == null) return;
                        pui.hide();
                        pui.showEpisodes(head, labels, urls, badges, curIdx, new PlayerUI.OnEpPick() {
                            @Override public void onPick(int index) { pickEpisode(list, index); }
                        });
                    }
                });
            }
        }).start();
    }

    /** 选集面板里确认了某一集：换源重播 */
    private void pickEpisode(java.util.List<Api.Item> list, int index) {
        if (index < 0 || index >= list.size()) { showHud(); return; }
        Api.Item e = list.get(index);
        if (e.id != null && e.id.equals(itemId)) { showHud(); return; }
        if (itemId != null && !itemId.isEmpty()) {
            try { Api.reportStop(session(), itemId, posMs, playSessionId); } catch (Throwable ignored) { }
        }
        itemId = e.id;
        titleS = e.name == null ? "" : e.name;
        directUrl = Api.streamUrl(session(), e.id);
        startMs = 0;
        tcStartMs = 0;
        posMs = 0;
        durMs = 0;
        prepared = false; ended = false; paused = false; seekDone = false; bailing = false;
        forceFfmpeg = false; ffmpegNoVideoTicks = 0;
        url = buildUrl();
        if (pui != null) pui.title(titleS);
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        open();
        showHud();
    }

    private String buildUrl() {
        try {
            if (pstore != null && (pstore.forceTranscode() || tcForce) && itemId != null && !itemId.isEmpty()) {
                String t = Api.transcodeUrlFrom(session(), itemId, pstore.maxHeightPx(),
                        pstore.maxKbps(), tcStartMs);
                if (t != null && !t.isEmpty()) return t;
            }
        } catch (Throwable ignored) { }
        return directUrl;
    }

    /** 当前是否在走服务器转码（转码流的 seek 要重新起转，不能原地跳） */
    private boolean isTranscoding() {
        // 按实际播放地址判断：static=true 是直连，只有 stream.mp4 才是转码流
        if (url == null || itemId == null || itemId.isEmpty()) return false;
        return !url.contains("static=true");
    }

    /** 杜比视界解码器体检：打出原生 DV 解码器、软匹配候选，以及对当前 Format 的实际支持结果 */
    private void logDvSupport(androidx.media3.common.Format f) {
        try {
            java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> raw =
                    androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos("video/dolby-vision", false, false);
            StringBuilder sb = new StringBuilder("DV查询 codecs=" + f.codecs
                    + " 原生候选 n=" + raw.size());
            for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo ci : raw) {
                boolean ok = false, func = false;
                try { ok = ci.isFormatSupported(this, f); } catch (Throwable ignored) { }
                try { func = ci.isFormatFunctionallySupported(this, f); } catch (Throwable ignored) { }
                sb.append(" | ").append(ci.name).append(" mime=").append(ci.codecMimeType)
                        .append(" hw=").append(ci.hardwareAccelerated)
                        .append(" pl=").append(ci.getProfileLevels().length)
                        .append(" ok=").append(ok).append(" func=").append(func);
            }
            android.util.Log.e("LMBY", sb.toString());
            java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> soft =
                    androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfosSoftMatch(
                            androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT, f, false, false);
            StringBuilder sb2 = new StringBuilder("DV软匹配 n=" + soft.size());
            for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo ci : soft) {
                sb2.append(" | ").append(ci.name).append(" mime=").append(ci.codecMimeType);
            }
            android.util.Log.e("LMBY", sb2.toString());
            // 系统侧清单：确认厂商 DV 解码器是否真的注册给应用
            try {
                android.media.MediaCodecList all = new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
                StringBuilder sb3 = new StringBuilder("DV系统清单 ");
                int dv = 0, hevc = 0, dvSecure = 0;
                for (android.media.MediaCodecInfo ai : all.getCodecInfos()) {
                    if (ai.isEncoder()) continue;
                    for (String t : ai.getSupportedTypes()) {
                        if ("video/dolby-vision".equals(t)) {
                            dv++;
                            if (ai.getName().contains("secure")) dvSecure++;
                            else sb3.append("| ").append(ai.getName()).append(" ");
                        }
                        if ("video/hevc".equals(t)) hevc++;
                    }
                }
                sb3.append(" 合计dv=").append(dv).append("(secure ").append(dvSecure).append(") hevc=").append(hevc);
                android.util.Log.e("LMBY", sb3.toString());
            } catch (Throwable t2) {
                android.util.Log.e("LMBY", "DV系统清单失败 " + t2);
            }
        } catch (Throwable t) {
            android.util.Log.e("LMBY", "DV查询失败 " + t);
        }
    }

    /** 杜比视界硬解被判不支持：把码流按 HEVC 基础层重开一次（Emby 也是这个思路） */
    private void retryDvAsHevc() {
        long pos = 0;
        try { if (player != null) pos = tcStartMs + Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) { }
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        DV_TO_HEVC = true;
        prepared = false; ended = false; paused = false; seekDone = false; bailing = false;
        firstFrameRendered = false; playStartedAt = 0L;
        startMs = Math.max(0, pos);
        url = buildUrl();
        posMs = startMs;
        open();
        pui.setHint(L.t("杜比视界按 HEVC 基础层重试…"));
    }

    /** 杜比视界 → HEVC 基础层（profile 7/8 才有可用基础层） */
    static final class DvHevcExtractors implements androidx.media3.extractor.ExtractorsFactory {
        private final androidx.media3.extractor.ExtractorsFactory base;
        DvHevcExtractors(androidx.media3.extractor.ExtractorsFactory b) { this.base = b; }
        @Override public androidx.media3.extractor.Extractor[] createExtractors() { return wrap(base.createExtractors()); }
        @Override public androidx.media3.extractor.Extractor[] createExtractors(android.net.Uri uri,
                java.util.Map<String, java.util.List<String>> headers) { return wrap(base.createExtractors(uri, headers)); }
        private static androidx.media3.extractor.Extractor[] wrap(androidx.media3.extractor.Extractor[] src) {
            androidx.media3.extractor.Extractor[] out = new androidx.media3.extractor.Extractor[src.length];
            for (int i = 0; i < src.length; i++) out[i] = new Dex(src[i]);
            return out;
        }
        static final class Dex implements androidx.media3.extractor.Extractor {
            private final androidx.media3.extractor.Extractor d;
            Dex(androidx.media3.extractor.Extractor d) { this.d = d; }
            @Override public boolean sniff(androidx.media3.extractor.ExtractorInput in) throws java.io.IOException { return d.sniff(in); }
            @Override public void init(androidx.media3.extractor.ExtractorOutput o) { d.init(new Dout(o)); }
            @Override public int read(androidx.media3.extractor.ExtractorInput in,
                    androidx.media3.extractor.PositionHolder ph) throws java.io.IOException { return d.read(in, ph); }
            @Override public void seek(long pos, long timeUs) { d.seek(pos, timeUs); }
            @Override public void release() { d.release(); }
        }
        static final class Dout implements androidx.media3.extractor.ExtractorOutput {
            private final androidx.media3.extractor.ExtractorOutput d;
            Dout(androidx.media3.extractor.ExtractorOutput d) { this.d = d; }
            @Override public androidx.media3.extractor.TrackOutput track(int id, int type) { return new Dtr(d.track(id, type)); }
            @Override public void endTracks() { d.endTracks(); }
            @Override public void seekMap(androidx.media3.extractor.SeekMap m) { d.seekMap(m); }
        }
        static final class Dtr implements androidx.media3.extractor.TrackOutput {
            private final androidx.media3.extractor.TrackOutput d;
            Dtr(androidx.media3.extractor.TrackOutput d) { this.d = d; }
            @Override public void format(androidx.media3.common.Format f) {
                if (DV_TO_HEVC && "video/dolby-vision".equals(f.sampleMimeType)) {
                    String c = f.codecs == null ? "" : f.codecs;
                    // Profile 7 带独立基础层；Profile 5 是单层 ICtCp，硬解只能解其 HEVC 主干，色彩依赖后端处理
                    f = f.buildUpon().setSampleMimeType("video/hevc").setCodecs(null).build();
                    android.util.Log.e("LMBY", "DV→HEVC 基础层已套用 codecs=" + c);
                }
                d.format(f);
            }
            @Override public void durationUs(long us) { d.durationUs(us); }
            @Override public int sampleData(androidx.media3.common.DataReader r, int len, boolean p) throws java.io.IOException { return d.sampleData(r, len, p); }
            @Override public int sampleData(androidx.media3.common.DataReader r, int len, boolean p, int part) throws java.io.IOException { return d.sampleData(r, len, p, part); }
            @Override public void sampleData(androidx.media3.common.util.ParsableByteArray b, int len) { d.sampleData(b, len); }
            @Override public void sampleData(androidx.media3.common.util.ParsableByteArray b, int len, int part) { d.sampleData(b, len, part); }
            @Override public void sampleMetadata(long t, int flags, int size, int offset,
                    androidx.media3.extractor.TrackOutput.CryptoData cd) { d.sampleMetadata(t, flags, size, offset, cd); }
        }
    }

    /** 硬解没画面：不整段放弃，先换成系统软解（MediaCodec PREFER_SOFTWARE）重开一次 */
    private void retrySoftCodec() {
        long pos = 0;
        try { if (player != null) pos = tcStartMs + Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) { }
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        prepared = false; ended = false; paused = false; seekDone = false; bailing = false;
        firstFrameRendered = false; playStartedAt = 0L;
        startMs = Math.max(0, pos);
        url = buildUrl();
        posMs = startMs;
        open();
        pui.setHint(L.t("软解重试中…"));
    }

    /** 转码流 seek：释放旧内核，用 StartTimeTicks 从目标点重新起转，进度偏移保持在目标位置 */
    private void retranscodeFrom(long t) {
        android.util.Log.e("LMBY", "转码流 seek → 从 " + t + "ms 重新起转");
        tcStartMs = Math.max(0, t);
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        prepared = false; ended = false; paused = false; seekDone = false; bailing = false;
        startMs = 0;
        url = buildUrl();
        posMs = tcStartMs;
        open();
        pui.setHint(L.t("重新转码中…"));
    }

    /** 菜单里切播放器 / 转码开关：按新参数重建内核，保留进度 */
    private void restartEngine() {
        long pos = startMs;
        try { if (player != null) pos = tcStartMs + Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) { }
        if (pos <= 0) pos = startMs;
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        prepared = false; ended = false; paused = false; seekDone = false; bailing = false;
        if (isTranscoding()) {
            // 切分辨率/码率后从当前位置重新起转
            tcStartMs = Math.max(0, pos);
            startMs = 0;
        } else {
            startMs = Math.max(0, pos);
        }
        url = buildUrl();
        open();
    }

    // ---------- 长按快进/后退：与 libVLC 版同一套档位与节流 ----------

    private int seekDir = 0;
    private long seekPressAt, seekLastApplyAt, seekTarget, seekBase;

    /** 按住时长换算累计前进距离：1.4s 内 10 倍速，之后逐档加速到 160 倍；按时间积分，刷新多少次都不影响总距离 */
    private long holdDistanceMs(long heldMs) {
        long[] segMs = {1400, 1400, 1800, 2400, Long.MAX_VALUE / 4};
        int[] mult = {10, 20, 40, 80, 160};
        long d = 0, t = 0;
        for (int i = 0; i < mult.length && t < heldMs; i++) {
            long seg = Math.min(segMs[i], heldMs - t);
            d += (long) (seg * mult[i] / 1000.0);
            t += seg;
        }
        return d;
    }
    private Runnable seekHoldRunnable;

    /**
     * 按住倍率制：每 90ms 一步，速度 = mult 倍实时（对齐当贝：按住 3 秒 ≈ 快进 17 分钟）。
     * 0.8s 内 50×，之后 180/640/1600/2800×。
     */
    private long holdStepMs(long heldMs, int dir) {
        int mult;
        if (heldMs < 800) mult = 50;
        else if (heldMs < 1800) mult = 180;
        else if (heldMs < 3000) mult = 640;
        else if (heldMs < 4500) mult = 1600;
        else mult = 2800;
        return 90L * mult;   // 后退同速，钳制交给 clampSeek
    }

    /** 快进/后退落点统一钳制：有总时长压到「时长 - 1 秒」；拿不到时长则单次按住最多越过起点 10 分钟 */
    private long clampSeek(long t) {
        if (t < 0) t = 0;
        if (durMs > 0) {
            if (t > durMs - 1000) t = durMs - 1000;
        } else {
            long cap = seekBase + 600_000L;
            if (t > cap) t = cap;
        }
        return t;
    }

    /** 长按由自己计时驱动：起点 300ms，之后每 90ms 走一步（步长随按住时长变大） */
    private void startSeekHold(final int dir) {
        stopSeekHold();
        seekHoldRunnable = new Runnable() {
            @Override public void run() {
                if (seekDir != dir) return;
                seekKeyDown(dir, true);
                ui.postDelayed(this, 90);
            }
        };
        ui.postDelayed(seekHoldRunnable, 300);
    }

    /** 连发刷新间隔：16ms ≈ 60fps，进度条动画才顺 */
    private static final long SEEK_TICK_MS = 16;

    private void stopSeekHold() {
        if (seekHoldRunnable != null) ui.removeCallbacks(seekHoldRunnable);
        seekHoldRunnable = null;
    }

    /** 按下立刻走一步；连发只推进目标位置与进度条，松手才真正 seekTo（避免进度条来回摆） */
    private void seekKeyDown(int dir, boolean repeat) {
        long now = SystemClock.uptimeMillis();
        if (!repeat) {
            seekDir = dir;
            seekPressAt = now;
            seekLastApplyAt = now;
            seekBase = posMs;
            seekTarget = seekBase + dir * 10000L;   // 单击前后都走 10 秒（对齐当贝）
            seekCommit(seekTarget);
            if (pui != null) pui.previewSeek(seekTarget, durMs);
            showHud();
            startSeekHold(dir);
            return;
        }
        if (seekDir == 0) return;
        if (now - seekLastApplyAt < 85) { showHud(); return; }
        seekLastApplyAt = now;
        // 连发时按「按住时长决定步长」累加，只更新目标位置和进度条，松手才真正跳一次
        long step = holdStepMs(now - seekPressAt, seekDir);
        seekTarget = clampSeek(seekTarget + seekDir * step);
        posMs = seekTarget;
        refreshBar();
        refreshTime();
        if (pui != null) pui.previewSeek(seekTarget, durMs);
        showHud();
    }

    private void seekKeyUp(int dir) {
        // 单击时按下已经提交过同一落点，这里不再重复 seekTo（重复会多一次重新缓冲）
        if (seekDir == dir && seekTarget != lastCommit) {
            seekCommit(clampSeek(seekTarget));
        }
        stopSeekHold();
        seekDir = 0;
        if (pui != null) pui.endSeekPreview();
        showHud();
    }

    private long lastCommit = -1;
    /** seek 落点后的一小段窗口内不把进度拉回真实位置，避免进度条来回摆 */
    private long seekLockUntil = 0;

    private void seekCommit(long t) {
        seekLockUntil = android.os.SystemClock.elapsedRealtime() + 2200;
        if (player == null || !prepared) {
            android.util.Log.e("LMBY", "sys seek 跳过 t=" + t + " player=" + (player != null) + " prepared=" + prepared);
            return;
        }
        if (t < 0) t = 0;
        if (durMs > 0 && t > durMs - 1000) t = durMs - 1000;
        if (isTranscoding()) {
            // 渐进式转码流没有 seek 索引，只能让服务器从目标点重新起转
            lastCommit = t;
            retranscodeFrom(t);
            refreshBar();
            refreshTime();
            pui.clearHint();
            return;
        }
        android.util.Log.e("LMBY", "sys seek → " + t + " (dir=" + seekDir + ")");
        player.seekTo(t);
        lastCommit = t;
        posMs = t;
        refreshBar();
        refreshTime();
        pui.clearHint();
    }

    private void showHud() { pui.show(); }

    private void hideHudSoon() { pui.keepAlive(); }

    private boolean hudVisible() { return pui.visible(); }

    /** 当前是否有可解码的视频轨（FFmpeg 重试用它判断软解是否真的能出画） */
    private boolean hasVideoTrack() {
        try {
            androidx.media3.common.Tracks ts = player.getCurrentTracks();
            for (androidx.media3.common.Tracks.Group g : ts.getGroups()) {
                if (g.getType() == androidx.media3.common.C.TRACK_TYPE_VIDEO && g.isSupported()) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // 卡缓冲看门狗：MPEG-TS / MPEG-2 这类老封装会让 Exo 的 TS 解析器停在
            // "Playback stuck buffering and not loading"——loader 不报错、缓冲永远 0、
            // 既不抛 onPlayerError 也永远到不了 READY。直连 8 秒、转码 30 秒（J1900 首帧慢）
            // 没有任何新增数据就交给 libVLC。
            if (!bailing && !stallBailed && !prepared && player != null
                    && player.getPlaybackState() == Player.STATE_BUFFERING
                    && bufferingSince > 0) {
                long noData = SystemClock.elapsedRealtime() - bufferingSince;
                long limit = isTranscoding() ? 30_000L : 8_000L;
                long buf = 0;
                try { buf = player.getBufferedPosition(); } catch (Throwable ignored) { }
                if (noData >= limit && buf <= 0) {
                    stallBailed = true;
                    android.util.Log.e("LMBY", "卡缓冲 " + (noData / 1000) + " 秒无数据 → 回退 libVLC");
                    toast(L.t("这个片源 Exo 加载不动，改用 libVLC"));
                    bailToVlc();
                    return;
                }
            }
            // 兜底看门狗：位置在走、但 6 秒都没渲染出第一帧 = 硬解"自称支持"其实出不来画面（黑屏）
            if (!firstFrameRendered && !bailing && player != null && player.isPlaying()
                    && player.getPlaybackState() == Player.STATE_READY) {
                if (playStartedAt == 0) playStartedAt = SystemClock.elapsedRealtime();
                else if (SystemClock.elapsedRealtime() - playStartedAt >= 8000) {
                    if (!softMcTried) {
                        softMcTried = true; softMc = true;
                        android.util.Log.e("LMBY", "硬解 8 秒没有首帧 → 换系统软解再试一次");
                        toast(L.t("硬解出不来画面，改用软解重试"));
                        retrySoftCodec();
                        return;
                    }
                    android.util.Log.e("LMBY", "软解也没首帧 → 回退 libVLC（硬解无画面）");
                    toast(L.t("这个编码硬解出不了画面，改用 libVLC"));
                    bailToVlc();
                    return;
                }
            }
            // 硬解失败 → 已切纯 FFmpeg 重试，或用户手动选了软件解码，但 FFmpeg 里没有这个视频解码器
            // （Xvid/VC-1 等）：表现为只有音频轨、屏幕全黑。
            // 注意必须「真的播了 8 秒以上还既无视频轨又无首帧」才算，否则刚起播那一两秒
            // 轨道还没选完就会被误判成解不了（以前就是这么把能播的片子送去 VLC 的）
            int sdm = pstore == null ? 0 : pstore.decodeMode();
            if ((forceFfmpeg || sdm == 1) && !bailing && player != null
                    && player.getPlaybackState() == Player.STATE_READY) {
                long played = 0;
                try { played = player.getCurrentPosition(); } catch (Throwable ignored) { }
                if (played >= 8000 && !firstFrameRendered && !hasVideoTrack()) {
                    ffmpegNoVideoTicks++;
                    if (ffmpegNoVideoTicks >= 2) {
                        android.util.Log.e("LMBY", "FFmpeg 播了 " + (played / 1000) + " 秒仍无视频轨/首帧 → 回退 libVLC");
                        toast(L.t("这个编码 FFmpeg 解不了，改用 libVLC"));
                        bailToVlc();
                        return;
                    }
                } else {
                    ffmpegNoVideoTicks = 0;
                }
            }
            android.util.Log.e("LMBY", "tick state=" + (player == null ? -9 : player.getPlaybackState())
                    + " playing=" + (player != null && player.isPlaying())
                    + " pos=" + posMs + " buf=" + (player == null ? -1 : player.getBufferedPosition())
                    + " dur=" + durMs);
            if (player != null && prepared && !ended) {
                // 刚 seek 完还在缓冲时别用旧位置把进度拉回去，否则进度条会来回摆
                if (seekDir == 0 && SystemClock.elapsedRealtime() >= seekLockUntil)
                    posMs = tcStartMs + Math.max(0, player.getCurrentPosition());
                if (!paused) refreshBar();
                refreshTime();
                refreshPlayIcon();
                long now = SystemClock.elapsedRealtime();
                if (now - lastSave > 10_000 && itemId != null && !itemId.isEmpty()) {
                    lastSave = now;
                    final long t = posMs;
                    final String psid = playSessionId;
                    new Thread(new Runnable() { public void run() {
                        try { Api.reportProgress(session(), itemId, t, false, psid); } catch (Throwable ignored) { }
                    }}).start();
                }
                updateSpeed();
                // 倍速守护：切换瞬间音频渲染器重配会让速度短暂回落，几秒内拉回目标
                if (targetSpeed != 1f && SystemClock.elapsedRealtime() < speedGuardUntil) {
                    try {
                        float cur = player.getPlaybackParameters().speed;
                        if (Math.abs(cur - targetSpeed) > 0.02f)
                            player.setPlaybackParameters(
                                    new androidx.media3.common.PlaybackParameters(targetSpeed, 1f));
                    } catch (Throwable ignored) { }
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    private void updateSpeed() {
        if (!hudVisible()) { pui.setNetSpeed(""); statT = 0; return; }
        long now = SystemClock.elapsedRealtime();
        long bytes = mediaStatBytes();
        if (statT == 0 || now - statT >= 1000) {
            if (statT > 0 && bytes > 0 && statB > 0) {
                float mb = (bytes - statB) / 1024f / 1024f;
                pui.setNetSpeed(String.format("%.1f MB/s", mb));
            } else {
                pui.setNetSpeed("");
            }
            statT = now; statB = bytes;
        }
    }

    private long mediaStatBytes() {
        try {
            long rx = android.net.TrafficStats.getUidRxBytes(getApplicationInfo().uid);
            if (rx > 0) return rx;
        } catch (Throwable ignore) { }
        try {
            return android.net.TrafficStats.getTotalRxBytes();
        } catch (Throwable e) { return -1; }
    }

    private void refreshBar() { pui.setProgress(posMs, durMs); }

    private void refreshTime() { pui.setTimes(posMs, durMs); }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    private Api.Session session() {
        Api.Session s = new Api.Session();
        s.server = server; s.token = token; s.userId = userId; s.deviceId = deviceId;
        return s;
    }

    /** 退回 libVLC 播放器，继承当前进度；同一时刻只退一次 */
    private void bailToVlc() {
        if (bailing) return;
        // 用户已经退出（BACK / 切第三方 / 被系统回收）：不能再弹一个 libVLC 出来，
        // 否则就是"退出播放器又自己跳回播放器"
        if (closing || isFinishing()) {
            android.util.Log.e("LMBY", "已退出播放器，忽略回退 libVLC");
            return;
        }
        android.util.Log.e("LMBY", "bailToVlc 触发", new Throwable("bail stack"));
        // 强制 ExoPlayer 时也照样回退：卡死/解不了就是看不了，回退到 libVLC 才有画面
        if (pstore != null && pstore.engine() == 1) {
            android.util.Log.e("LMBY", "强制 ExoPlayer 但本片解不了，仍然回退 libVLC");
            toast(L.t("这个片源 ExoPlayer 解不了，已临时改用 libVLC"));
        }
        bailing = true;
        try {
            long pos = 0;
            try { if (player != null) pos = tcStartMs + Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) { }
            if (pos <= 0) pos = startMs;
            android.content.Intent i = new android.content.Intent(this, PlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("title", titleS == null ? "" : titleS);
            i.putExtra("itemId", itemId == null ? "" : itemId);
            i.putExtra("server", server);
            i.putExtra("token", token);
            i.putExtra("userId", userId);
            i.putExtra("deviceId", deviceId);
            i.putExtra("startMs", pos);
            if (bailAutoSub) i.putExtra("autoSub", "1");
            startActivity(i);
        } catch (Throwable ignored) {
        }
        finish();
    }

    private void toast(String t) {
        if (pui != null) pui.toast(t);
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null && prepared) {
            if (player.isPlaying()) player.pause();
            paused = true;
            refreshPlayIcon();
            long p = player.getCurrentPosition();
            if (itemId != null && !itemId.isEmpty() && p > 0)
                Api.reportProgress(session(), itemId, p, true, playSessionId);
        }
    }

    @Override protected void onDestroy() {
        if (alive > 0) alive--;
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) { }
        ui.removeCallbacksAndMessages(null);
        if (pui != null) pui.release();
        // 记下「这部剧最后播的是哪一集」：详情页的续播按钮和「继续播放」提示都认它
        if (seriesId != null && !seriesId.isEmpty() && itemId != null && !itemId.isEmpty()) {
            try {
                pstore.setLastEp(seriesId, itemId);
                pstore.setLastEpAt(seriesId, System.currentTimeMillis());
            } catch (Throwable ignored) { }
        }
        if (player != null) {
            try {
                long p = Math.max(0, player.getCurrentPosition());
                if (p <= 0) p = startMs;
                if (itemId != null && !itemId.isEmpty() && p > 0)
                    Api.reportStop(session(), itemId, p, playSessionId);
            } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        if (texSurface != null) { try { texSurface.release(); } catch (Throwable ignored) { } texSurface = null; }
        super.onDestroy();
    }
}
