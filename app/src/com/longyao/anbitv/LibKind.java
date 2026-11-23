package com.longyao.anbitv;

/**
 * 媒体库 / 媒体项目 / 图片位 的类型识别引擎（纯静态、无状态，独立于缩放与网格）。
 * 只做"这是什么库、这是什么条目、该用哪张图、该给哪种卡片"，具体样式仍由 MainActivity / Cards 决定。
 */
public final class LibKind {

    // ===== 媒体库类型（来自 Emby 的 CollectionType） =====
    public static final int UNKNOWN = 0;
    public static final int MOVIES = 1;
    public static final int TV = 2;
    public static final int MUSIC = 3;
    public static final int PHOTOS = 4;
    public static final int HOMEVIDEOS = 5;
    public static final int BOOKS = 6;
    public static final int MIXED = 7;
    public static final int MUSICVIDEOS = 8;

    public static int ofLib(String collectionType) {
        String t = collectionType == null ? "" : collectionType.trim().toLowerCase();
        if ("movies".equals(t)) return MOVIES;
        if ("tvshows".equals(t)) return TV;
        if ("music".equals(t)) return MUSIC;
        if ("photos".equals(t)) return PHOTOS;
        if ("homevideos".equals(t)) return HOMEVIDEOS;
        if ("books".equals(t)) return BOOKS;
        if ("mixed".equals(t)) return MIXED;
        if ("musicvideos".equals(t)) return MUSICVIDEOS;
        return UNKNOWN;
    }

    /**
     * 进库时向服务器要哪些条目类型。
     * 混合库 / 未知库给一份"全媒体白名单"（含照片、剧集、专辑、书），
     * 避免什么都没滤到导致空网格，同时挡掉 Studio / Genre / Person 这类非媒体节点。
     */
    public static String includeTypes(int libKind, boolean folderMode) {
        if (folderMode) {
            // 文件夹视图走非递归列表，不需要类型过滤
            return null;
        }
        switch (libKind) {
            case MOVIES:      return "Movie,Video";
            case TV:          return "Series";
            case MUSIC:       return "MusicAlbum,MusicArtist,Audio";
            case PHOTOS:      return "Photo,Image";
            // 照片有独立的「照片」分页，视频分页只放视频，别把照片混进来
            case HOMEVIDEOS:  return "Video";
            case BOOKS:       return "Book";
            case MUSICVIDEOS: return "MusicVideo,MusicArtist";
            default:
                // 混合/未知库：视频分页照样排除照片，照片走「照片」分页
                return "Movie,Episode,Video,MusicVideo,Series,Season,"
                        + "MusicAlbum,MusicArtist,Audio,Book";
        }
    }

    /** 该库要不要额外拉 People（书籍需要作者，其它不用，省流量） */
    public static boolean wantsPeople(int libKind) {
        return libKind == BOOKS;
    }

    // ===== 媒体项目类型（来自 Type 字段） =====
    public static final int I_OTHER = 0;
    public static final int I_MOVIE = 1;
    public static final int I_SERIES = 2;
    public static final int I_SEASON = 3;
    public static final int I_EPISODE = 4;
    public static final int I_ALBUM = 5;
    public static final int I_AUDIO = 6;
    public static final int I_ARTIST = 7;
    public static final int I_PHOTO = 8;
    public static final int I_VIDEO = 9;
    public static final int I_FOLDER = 10;
    public static final int I_BOOK = 11;
    public static final int I_MUSICVIDEO = 12;

    public static int ofItem(String type) {
        String t = type == null ? "" : type.trim();
        if ("Movie".equals(t)) return I_MOVIE;
        if ("Series".equals(t)) return I_SERIES;
        if ("Season".equals(t)) return I_SEASON;
        if ("Episode".equals(t)) return I_EPISODE;
        if ("MusicAlbum".equals(t)) return I_ALBUM;
        if ("Audio".equals(t)) return I_AUDIO;
        if ("MusicArtist".equals(t) || "Artist".equals(t)) return I_ARTIST;
        if ("Photo".equals(t) || "Image".equals(t)) return I_PHOTO;
        if ("Video".equals(t)) return I_VIDEO;
        if ("MusicVideo".equals(t)) return I_MUSICVIDEO;
        if ("Folder".equals(t) || "CollectionFolder".equals(t)
                || "BoxSet".equals(t) || "PhotoAlbum".equals(t)) return I_FOLDER;
        if ("Book".equals(t)) return I_BOOK;
        return I_OTHER;
    }

    /** 专辑 / 歌曲 / 艺术家：用正方形封面、显示音乐类信息、不套电影评分逻辑 */
    public static boolean isMusic(int ik) {
        return ik == I_ALBUM || ik == I_AUDIO || ik == I_ARTIST;
    }

    /** 天然正方形的封面（专辑 / 艺术家 / 歌曲共用专辑封面） */
    public static boolean squareCover(int ik) {
        return ik == I_ALBUM || ik == I_ARTIST;
    }

    /** 能直接播放的条目 */
    public static boolean playable(int ik) {
        return ik == I_MOVIE || ik == I_VIDEO || ik == I_EPISODE
                || ik == I_AUDIO || ik == I_MUSICVIDEO || ik == I_PHOTO;
    }

    /** 打开后是"容器"（要继续铺一层子项） */
    public static boolean container(int ik) {
        return ik == I_SERIES || ik == I_ALBUM || ik == I_ARTIST
                || ik == I_SEASON || ik == I_FOLDER;
    }

    // ===== 图片位选择（Primary / Thumb / 专辑封面 / 原始图） =====
    public static final int IMG_PRIMARY = 0;
    public static final int IMG_THUMB = 1;
    public static final int IMG_BACKDROP = 2;

    /**
     * 卡片该用哪张图：
     * 电影/剧集/季/书 → Primary；集 / 家庭视频 → 有 Thumb 先用 Thumb；
     * 音乐 → 专辑封面（歌曲用父级专辑的 Primary）；照片 → 原始图（Primary，不裁）。
     */
    public static int imageSlot(int ik, boolean hasPrimary, boolean hasThumb) {
        switch (ik) {
            case I_EPISODE:
            case I_VIDEO:
            case I_MUSICVIDEO:
                return hasThumb ? IMG_THUMB : IMG_PRIMARY;
            case I_PHOTO:
                return IMG_PRIMARY;
            default:
                return hasPrimary ? IMG_PRIMARY : (hasThumb ? IMG_THUMB : IMG_PRIMARY);
        }
    }
}
