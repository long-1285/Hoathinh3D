import { DonghuaMovie, EpisodeItem, GenreItem, ServerGroup } from '../types';
import { httpGetJson, httpGetText } from '../utils/httpClient';

const YAN_BASE = 'https://yanhh3d.ee';

export const WP_SOURCE_CONFIG: Record<string, { baseUrl: string; sourceName: string }> = {
  YanHH3D: { baseUrl: YAN_BASE, sourceName: 'YanHH3D' }
};

const CACHE_TTL_MS = 2 * 60 * 60 * 1000;

interface CacheItem<T> {
  data: T;
  expiresAt: number;
}

const wpCache = new Map<string, CacheItem<unknown>>();

function getWpCache<T>(key: string): T | null {
  const item = wpCache.get(key) as CacheItem<T> | undefined;
  if (item && item.expiresAt > Date.now()) return item.data;
  return null;
}

function setWpCache<T>(key: string, data: T): void {
  wpCache.set(key, { data, expiresAt: Date.now() + CACHE_TTL_MS });
}

function decodeHtmlEntities(str: string): string {
  if (!str) return '';
  return str
    .replace(/&#(\d+);/g, (_, dec) => String.fromCharCode(Number(dec)))
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#039;/g, "'")
    .replace(/&hellip;/g, '...')
    .replace(/<[^>]+>/g, '')
    .trim();
}

const CATEGORY_ICON_MAP: Record<string, string> = {
  'tu-tien': 'fa-wand-sparkles',
  'tien-hiep': 'fa-yin-yang',
  'huyen-huyen': 'fa-dragon',
  'kiem-hiep': 'fa-khanda',
  'co-trang': 'fa-crown',
  'do-thi': 'fa-city',
  'hai-huoc': 'fa-face-laugh-beam',
  'hien-dai': 'fa-bolt',
  'trung-sinh': 'fa-fire-flame-curved',
  'xuyen-khong': 'fa-hurricane',
  'luyen-cap': 'fa-angles-up',
  ova: 'fa-film'
};

const SHOWTIME_ID_TO_DAY: Record<
  number,
  'Thứ 2' | 'Thứ 3' | 'Thứ 4' | 'Thứ 5' | 'Thứ 6' | 'Thứ 7' | 'Chủ Nhật'
> = {
  4: 'Chủ Nhật',
  5: 'Thứ 2',
  6: 'Thứ 3',
  7: 'Thứ 4',
  8: 'Thứ 5',
  9: 'Thứ 6',
  10: 'Thứ 7'
};

export interface YanTaxonomyResult {
  genres: GenreItem[];
  categoryMap: Record<number, string>;
  totalMovies: number;
}

export interface YanHomeEpisodeMeta {
  latestEp: number;
  totalEp: number;
  quality?: string;
}

function parseTickRateBadge(raw: string): YanHomeEpisodeMeta | null {
  const clean = decodeHtmlEntities(raw);
  if (!clean) return null;
  const qualityMatch = clean.match(/\[([^\]]+)\]/);
  const quality = qualityMatch ? qualityMatch[1].trim() : undefined;
  const nums = clean.replace(/\[[^\]]+\]/g, '').match(/\d+/g);
  if (!nums || nums.length === 0) return null;
  const latestEp = parseInt(nums[0], 10);
  if (!latestEp || latestEp <= 0) return null;
  const totalEp = nums.length >= 2 ? Math.max(latestEp, parseInt(nums[1], 10)) : latestEp;
  return { latestEp, totalEp, quality };
}

export class WpDonghuaService {
  private static categoryMaps = new Map<string, Record<number, string>>();

  /**
   * Bóc tách nhanh badge tập mới nhất (Tập X/Y [4K]) từ trang chủ YanHH3D trong 1 request
   */
  static async fetchHomeEpisodeMeta(baseUrl = YAN_BASE): Promise<Record<string, YanHomeEpisodeMeta>> {
    const cacheKey = `wp_home_ep_meta_${baseUrl}`;
    const cached = getWpCache<Record<string, YanHomeEpisodeMeta>>(cacheKey);
    if (cached) return cached;

    const metaMap: Record<string, YanHomeEpisodeMeta> = {};
    try {
      const html = await httpGetText(`${baseUrl}/`, { Referer: `${baseUrl}/` }, 7500);
      const itemRegex =
        /<div[^>]+class=["'][^"']*flw-item[^"']*["'][^>]*>([\s\S]*?)<\/a>/gi;
      let match: RegExpExecArray | null;
      while ((match = itemRegex.exec(html)) !== null) {
        const block = match[1];
        const tickRaw = block.match(
          /<div[^>]+class=["'][^"']*tick-rate[^"']*["'][^>]*>([\s\S]*?)<\/div>/i
        )?.[1];
        const href = block.match(/href=["']([^"']+)["']/i)?.[1];
        if (!tickRaw || !href) continue;

        const parsed = parseTickRateBadge(tickRaw);
        if (!parsed) continue;

        const slug = href
          .replace(/\/+$/, '')
          .split('/')
          .pop()
          ?.replace(/\.html$/i, '');
        if (slug) {
          const prev = metaMap[slug];
          if (!prev || parsed.latestEp > prev.latestEp) {
            metaMap[slug] = parsed;
          }
        }
      }
      if (Object.keys(metaMap).length > 0) {
        setWpCache(cacheKey, metaMap);
      }
    } catch (err) {
      console.warn(`WP fetchHomeEpisodeMeta (${baseUrl}) err:`, err);
    }
    return metaMap;
  }

  private static getDefaultCatMap(): Record<number, string> {
    return {
      15: 'Cổ Trang',
      17: 'Đô Thị',
      19: 'Hài Hước',
      22: 'Hiện Đại',
      23: 'Kiếm Hiệp',
      24: 'Tiên Hiệp',
      21: 'Trùng Sinh',
      25: 'Tu Tiên',
      1: 'Xuyên Không'
    };
  }

  /**
   * Lấy toàn bộ phân loại phim (Thể loại, Trạng thái, Tổng số phim) từ nguồn WP Donghua bất kỳ
   */
  static async fetchTaxonomies(baseUrl = YAN_BASE): Promise<YanTaxonomyResult> {
    const cacheKey = `wp_tax_${baseUrl}`;
    const cached = getWpCache<YanTaxonomyResult>(cacheKey);
    if (cached) return cached;

    const categoryMap: Record<number, string> = {
      ...(this.categoryMaps.get(baseUrl) || this.getDefaultCatMap())
    };

    try {
      const [catsRes, statusesRes] = await Promise.allSettled([
        httpGetJson<any[]>(`${baseUrl}/wp-json/wp/v2/categories?per_page=100`),
        httpGetJson<any[]>(`${baseUrl}/wp-json/wp/v2/status?per_page=20`)
      ]);

      const cats = catsRes.status === 'fulfilled' ? catsRes.value.data : [];
      const statuses = statusesRes.status === 'fulfilled' ? statusesRes.value.data : [];

      let totalMovies = 0;
      const statusItems: GenreItem[] = [];
      if (Array.isArray(statuses)) {
        statuses.forEach((st) => {
          if (Number(st.count || 0) <= 0) return;
          const slug = String(st.slug || '').toLowerCase();
          const name = String(st.name || '').toLowerCase();
          if (slug.includes('ongoing') || name.includes('đang')) {
            statusItems.push({
              id: 'Đang chiếu',
              label: `Đang chiếu (${st.count})`,
              icon: 'fa-tower-broadcast',
              count: st.count,
              yanStatusId: st.id,
              wpStatusId: st.id
            });
          } else if (slug.includes('completed') || name.includes('hoàn')) {
            statusItems.push({
              id: 'Hoàn thành',
              label: `Hoàn thành (${st.count})`,
              icon: 'fa-circle-check',
              count: st.count,
              yanStatusId: st.id,
              wpStatusId: st.id
            });
          }
          totalMovies += Number(st.count || 0);
        });
      }

      const genreItems: GenreItem[] = [];
      if (Array.isArray(cats)) {
        cats
          .filter((c) => Number(c.count || 0) > 0)
          .sort((a, b) => Number(b.count || 0) - Number(a.count || 0))
          .forEach((c) => {
            categoryMap[c.id] = c.name;
            genreItems.push({
              id: c.name,
              label: `${c.name} (${c.count})`,
              icon: CATEGORY_ICON_MAP[c.slug] || 'fa-tag',
              count: c.count,
              sourceSlug: c.slug,
              yanCategoryId: c.id,
              wpCategoryId: c.id
            });
          });
        if (totalMovies === 0) {
          totalMovies = cats.reduce((acc, c) => Math.max(acc, Number(c.count || 0)), 0);
        }
      }

      this.categoryMaps.set(baseUrl, categoryMap);

      const genres: GenreItem[] = [
        {
          id: 'Tất cả',
          label: totalMovies > 0 ? `Tất cả (${totalMovies})` : 'Tất cả thể loại',
          icon: 'fa-border-all',
          count: totalMovies
        },
        ...statusItems,
        ...genreItems
      ];

      const result: YanTaxonomyResult = {
        genres,
        categoryMap,
        totalMovies
      };
      if (genreItems.length > 0) setWpCache(cacheKey, result);
      return result;
    } catch (err) {
      console.warn(`WP fetchTaxonomies (${baseUrl}) err:`, err);
      return {
        genres: [],
        categoryMap,
        totalMovies: 0
      };
    }
  }

  /**
   * Nạp danh sách phim từ WP Donghua (YanHH3D, HH3D, HHKungfu) theo trang, thể loại, trạng thái hoặc tìm kiếm
   */
  static async fetchMovies(
    baseUrl = YAN_BASE,
    sourceName = 'YanHH3D',
    options: {
      page?: number;
      perPage?: number;
      categoryId?: number;
      statusId?: number;
      showtimeId?: number;
      search?: string;
    } = {}
  ): Promise<{ movies: DonghuaMovie[]; totalPages: number; totalItems: number }> {
    const { page = 1, perPage = 30, categoryId, statusId, showtimeId, search } = options;
    const cacheKey = `wp_posts_${baseUrl}_${page}_${perPage}_${categoryId || ''}_${statusId || ''}_${showtimeId || ''}_${search || ''}`;
    const cached = getWpCache<{ movies: DonghuaMovie[]; totalPages: number; totalItems: number }>(
      cacheKey
    );
    if (cached) return cached;

    if (!this.categoryMaps.has(baseUrl)) {
      this.fetchTaxonomies(baseUrl).catch(() => {});
    }
    const catMap = this.categoryMaps.get(baseUrl) || this.getDefaultCatMap();

    const params = new URLSearchParams({
      page: String(page),
      per_page: String(perPage),
      _embed: '1'
    });
    if (categoryId) params.set('categories', String(categoryId));
    if (statusId) params.set('status', String(statusId));
    if (showtimeId) params.set('showtimes', String(showtimeId));
    if (search && search.trim()) params.set('search', search.trim());

    const isYanFlag = baseUrl === YAN_BASE;
    const [{ data: posts, headers }, homeEpMeta] = await Promise.all([
      httpGetJson<any[]>(`${baseUrl}/wp-json/wp/v2/posts?${params.toString()}`),
      isYanFlag
        ? this.fetchHomeEpisodeMeta(baseUrl).catch(() => ({} as Record<string, YanHomeEpisodeMeta>))
        : Promise.resolve({} as Record<string, YanHomeEpisodeMeta>)
    ]);

    const totalItems =
      parseInt(headers['x-wp-total'] || '0', 10) || (Array.isArray(posts) ? posts.length : 0);
    const totalPages = parseInt(headers['x-wp-totalpages'] || '1', 10) || 1;
    const idPrefix = isYanFlag ? 'yan' : sourceName.toLowerCase().replace(/[^a-z0-9]/g, '');

    const movies: DonghuaMovie[] = (Array.isArray(posts) ? posts : []).map((p, index) => {
      const title = decodeHtmlEntities(p.title?.rendered || p.slug || 'Donghua 3D');
      const featuredMedia = p._embedded?.['wp:featuredmedia']?.[0];
      const poster =
        featuredMedia?.media_details?.sizes?.medium_large?.source_url ||
        featuredMedia?.source_url ||
        'https://yanhh3d.ee/wp-content/uploads/2025/04/Tien-Nghich-300x450.webp';

      const genres: string[] = [];
      if (Array.isArray(p.categories)) {
        p.categories.forEach((cid: number) => {
          const name = catMap[cid];
          if (name && !genres.includes(name)) genres.push(name);
        });
      }
      if (genres.length === 0) genres.push('Tu Tiên');

      const showtimeIdFirst =
        Array.isArray(p.showtimes) && p.showtimes.length > 0 ? p.showtimes[0] : 0;
      const scheduleDay = SHOWTIME_ID_TO_DAY[showtimeIdFirst] || 'Chủ Nhật';

      const isCompleted = Array.isArray(p.status) && p.status.includes(12);
      const statusLabel = isCompleted ? 'Hoàn thành' : 'Đang chiếu';
      const desc = decodeHtmlEntities(p.content?.rendered || p.excerpt?.rendered || '');
      const slugMeta = p.slug ? homeEpMeta[p.slug] : undefined;
      const latestEp = slugMeta?.latestEp || 1;
      const totalEp = slugMeta?.totalEp || (isCompleted ? Math.max(latestEp, 52) : Math.max(latestEp, 180));
      const quality = slugMeta?.quality ? `${slugMeta.quality} • 1080p` : '4K / 1080p';

      return {
        id: `${idPrefix}_${p.id}`,
        slug: p.slug,
        ...(isYanFlag ? { yanPostId: p.id, yanUrl: p.link } : {}),
        wpSources: [{ baseUrl, postId: p.id, url: p.link, sourceName }],
        statusLabel,
        title,
        originalTitle: `${statusLabel} • ${sourceName}`,
        studio: `${sourceName} 4K`,
        latestEp,
        totalEp,
        rating: 9.8,
        views: `${(11 + ((index * 0.9) % 9)).toFixed(1)}M`,
        viewsNum: 11 + ((index * 0.9) % 9),
        quality,
        audioModes: ['Vietsub', 'Thuyết Minh'],
        sources: [sourceName],
        genres,
        scheduleDay,
        scheduleTime: '09:30',
        poster,
        thumb: poster,
        description: desc,
        streamIndex: index % 4
      };
    });

    const out = { movies, totalPages, totalItems };
    if (movies.length > 0) setWpCache(cacheKey, out);
    return out;
  }

  /**
   * Bóc tách chi tiết phim từ nguồn WP Donghua (hỗ trợ cả cấu trúc YanHH3D .ep-range và Halim .halim-server của HH3D/HHKungfu)
   */
  static async fetchMovieDetail(
    baseUrl: string,
    sourceName: string,
    targetUrl: string,
    fallbackPostId = 0,
    fallbackDesc = ''
  ): Promise<{
    serverGroups: ServerGroup[];
    description?: string;
    latestEp?: number;
    totalEp?: number;
  }> {
    if (!targetUrl) return { serverGroups: [] };

    const cacheKey = `wp_detail_${baseUrl}_${targetUrl}`;
    const cached = getWpCache<{
      serverGroups: ServerGroup[];
      description?: string;
      latestEp?: number;
      totalEp?: number;
    }>(cacheKey);
    if (cached && cached.serverGroups.length > 0) return cached;

    try {
      const detailHtml = await httpGetText(targetUrl, { Referer: `${baseUrl}/` });

      const descMatch =
        detailHtml.match(/<div[^>]+class=["'][^"']*film-description[^"']*["'][^>]*>([\s\S]*?)<\/div>/i) ||
        detailHtml.match(/<div[^>]+class=["'][^"']*item-content[^"']*["'][^>]*>([\s\S]*?)<\/div>/i) ||
        detailHtml.match(/<div[^>]+class=["'][^"']*description[^"']*["'][^>]*>([\s\S]*?)<\/div>/i);
      const description = descMatch ? decodeHtmlEntities(descMatch[1]) : fallbackDesc;

      // Strategy 1: Halim Server Blocks (hoathinh3d.ee & hhkungfu.ee)
      const halimBlocks = [
        ...detailHtml.matchAll(
          /<div[^>]+class=["'][^"']*halim-server[^"']*["'][^>]*>([\s\S]*?)<\/ul>\s*<\/div>/gi
        )
      ];

      interface ParsedEpEntry {
        postId: number;
        epSlug: string;
        href: string;
        name: string;
        epNum: number;
        sv: string;
      }

      const halimServerGroupsRaw: { srvName: string; episodes: ParsedEpEntry[] }[] = [];

      if (halimBlocks.length > 0) {
        for (const blk of halimBlocks) {
          const blkHtml = blk[1];
          const rawSrvName = blkHtml.match(
            /<span[^>]+class=["'][^"']*halim-server-name[^"']*["'][^>]*>([\s\S]*?)<\/span>/i
          )?.[1];
          const cleanSrvName = decodeHtmlEntities(rawSrvName || 'Vietsub')
            .replace(/^[#:\s]+|[#:\s]+$/g, '')
            .trim();

          const eps: ParsedEpEntry[] = [];
          const seen = new Set<string>();
          const aRegex = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
          let m: RegExpExecArray | null;
          while ((m = aRegex.exec(blkHtml)) !== null) {
            const attrs = m[1];
            const rawText = decodeHtmlEntities(m[2]);
            const href = attrs.match(/href=["']([^"']+)["']/i)?.[1];
            const postIdStr = attrs.match(/data-post-id=["'](\d+)["']/i)?.[1];
            const epAttr = attrs.match(/data-ep=["']([^"']+)["']/i)?.[1];
            const svAttr = attrs.match(/data-sv=["']([^"']+)["']/i)?.[1] || '1';
            if (!href) continue;

            const normalizedEpSlug =
              epAttr || href.split('/').pop()?.replace(/\.html$/i, '') || '';
            const key = `${svAttr}_${normalizedEpSlug || href}`;
            if (seen.has(key)) continue;
            seen.add(key);

            const numMatch = (normalizedEpSlug || rawText).match(/(\d+(?:\.\d+)?)/);
            const epNum = numMatch ? parseFloat(numMatch[1]) : eps.length + 1;

            eps.push({
              postId: Number(postIdStr || fallbackPostId || 0),
              epSlug: normalizedEpSlug || `tap-${epNum}`,
              href,
              name: rawText || `Tập ${epNum}`,
              epNum,
              sv: svAttr
            });
          }

          if (eps.length > 0) {
            eps.sort((a, b) => a.epNum - b.epNum);
            halimServerGroupsRaw.push({
              srvName: cleanSrvName || 'Vietsub',
              episodes: eps
            });
          }
        }
      }

      if (halimServerGroupsRaw.length > 0) {
        // Discover player variants (1080P V1, 1080P V2...) from latest episode watch page
        const sampleHref = halimServerGroupsRaw[0].episodes[halimServerGroupsRaw[0].episodes.length - 1].href;
        let variants: { type: string; label: string }[] = [];
        try {
          const epHtml = await httpGetText(sampleHref, { Referer: targetUrl });
          const svMatches = [
            ...epHtml.matchAll(
              /<span[^>]+data-type=["']([^"']+)["'][^>]*>([\s\S]*?)<\/span>/gi
            )
          ];
          variants = svMatches.map((s) => ({
            type: s[1],
            label: decodeHtmlEntities(s[2])
          }));
        } catch {}

        if (variants.length === 0) {
          variants = [
            { type: 'tiktik', label: '1080P V1' },
            { type: 'pro', label: '1080P V2' }
          ];
        }

        const serverGroups: ServerGroup[] = [];
        halimServerGroupsRaw.forEach((rawSrv) => {
          variants.slice(0, 2).forEach((v) => {
            serverGroups.push({
              serverName: `${sourceName} • ${rawSrv.srvName} (${v.label})`,
              episodes: rawSrv.episodes.map(
                (ep): EpisodeItem => ({
                  name: ep.name,
                  slug: ep.epSlug,
                  linkM3u8: '',
                  linkEmbed: '',
                  wpBaseUrl: baseUrl,
                  wpPostId: ep.postId,
                  wpEpSlug: ep.epSlug,
                  wpServerType: v.type,
                  wpServerSv: ep.sv,
                  wpPageUrl: ep.href
                })
              )
            });
          });
        });

        const maxEp = serverGroups.reduce((m, g) => Math.max(m, g.episodes.length), 0);
        const result = { serverGroups, description, latestEp: maxEp, totalEp: maxEp };
        setWpCache(cacheKey, result);
        return result;
      }

      // Strategy 2: YanHH3D .ep-range / .ep-item structure
      const parsedEps: ParsedEpEntry[] = [];
      const seenEp = new Set<string>();
      const epRegex = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
      let m: RegExpExecArray | null;
      while ((m = epRegex.exec(detailHtml)) !== null) {
        const attrs = m[1];
        const rawText = decodeHtmlEntities(m[2]);
        const href = attrs.match(/href=["']([^"']+)["']/i)?.[1];
        const postIdStr = attrs.match(/data-post-id=["'](\d+)["']/i)?.[1];
        const epSlug = attrs.match(/data-ep=["']([^"']+)["']/i)?.[1];
        const svAttr = attrs.match(/data-sv=["']([^"']+)["']/i)?.[1] || '1';

        const isEpisodeLink =
          Boolean(epSlug) ||
          Boolean(href && (href.includes('/tap-') || href.includes('/xem-phim-') || href.includes('/watch-')));
        if (!href || !isEpisodeLink) continue;

        const normalizedEpSlug =
          epSlug || href.split('/').pop()?.replace(/\.html$/i, '') || `tap-${parsedEps.length + 1}`;
        if (seenEp.has(normalizedEpSlug)) continue;
        seenEp.add(normalizedEpSlug);

        const numMatch = (normalizedEpSlug || rawText).match(/(\d+(?:\.\d+)?)/);
        const epNum = numMatch ? parseFloat(numMatch[1]) : parsedEps.length + 1;

        parsedEps.push({
          postId: Number(postIdStr || fallbackPostId || 0),
          epSlug: normalizedEpSlug,
          href,
          name: rawText && !rawText.toLowerCase().includes('xem phim') ? rawText : `Tập ${epNum}`,
          epNum,
          sv: svAttr
        });
      }

      parsedEps.sort((a, b) => a.epNum - b.epNum);
      if (parsedEps.length === 0) {
        return { serverGroups: [], description };
      }

      const latestEpItem = parsedEps[parsedEps.length - 1];
      let servers: { type: string; label: string }[] = [];
      try {
        const epHtml = await httpGetText(latestEpItem.href, { Referer: targetUrl });
        const svMatches = [
          ...epHtml.matchAll(
            /<span[^>]+class=["'][^"']*btn3dsv[^"']*["'][^>]*data-type=["']([^"']+)["'][^>]*>([\s\S]*?)<\/span>/gi
          )
        ];
        servers = svMatches.map((s) => ({
          type: s[1],
          label: decodeHtmlEntities(s[2])
        }));
      } catch {}

      if (servers.length === 0) {
        servers = [
          { type: 'tiktik', label: 'VIETSUB 1080 V1' },
          { type: 'pro', label: 'VIETSUB 1080 V2' },
          { type: 'vip4k', label: 'VIETSUB 4K V1' },
          { type: 'tiktm', label: 'Thuyết minh 1080 V1' }
        ];
      }

      const serverGroups: ServerGroup[] = servers.map((sv) => ({
        serverName: `${sourceName} • ${sv.label}`,
        episodes: parsedEps.map(
          (ep): EpisodeItem => ({
            name: ep.name,
            slug: ep.epSlug,
            linkM3u8: '',
            linkEmbed: '',
            yanPostId: ep.postId,
            yanEpSlug: ep.epSlug,
            yanServerType: sv.type,
            yanPageUrl: ep.href,
            wpBaseUrl: baseUrl,
            wpPostId: ep.postId,
            wpEpSlug: ep.epSlug,
            wpServerType: sv.type,
            wpServerSv: ep.sv,
            wpPageUrl: ep.href
          })
        )
      }));

      const highestEp = parsedEps.reduce(
        (max, ep) => Math.max(max, Number.isFinite(ep.epNum) ? Math.floor(ep.epNum) : 0),
        parsedEps.length
      );
      const tickMatch = detailHtml.match(
        /<div[^>]+class=["'][^"']*tick-rate[^"']*["'][^>]*>([\s\S]*?)<\/div>/i
      )?.[1];
      const tickParsed = tickMatch ? parseTickRateBadge(tickMatch) : null;

      const result = {
        serverGroups,
        description,
        latestEp: Math.max(highestEp, tickParsed?.latestEp || 0),
        totalEp: Math.max(highestEp, tickParsed?.totalEp || 0)
      };
      setWpCache(cacheKey, result);
      return result;
    } catch (err) {
      console.warn(`WP detail parse error (${sourceName}):`, err);
      return { serverGroups: [] };
    }
  }

  /**
   * Giải mã link phát thực tế (Embed / Direct stream) qua player.php cho YanHH3D, HH3D, HHKungfu
   */
  static async resolveStream(
    baseUrl: string,
    postId: number,
    epSlug: string,
    serverType: string,
    sv = '1',
    refererUrl?: string
  ): Promise<{ linkEmbed: string; linkM3u8: string }> {
    const cleanBase = baseUrl || YAN_BASE;
    const cacheKey = `wp_stream_${cleanBase}_${postId}_${epSlug}_${serverType}_${sv}`;
    const cached = getWpCache<{ linkEmbed: string; linkM3u8: string }>(cacheKey);
    if (cached && (cached.linkEmbed || cached.linkM3u8)) return cached;

    const url = `${cleanBase}/player/player.php?action=dox_ajax_player&post_id=${encodeURIComponent(
      String(postId)
    )}&chapter_st=${encodeURIComponent(epSlug)}&type=${encodeURIComponent(
      serverType
    )}&sv=${encodeURIComponent(sv || '1')}`;

    const html = await httpGetText(url, {
      Referer: refererUrl || `${cleanBase}/`,
      'X-Requested-With': 'XMLHttpRequest'
    });

    let iframeSrc = html.match(/<iframe[^>]+src=["']([^"']+)["']/i)?.[1] || '';
    if (iframeSrc.startsWith('//')) {
      iframeSrc = `https:${iframeSrc}`;
    } else if (iframeSrc.startsWith('/')) {
      iframeSrc = `${cleanBase}${iframeSrc}`;
    }
    const directM3u8 = html.match(/(https?:\/\/[^"'\s]+\.m3u8[^"'\s]*)/i)?.[1] || '';

    const out = { linkEmbed: iframeSrc, linkM3u8: directM3u8 };
    if (out.linkEmbed || out.linkM3u8) {
      setWpCache(cacheKey, out);
    }
    return out;
  }
}

/**
 * Backward-compatible wrapper cho YanHH3DService
 */
export class YanHH3DService {
  static fetchCategoriesAndTaxonomies(): Promise<YanTaxonomyResult> {
    return WpDonghuaService.fetchTaxonomies(YAN_BASE);
  }

  static fetchYanMovies(options: {
    page?: number;
    perPage?: number;
    categoryId?: number;
    statusId?: number;
    showtimeId?: number;
    search?: string;
  } = {}) {
    return WpDonghuaService.fetchMovies(YAN_BASE, 'YanHH3D', options);
  }

  static fetchYanMovieDetail(movie: DonghuaMovie) {
    const targetUrl = movie.yanUrl || (movie.slug ? `${YAN_BASE}/tu-tien/${movie.slug}` : '');
    return WpDonghuaService.fetchMovieDetail(
      YAN_BASE,
      'YanHH3D',
      targetUrl,
      movie.yanPostId || 0,
      movie.description || ''
    );
  }

  static resolveYanEpisodeStream(
    postId: number,
    epSlug: string,
    serverType: string,
    refererUrl?: string,
    baseUrl = YAN_BASE,
    sv = '1'
  ) {
    return WpDonghuaService.resolveStream(baseUrl, postId, epSlug, serverType, sv, refererUrl);
  }
}
