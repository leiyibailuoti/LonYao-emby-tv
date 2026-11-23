package androidx.media3.exoplayer;

/**
 * 兼容垫片：nextlib 的 FFmpeg 视频渲染器里把静态方法写死在 RendererCapabilities$_CC（下划线）上，
 * 而 D8 新版 desugar 生成的伴生类是 RendererCapabilities$-CC（连字符），
 * 一旦真的用到 FFmpeg 视频渲染器就 NoClassDefFoundError 闪退。
 * 这里提供 nextlib 期望的同名类，转发给 media3 接口本身的静态 create 实现。
 */
public final class RendererCapabilities$_CC {
    private RendererCapabilities$_CC() { }

    public static int create(int formatSupport) {
        return RendererCapabilities.create(formatSupport);
    }

    public static int create(int formatSupport, int adaptiveSupport, int tunnelingSupport) {
        return RendererCapabilities.create(formatSupport, adaptiveSupport, tunnelingSupport);
    }

    public static int create(int formatSupport, int adaptiveSupport, int tunnelingSupport,
                             int hardwareAccelerationSupport) {
        return RendererCapabilities.create(formatSupport, adaptiveSupport, tunnelingSupport,
                hardwareAccelerationSupport);
    }
}
