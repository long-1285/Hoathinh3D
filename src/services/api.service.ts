import { DonghuaMovie, EpisodeItem, GenreItem, ServerGroup } from '../types';
import { WP_SOURCE_CONFIG, WpDonghuaService } from './yanhh3d.service';
import {
  expandVietnameseSearchQueries,
  removeVietnameseTones,
  scoreMovieMatch
} from '../utils/vietnamese';

interface CacheEntry<T> {
  data: T;
  expiresAt: number;
}

const CACHE_TTL_MS = 2 * 60 * 60 * 1000;
const memoryCache = new Map<string, CacheEntry<unknown>>();

function getCached<T>(key: string): T | null {
  const mem = memoryCache.get(key) as CacheEntry<T> | undefined;
  if (mem && mem.expiresAt > Date.now()) {
    return mem.data;
  }
  try {
    const raw = sessionStorage.getItem(`hh3d_cache_${key}`);
    if (raw) {
      const parsed = JSON.parse(raw) as CacheEntry<T>;
      if (parsed.expiresAt > Date.now()) {
        memoryCache.set(key, parsed);
        return parsed.data;
      }
      sessionStorage.removeItem(`hh3d_cache_${key}`);
    }
  } catch {}
  return null;
}

function setCached<T>(key: string, data: T): void {
  const entry: CacheEntry<T> = {
    data,
    expiresAt: Date.now() + CACHE_TTL_MS
  };
  memoryCache.set(key, entry);
  try {
    sessionStorage.setItem(`hh3d_cache_${key}`, JSON.stringify(entry));
  } catch {}
}

async function fetchJsonWithTimeout(url: string, timeoutMs = 6500): Promise<any> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal: controller.signal
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } finally {
    window.clearTimeout(timer);
  }
}

function parseFirstInt(val: unknown, fallback = 1): number {
  const m = String(val || '').match(/\d+/);
  if (!m) return fallback;
  const n = parseInt(m[0], 10);
  return n > 0 ? n : fallback;
}

function normalizeTitleForMatch(str: string): string {
  return removeVietnameseTones(str);
}

export function isDubbedServerName(serverName: string): boolean {
  const low = (serverName || '').toLowerCase();
  return (
    low.includes('thuyết') ||
    low.includes('thuyet') ||
    low.includes('lồng tiếng') ||
    low.includes('long tieng') ||
    low.includes('dub')
  );
}

export function selectPreferredServerIdx(
  serverGroups: ServerGroup[],
  preferredAudio: 'Vietsub' | 'Thuyết Minh' = 'Thuyết Minh'
): number {
  if (!serverGroups || serverGroups.length === 0) return 0;

  const indexed = serverGroups.map((g, idx) => ({
    idx,
    name: g.serverName || '',
    epCount: g.episodes?.length || 0,
    hasDirectHls: Boolean(g.episodes?.some((ep) => Boolean(ep.linkM3u8))),
    isDubbed: isDubbedServerName(g.serverName || ''),
    isExactThuyetMinh: (g.serverName || '').toLowerCase().includes('thuyết')
  }));

  const wantDubbed = preferredAudio === 'Thuyết Minh';
  const matching = indexed.filter((item) => (wantDubbed ? item.isDubbed : !item.isDubbed));
  const pool = matching.length > 0 ? matching : indexed;

  pool.sort((a, b) => {
    // Ưu tiên hàng đầu: Trình phát tích hợp (có linkM3u8 trực tiếp) thay vì Web Embed
    if (b.hasDirectHls !== a.hasDirectHls) return b.hasDirectHls ? 1 : -1;
    if (b.epCount !== a.epCount) return b.epCount - a.epCount;
    if (wantDubbed && b.isExactThuyetMinh !== a.isExactThuyetMinh) {
      return b.isExactThuyetMinh ? 1 : -1;
    }
    return a.idx - b.idx;
  });

  return pool[0].idx;
}

const SCHEDULE_DAYS: ('Thứ 2' | 'Thứ 3' | 'Thứ 4' | 'Thứ 5' | 'Thứ 6' | 'Thứ 7' | 'Chủ Nhật')[] = [
  'Thứ 2',
  'Thứ 3',
  'Thứ 4',
  'Thứ 5',
  'Thứ 6',
  'Thứ 7',
  'Chủ Nhật'
];

const NGUONC_GENRES: GenreItem[] = [
  { id: 'Tất cả', label: 'Tất cả NguonC', icon: 'fa-border-all', sourceSlug: 'the-loai:hoat-hinh' },
  { id: 'Thuyết Minh', label: 'Thuyết Minh', icon: 'fa-microphone-lines', sourceSlug: 'hoat-hinh:lang=thuyet-minh' },
  { id: 'Lồng Tiếng', label: 'Lồng Tiếng', icon: 'fa-volume-high', sourceSlug: 'hoat-hinh:lang=long-tieng' },
  { id: 'Cổ Trang', label: 'Cổ Trang', icon: 'fa-crown', sourceSlug: 'hoat-hinh:cat=co-trang' },
  { id: 'Hành Động', label: 'Hành Động', icon: 'fa-khanda', sourceSlug: 'hoat-hinh:cat=hanh-dong' },
  { id: 'Phiêu Lưu', label: 'Phiêu Lưu', icon: 'fa-compass', sourceSlug: 'hoat-hinh:cat=phieu-luu' },
  { id: 'Giả Tưởng', label: 'Giả Tưởng', icon: 'fa-dragon', sourceSlug: 'hoat-hinh:cat=than-thoai' },
  { id: 'Viễn Tưởng', label: 'Viễn Tưởng', icon: 'fa-user-astronaut', sourceSlug: 'hoat-hinh:cat=vien-tuong' },
  { id: 'Hài Hước', label: 'Hài Hước', icon: 'fa-face-laugh-beam', sourceSlug: 'hoat-hinh:cat=hai-huoc' },
  { id: 'Bí Ẩn', label: 'Bí Ẩn', icon: 'fa-mask', sourceSlug: 'hoat-hinh:cat=bi-an' }
];

function mergeMovieLists(lists: DonghuaMovie[][]): DonghuaMovie[] {
  const resultsMap = new Map<string, DonghuaMovie>();
  lists.forEach((list) => {
    list.forEach((movie) => {
      const key = movie.slug || movie.id;
      const existing = resultsMap.get(key);
      if (!existing) {
        resultsMap.set(key, movie);
      } else {
        const wpMap = new Map<string, NonNullable<DonghuaMovie['wpSources']>[number]>();
        [...(existing.wpSources || []), ...(movie.wpSources || [])].forEach((ws) => {
          wpMap.set(`${ws.baseUrl}_${ws.postId}`, ws);
        });
        resultsMap.set(key, {
          ...existing,
          yanPostId: existing.yanPostId || movie.yanPostId,
          yanUrl: existing.yanUrl || movie.yanUrl,
          wpSources: wpMap.size > 0 ? Array.from(wpMap.values()) : undefined,
          statusLabel: existing.statusLabel || movie.statusLabel,
          latestEp: Math.max(existing.latestEp || 1, movie.latestEp || 1),
          totalEp: Math.max(existing.totalEp || 1, movie.totalEp || 1),
          description: existing.description || movie.description,
          genres: Array.from(new Set([...existing.genres, ...movie.genres])),
          sources: Array.from(new Set([...existing.sources, ...movie.sources]))
        });
      }
    });
  });
  return Array.from(resultsMap.values());
}

export class ApiService {
  private static NGUONC_BASE = 'https://phim.nguonc.com';
  private static PHIMAPI_BASE = 'https://phimapi.com';

  static getOptimizedPosterUrl(url: string, width = 420): string {
    if (!url || !url.startsWith('http')) return url;
    if (url.includes('wsrv.nl') || url.endsWith('.webp')) return url;
    return `https://wsrv.nl/?url=${encodeURIComponent(url)}&w=${width}&output=webp&q=82`;
  }

  private static mapPhimApiItems(
    items: any[],
    sourceTag = 'NguonC',
    forcedGenre?: string
  ): DonghuaMovie[] {
    return items.map((it: any, index: number): DonghuaMovie => {
      const rawPoster = it.poster_url || it.thumb_url || '';
      const poster = rawPoster.startsWith('http')
        ? rawPoster
        : `https://phimimg.com/${rawPoster.replace(/^\/+/, '')}`;
      const rawThumb = it.thumb_url || it.poster_url || '';
      const thumb = rawThumb.startsWith('http')
        ? rawThumb
        : `https://phimimg.com/${rawThumb.replace(/^\/+/, '')}`;

      const langStr = String(it.lang || '').toLowerCase();
      const isDub = langStr.includes('thuyết') || langStr.includes('lồng');
      const cats: string[] = Array.isArray(it.category)
        ? it.category.map((c: any) => String(c.name || '').trim()).filter(Boolean)
        : [];
      if (forcedGenre && !cats.includes(forcedGenre)) {
        cats.unshift(forcedGenre);
      }
      if (cats.length === 0) cats.push('Hoạt Hình');

      return {
        id: it._id || it.slug,
        slug: it.slug,
        title: it.name,
        originalTitle: it.origin_name || it.name,
        studio: it.year ? `${it.year} • ${sourceTag}` : `${sourceTag} Stream`,
        latestEp: parseFirstInt(it.episode_current, 1),
        totalEp: parseFirstInt(it.episode_total, 24),
        rating: 9.6,
        views: `${(8.5 + ((index * 1.1) % 7)).toFixed(1)}M`,
        viewsNum: 8.5 + ((index * 1.1) % 7),
        quality: it.quality || 'FHD',
        audioModes: isDub ? ['Thuyết Minh', 'Vietsub'] : ['Vietsub', 'Thuyết Minh'],
        sources: [sourceTag],
        genres: cats,
        scheduleDay: SCHEDULE_DAYS[(index + 1) % SCHEDULE_DAYS.length],
        scheduleTime: `${10 + (index % 2)}:00`,
        poster,
        thumb,
        streamIndex: index % 4
      };
    });
  }

  private static mapNguonCItems(
    items: any[],
    sourceTag = 'NguonC',
    forcedGenre?: string
  ): DonghuaMovie[] {
    return items.map((it: any, index: number): DonghuaMovie => {
      const poster = it.poster_url_webp || it.poster_url || it.thumb_url_webp || it.thumb_url || '';
      const thumb = it.thumb_url_webp || it.thumb_url || poster;
      const isDub =
        String(it.language || '').toLowerCase().includes('thuyết') ||
        String(it.language || '').toLowerCase().includes('lồng');
      const genres = ['Hoạt Hình', 'Tu Tiên', 'Huyền Huyễn'];
      if (forcedGenre && !genres.includes(forcedGenre)) {
        genres.unshift(forcedGenre);
      }
      if (isDub && !genres.includes('Thuyết Minh')) {
        genres.push('Thuyết Minh');
      }
      return {
        id: it.id || it.slug,
        slug: it.slug,
        title: it.name,
        originalTitle: it.original_name || it.name,
        studio: it.year ? `${it.year} • ${sourceTag}` : `${sourceTag} Stream`,
        latestEp: parseFirstInt(it.current_episode, 1),
        totalEp: parseFirstInt(it.total_episodes, 52),
        rating: 9.6,
        views: `${(8.2 + ((index * 1.1) % 7)).toFixed(1)}M`,
        viewsNum: 8.2 + ((index * 1.1) % 7),
        quality: it.quality || 'FHD',
        audioModes: isDub ? ['Thuyết Minh', 'Vietsub'] : ['Vietsub', 'Thuyết Minh'],
        sources: [sourceTag],
        genres,
        scheduleDay: SCHEDULE_DAYS[(index + 2) % SCHEDULE_DAYS.length],
        scheduleTime: `${10 + (index % 2)}:00`,
        poster,
        thumb,
        description: it.description ? String(it.description).replace(/<[^>]+>/g, '') : undefined,
        streamIndex: index % 4
      };
    });
  }

  /**
   * Lấy toàn bộ phân loại phim chính thức của YanHH3D hoặc NguonC
   */
  static async fetchCategoriesForSource(sourceId: string): Promise<GenreItem[]> {
    const cacheKey = `cats_2src_${sourceId}`;
    const cached = getCached<GenreItem[]>(cacheKey);
    if (cached && cached.length > 0) return cached;

    try {
      if (sourceId === 'NguonC') {
        setCached(cacheKey, NGUONC_GENRES);
        return NGUONC_GENRES;
      }

      const res = await WpDonghuaService.fetchTaxonomies(WP_SOURCE_CONFIG.YanHH3D.baseUrl);
      if (res.genres.length > 0) {
        setCached(cacheKey, res.genres);
        return res.genres;
      }
    } catch (err) {
      console.warn(`fetchCategoriesForSource(${sourceId}) err:`, err);
    }
    return [];
  }

  /**
   * Nạp danh sách phim theo Nguồn (YanHH3D hoặc NguonC) + Phân loại cụ thể
   */
  static async fetchBySourceAndGenre(
    sourceId: string,
    genre: GenreItem,
    page = 1,
    limit = 30
  ): Promise<DonghuaMovie[]> {
    const cacheKey = `src_genre_v3_${sourceId}_${genre.id}_${genre.sourceSlug || ''}_${genre.wpCategoryId || ''}_${genre.wpStatusId || ''}_${page}`;
    const cached = getCached<DonghuaMovie[]>(cacheKey);
    if (cached && cached.length > 0) return cached;

    try {
      let movies: DonghuaMovie[] = [];

      if (sourceId === 'NguonC') {
        const spec = genre.sourceSlug || 'the-loai:hoat-hinh';
        if (spec.startsWith('hoat-hinh:')) {
          const filterExpr = spec.slice('hoat-hinh:'.length);
          const [fKey, fVal] = filterExpr.split('=');
          const qs = fKey === 'lang' ? `sort_lang=${encodeURIComponent(fVal)}` : `category=${encodeURIComponent(fVal)}`;
          const [p1, p2] = await Promise.allSettled([
            fetchJsonWithTimeout(`${this.PHIMAPI_BASE}/v1/api/danh-sach/hoat-hinh?page=${page}&limit=24&${qs}`),
            fetchJsonWithTimeout(`${this.PHIMAPI_BASE}/v1/api/danh-sach/hoat-hinh?page=${page + 1}&limit=24&${qs}`)
          ]);
          const items1 = p1.status === 'fulfilled' ? p1.value?.data?.items || [] : [];
          const items2 = p2.status === 'fulfilled' ? p2.value?.data?.items || [] : [];
          movies = this.mapPhimApiItems([...items1, ...items2], 'NguonC', genre.id);
        } else {
          const [kind, slug] = spec.includes(':') ? spec.split(':') : ['the-loai', spec];
          const [p1, p2, p3] = await Promise.allSettled([
            fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/films/${kind}/${slug}?page=${page}`),
            fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/films/${kind}/${slug}?page=${page + 1}`),
            fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/films/${kind}/${slug}?page=${page + 2}`)
          ]);
          const items1 = p1.status === 'fulfilled' ? p1.value?.items || [] : [];
          const items2 = p2.status === 'fulfilled' ? p2.value?.items || [] : [];
          const items3 = p3.status === 'fulfilled' ? p3.value?.items || [] : [];
          movies = this.mapNguonCItems([...items1, ...items2, ...items3], 'NguonC');
        }
      } else {
        const res = await WpDonghuaService.fetchMovies(WP_SOURCE_CONFIG.YanHH3D.baseUrl, 'YanHH3D', {
          page,
          perPage: limit,
          categoryId: genre.wpCategoryId || genre.yanCategoryId,
          statusId: genre.wpStatusId || genre.yanStatusId
        });
        movies = res.movies.map((m) => ({
          ...m,
          genres:
            genre.id !== 'Tất cả' &&
            genre.id !== 'Đang chiếu' &&
            genre.id !== 'Hoàn thành' &&
            !m.genres.includes(genre.id)
              ? [genre.id, ...m.genres]
              : m.genres
        }));
      }

      if (movies.length > 0) setCached(cacheKey, movies);
      return movies;
    } catch (err) {
      console.warn(`fetchBySourceAndGenre(${sourceId}, ${genre.id}) err:`, err);
      return [];
    }
  }

  /**
   * Nạp danh sách phim theo nguồn đang chọn (YanHH3D hoặc NguonC), dự phòng nguồn còn lại nếu lỗi
   */
  static async fetchDonghuaList(page = 1, limit = 30, preferredSourceId = 'YanHH3D'): Promise<DonghuaMovie[]> {
    const cacheKey = `list_src_v3_${preferredSourceId}_${page}_${limit}`;
    const cached = getCached<DonghuaMovie[]>(cacheKey);
    if (cached && cached.length > 0) return cached;

    const fetchYan = async (): Promise<DonghuaMovie[]> => {
      const res = await WpDonghuaService.fetchMovies(WP_SOURCE_CONFIG.YanHH3D.baseUrl, 'YanHH3D', {
        page,
        perPage: limit
      });
      return res.movies;
    };

    const fetchNguonC = async (): Promise<DonghuaMovie[]> => {
      const [p1, p2, p3] = await Promise.allSettled([
        fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/films/the-loai/hoat-hinh?page=${page}`),
        fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/films/the-loai/hoat-hinh?page=${page + 1}`),
        fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/films/the-loai/hoat-hinh?page=${page + 2}`)
      ]);
      const items1 = p1.status === 'fulfilled' ? p1.value?.items || [] : [];
      const items2 = p2.status === 'fulfilled' ? p2.value?.items || [] : [];
      const items3 = p3.status === 'fulfilled' ? p3.value?.items || [] : [];
      return this.mapNguonCItems([...items1, ...items2, ...items3], 'NguonC');
    };

    try {
      const primary = preferredSourceId === 'NguonC' ? await fetchNguonC() : await fetchYan();
      if (primary.length > 0) {
        setCached(cacheKey, primary);
        return primary;
      }
    } catch {}

    try {
      const fallback = preferredSourceId === 'NguonC' ? await fetchYan() : await fetchNguonC();
      if (fallback.length > 0) {
        setCached(cacheKey, fallback);
        return fallback;
      }
    } catch {}

    return [];
  }

  /**
   * Lấy chi tiết phim, ưu tiên luồng HLS (.m3u8) cho Trình phát Tích hợp và giữ Embed dự phòng
   */
  static async fetchMovieDetail(
    slug: string,
    movie?: DonghuaMovie
  ): Promise<{
    serverGroups: ServerGroup[];
    description?: string;
    poster?: string;
    totalEp?: number;
    latestEp?: number;
  }> {
    if (!slug && !movie?.yanUrl && (!movie?.wpSources || movie.wpSources.length === 0)) {
      return { serverGroups: [] };
    }

    const cacheKey = `detail_2src_v2_${slug || movie?.id}`;
    const cached = getCached<{
      serverGroups: ServerGroup[];
      description?: string;
      poster?: string;
      totalEp?: number;
      latestEp?: number;
    }>(cacheKey);
    if (cached && cached.serverGroups.length > 0) return cached;

    const serverGroups: ServerGroup[] = [];
    let description = movie?.description || '';
    let poster = movie?.poster || '';
    let totalEp = 0;
    let latestEp = 0;

    const targetNormTitle = movie?.title ? normalizeTitleForMatch(movie.title) : '';

    const fetchYanDetailTask = async () => {
      const cfg = WP_SOURCE_CONFIG.YanHH3D;
      const known = movie?.wpSources?.find((w) => w.baseUrl === cfg.baseUrl);
      let targetUrl = known?.url || movie?.yanUrl;
      let postId = known?.postId || movie?.yanPostId || 0;

      if (!targetUrl && slug) {
        try {
          const searchKw = movie?.title || slug.replace(/-/g, ' ');
          const res = await WpDonghuaService.fetchMovies(cfg.baseUrl, cfg.sourceName, {
            search: searchKw,
            perPage: 6
          });
          const matched = res.movies.find(
            (m) =>
              m.slug === slug ||
              (targetNormTitle && normalizeTitleForMatch(m.title) === targetNormTitle)
          );
          if (matched) {
            targetUrl = matched.wpSources?.[0]?.url || matched.yanUrl;
            postId = matched.wpSources?.[0]?.postId || matched.yanPostId || 0;
          }
        } catch {}
      }

      if (!targetUrl) return { serverGroups: [] as ServerGroup[], description: '', latestEp: 0, totalEp: 0 };
      return WpDonghuaService.fetchMovieDetail(
        cfg.baseUrl,
        cfg.sourceName,
        targetUrl,
        postId,
        movie?.description || ''
      );
    };

    const [phimApiResult, yanResult, nguonCResult] = await Promise.allSettled([
      slug ? fetchJsonWithTimeout(`${this.PHIMAPI_BASE}/phim/${slug}`, 4500) : Promise.resolve(null),
      fetchYanDetailTask(),
      slug ? fetchJsonWithTimeout(`${this.NGUONC_BASE}/api/film/${slug}`, 5500) : Promise.resolve(null)
    ]);

    const hlsByAudioAndEp = {
      dubbed: new Map<string, string>(),
      sub: new Map<string, string>()
    };

    // 1. Direct HLS (.m3u8) Engine cho Trình phát tích hợp
    if (phimApiResult.status === 'fulfilled' && phimApiResult.value?.status && Array.isArray(phimApiResult.value?.episodes)) {
      const pm = phimApiResult.value.movie;
      if (!description && pm?.content) {
        description = String(pm.content).replace(/<[^>]+>/g, '').trim();
      }
      if (!poster && pm?.poster_url) {
        poster = pm.poster_url.startsWith('http') ? pm.poster_url : `https://phimimg.com/${pm.poster_url}`;
      }
      let pmMaxEps = 0;
      phimApiResult.value.episodes.forEach((srv: any) => {
        const items = Array.isArray(srv.server_data) ? srv.server_data : [];
        const validItems = items.filter((ep: any) => ep.link_m3u8 || ep.link_embed);
        if (validItems.length > 0) {
          pmMaxEps = Math.max(pmMaxEps, validItems.length);
          const srvLabel = String(srv.server_name || 'HLS').replace(/^#?\w*\s*-?\s*/i, '').trim() || srv.server_name || 'HLS';
          const isDub = isDubbedServerName(srv.server_name || '');
          const targetMap = isDub ? hlsByAudioAndEp.dubbed : hlsByAudioAndEp.sub;

          serverGroups.push({
            serverName: `Trình phát Tích hợp • ${srvLabel}`,
            episodes: validItems.map((ep: any): EpisodeItem => {
              const epNumMatch = String(ep.slug || ep.name || '').match(/\d+/);
              if (ep.link_m3u8 && epNumMatch) {
                targetMap.set(epNumMatch[0], ep.link_m3u8);
              }
              return {
                name: String(ep.name || '').startsWith('Tập') ? ep.name : `Tập ${ep.name}`,
                slug: ep.slug || `tap-${ep.name}`,
                linkM3u8: ep.link_m3u8 || '',
                linkEmbed: ep.link_embed || ''
              };
            })
          });
        }
      });
      latestEp = Math.max(latestEp, pmMaxEps);
      totalEp = Math.max(totalEp, parseFirstInt(pm?.episode_total, 0), pmMaxEps);
    }

    const backfillM3u8 = (groups: ServerGroup[]) =>
      groups.map((g) => {
        const map = isDubbedServerName(g.serverName) ? hlsByAudioAndEp.dubbed : hlsByAudioAndEp.sub;
        if (map.size === 0) return g;
        return {
          ...g,
          episodes: g.episodes.map((ep) => {
            if (ep.linkM3u8) return ep;
            const num = String(ep.slug || ep.name || '').match(/\d+/)?.[0];
            const m3u8 = num ? map.get(num) : undefined;
            return m3u8 ? { ...ep, linkM3u8: m3u8 } : ep;
          })
        };
      });

    if (yanResult.status === 'fulfilled' && yanResult.value.serverGroups.length > 0) {
      serverGroups.push(...backfillM3u8(yanResult.value.serverGroups));
      if (!description && yanResult.value.description) description = yanResult.value.description;
      if (yanResult.value.latestEp) latestEp = Math.max(latestEp, yanResult.value.latestEp);
      if (yanResult.value.totalEp) totalEp = Math.max(totalEp, yanResult.value.totalEp);
    }

    if (nguonCResult.status === 'fulfilled' && nguonCResult.value?.movie) {
      const nm = nguonCResult.value.movie;
      if (!description && nm.description) {
        description = String(nm.description).replace(/<[^>]+>/g, '');
      }
      if (!poster) {
        poster = nm.poster_url_webp || nm.poster_url || nm.thumb_url || '';
      }
      const rawNguonCEps = Array.isArray(nm.episodes) ? nm.episodes : [];
      let ncMaxEps = 0;
      const ncGroups: ServerGroup[] = [];
      rawNguonCEps.forEach((srv: any) => {
        const items = Array.isArray(srv.items) ? srv.items : [];
        if (items.length > 0) {
          ncMaxEps = Math.max(ncMaxEps, items.length);
          ncGroups.push({
            serverName: `NguonC • ${srv.server_name || 'Server 1'}`,
            episodes: items.map((ep: any): EpisodeItem => ({
              name: ep.name?.startsWith('Tập') ? ep.name : `Tập ${ep.name}`,
              slug: ep.slug,
              linkM3u8: ep.m3u8 || '',
              linkEmbed: ep.embed || ''
            }))
          });
        }
      });
      serverGroups.push(...backfillM3u8(ncGroups));
      latestEp = Math.max(latestEp, ncMaxEps);
      totalEp = Math.max(totalEp, parseFirstInt(nm.total_episodes, 0), ncMaxEps);
    }

    const maxGroupEps = serverGroups.reduce((max, g) => Math.max(max, g.episodes.length), 0);
    latestEp = Math.max(latestEp, maxGroupEps, movie?.latestEp || 0);
    totalEp = Math.max(totalEp, latestEp, movie?.totalEp || 0);

    const finalResult = {
      serverGroups,
      description,
      poster,
      totalEp: totalEp || undefined,
      latestEp: latestEp || undefined
    };

    if (serverGroups.length > 0) {
      setCached(cacheKey, finalResult);
    }

    return finalResult;
  }

  /**
   * Tìm kiếm tiếng Việt tối ưu trên cả 2 nguồn chính thức: YanHH3D & NguonC
   */
  static async searchDonghua(keyword: string): Promise<DonghuaMovie[]> {
    const { raw, expanded, apiKeywords } = expandVietnameseSearchQueries(keyword);
    if (!raw) return [];

    const primaryKw = expanded || raw;
    const cacheKey = `search_2src_${primaryKw.toLowerCase()}`;
    const cached = getCached<DonghuaMovie[]>(cacheKey);
    if (cached) return cached;

    const searchQueries = apiKeywords.slice(0, 2);
    const tasks: Promise<DonghuaMovie[]>[] = [];

    for (const kw of searchQueries) {
      tasks.push(
        WpDonghuaService.fetchMovies(WP_SOURCE_CONFIG.YanHH3D.baseUrl, 'YanHH3D', {
          search: kw,
          perPage: 20
        }).then((r) => r.movies),
        fetchJsonWithTimeout(
          `${this.NGUONC_BASE}/api/films/search?keyword=${encodeURIComponent(kw)}`
        ).then((res) => this.mapNguonCItems(res?.items || [], 'NguonC'))
      );
    }

    const settled = await Promise.allSettled(tasks);
    const allLists: DonghuaMovie[][] = [];
    settled.forEach((res) => {
      if (res.status === 'fulfilled' && Array.isArray(res.value) && res.value.length > 0) {
        allLists.push(res.value);
      }
    });

    const merged = mergeMovieLists(allLists);
    const ranked = merged
      .map((movie) => ({ movie, score: scoreMovieMatch(movie, raw) }))
      .sort((a, b) => b.score - a.score || (b.movie.viewsNum || 0) - (a.movie.viewsNum || 0))
      .map((x) => x.movie);

    if (ranked.length > 0) setCached(cacheKey, ranked);
    return ranked;
  }
}
