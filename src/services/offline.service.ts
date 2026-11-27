import Hls, { HlsConfig, Loader, LoaderCallbacks, LoaderConfiguration, LoaderContext } from 'hls.js';
import { DonghuaMovie, FavoriteEpisodeMeta, OfflineEpisodeRecord } from '../types';
import { ApiService } from './api.service';
import { StorageService } from './storage.service';

export const OFFLINE_CACHE_NAME = 'hh3d-offline-hls-v1';
const MAX_SEGMENTS_PER_EPISODE = 240;

function resolvePlaylistUrl(baseUrl: string, entry: string): string {
  try {
    return new URL(entry, baseUrl).toString();
  } catch {
    return entry;
  }
}

function parseM3u8Lines(text: string): { isMaster: boolean; variants: string[]; segments: string[] } {
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
  const isMaster = lines.some((l) => l.startsWith('#EXT-X-STREAM-INF'));
  const variants: string[] = [];
  const segments: string[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('#')) continue;
    if (isMaster) {
      variants.push(line);
    } else {
      segments.push(line);
    }
  }
  return { isMaster, variants, segments };
}

/**
 * Custom Hls.js Loader ưu tiên đọc Playlist & TS Segments từ CacheStorage offline trước khi gọi mạng.
 */
export function createCacheAwareHlsLoader(): typeof Hls.DefaultConfig.loader {
  const DefaultLoader = Hls.DefaultConfig.loader;

  return class OfflineAwareLoader implements Loader<LoaderContext> {
    private inner: Loader<LoaderContext>;
    private aborted = false;

    constructor(config: HlsConfig) {
      this.inner = new DefaultLoader(config);
    }

    get stats() {
      return this.inner.stats;
    }

    get context() {
      return this.inner.context;
    }

    destroy() {
      this.inner.destroy();
    }

    abort() {
      this.aborted = true;
      this.inner.abort();
    }

    getCacheAge() {
      return this.inner.getCacheAge ? this.inner.getCacheAge() : null;
    }

    getResponseHeader(name: string) {
      return this.inner.getResponseHeader ? this.inner.getResponseHeader(name) : null;
    }

    load(
      context: LoaderContext,
      config: LoaderConfiguration,
      callbacks: LoaderCallbacks<LoaderContext>
    ) {
      this.aborted = false;
      if (typeof caches === 'undefined') {
        this.inner.load(context, config, callbacks);
        return;
      }

      caches
        .open(OFFLINE_CACHE_NAME)
        .then((cache) => cache.match(context.url))
        .then(async (cachedRes) => {
          if (this.aborted) return;
          if (!cachedRes) {
            this.inner.load(context, config, callbacks);
            return;
          }

          const isBinary = context.responseType === 'arraybuffer';
          const data = isBinary ? await cachedRes.arrayBuffer() : await cachedRes.text();
          if (this.aborted) return;

          const now = performance.now();
          const stats = this.inner.stats;
          stats.loading.start = now;
          stats.loading.first = now;
          stats.loading.end = now;
          const byteLen =
            data instanceof ArrayBuffer ? data.byteLength : new Blob([String(data)]).size;
          stats.loaded = byteLen;
          stats.total = byteLen;

          callbacks.onSuccess(
            {
              url: context.url,
              data,
              code: 200
            },
            stats,
            context,
            null
          );
        })
        .catch(() => {
          if (!this.aborted) {
            this.inner.load(context, config, callbacks);
          }
        });
    }
  } as unknown as typeof Hls.DefaultConfig.loader;
}

export class OfflineCacheService {
  static buildEpisodeKey(donghuaId: string, episodeNum: number): string {
    return `${donghuaId}__ep_${episodeNum}`;
  }

  static isSupported(): boolean {
    return typeof window !== 'undefined' && typeof caches !== 'undefined';
  }

  static async cacheHlsEpisode(
    params: {
      donghuaId: string;
      title: string;
      poster: string;
      episodeNum: number;
      episodeName: string;
      serverName: string;
      streamUrl: string;
    },
    onProgress?: (pct: number, detail: string) => void
  ): Promise<OfflineEpisodeRecord> {
    if (!this.isSupported()) {
      throw new Error('Trình duyệt/WebView hiện tại không hỗ trợ CacheStorage offline.');
    }
    if (!params.streamUrl || !params.streamUrl.includes('.m3u8')) {
      throw new Error('Chỉ hỗ trợ lưu offline các tập dùng luồng HLS (.m3u8).');
    }

    const cache = await caches.open(OFFLINE_CACHE_NAME);
    const cachedUrls: string[] = [];
    let totalBytes = 0;

    onProgress?.(5, 'Đang tải danh sách phân đoạn HLS...');
    const masterRes = await fetch(params.streamUrl);
    if (!masterRes.ok) throw new Error(`Không tải được playlist (HTTP ${masterRes.status})`);
    const masterClone = masterRes.clone();
    const masterText = await masterRes.text();
    await cache.put(params.streamUrl, masterClone);
    cachedUrls.push(params.streamUrl);
    totalBytes += masterText.length;

    let mediaPlaylistUrl = params.streamUrl;
    let parsed = parseM3u8Lines(masterText);

    if (parsed.isMaster && parsed.variants.length > 0) {
      mediaPlaylistUrl = resolvePlaylistUrl(
        params.streamUrl,
        parsed.variants[parsed.variants.length - 1]
      );
      const subRes = await fetch(mediaPlaylistUrl);
      if (!subRes.ok) throw new Error(`Không tải được luồng con (HTTP ${subRes.status})`);
      const subClone = subRes.clone();
      const subText = await subRes.text();
      await cache.put(mediaPlaylistUrl, subClone);
      cachedUrls.push(mediaPlaylistUrl);
      totalBytes += subText.length;
      parsed = parseM3u8Lines(subText);
    }

    const segList = parsed.segments.slice(0, MAX_SEGMENTS_PER_EPISODE);
    if (segList.length === 0) {
      throw new Error('Playlist HLS không chứa phân đoạn video hợp lệ.');
    }

    const CONCURRENCY = 4;
    let completed = 0;

    for (let i = 0; i < segList.length; i += CONCURRENCY) {
      const batch = segList.slice(i, i + CONCURRENCY);
      await Promise.all(
        batch.map(async (segRel) => {
          const segUrl = resolvePlaylistUrl(mediaPlaylistUrl, segRel);
          const segRes = await fetch(segUrl);
          if (!segRes.ok) return;
          const buf = await segRes.clone().arrayBuffer();
          totalBytes += buf.byteLength;
          await cache.put(segUrl, segRes);
          cachedUrls.push(segUrl);
        })
      );
      completed = Math.min(segList.length, i + CONCURRENCY);
      const pct = Math.min(99, Math.round(10 + (completed / segList.length) * 89));
      onProgress?.(pct, `Đã tải ${completed}/${segList.length} phân đoạn`);
    }

    const record: OfflineEpisodeRecord = {
      key: this.buildEpisodeKey(params.donghuaId, params.episodeNum),
      donghuaId: params.donghuaId,
      title: params.title,
      poster: params.poster,
      episodeNum: params.episodeNum,
      episodeName: params.episodeName,
      serverName: params.serverName,
      streamUrl: params.streamUrl,
      cachedUrls,
      segmentCount: segList.length,
      approxBytes: totalBytes,
      savedAt: Date.now()
    };

    StorageService.saveOfflineEpisode(record);
    onProgress?.(100, 'Hoàn tất lưu xem offline!');
    return record;
  }

  static async removeOfflineEpisode(key: string): Promise<void> {
    const records = StorageService.getOfflineEpisodes();
    const target = records[key];
    if (target && this.isSupported()) {
      try {
        const cache = await caches.open(OFFLINE_CACHE_NAME);
        await Promise.all(target.cachedUrls.map((u) => cache.delete(u)));
      } catch {}
    }
    StorageService.deleteOfflineEpisode(key);
  }
}

export class FavoritesMonitorService {
  /**
   * Kiểm tra tập mới cho các phim trong Tủ phim yêu thích dựa trên feed hiện tại + truy vấn chi tiết
   */
  static async checkNewEpisodes(
    favoriteIds: string[],
    knownMovies: DonghuaMovie[],
    deepCheckLimit = 5
  ): Promise<{
    metaMap: Record<string, FavoriteEpisodeMeta>;
    updatedMovies: DonghuaMovie[];
    newlyUpdatedTitles: string[];
  }> {
    const prevMeta = StorageService.getFavoriteMetaMap();
    const nextMeta: Record<string, FavoriteEpisodeMeta> = { ...prevMeta };
    const newlyUpdatedTitles: string[] = [];
    const updatedMovies: DonghuaMovie[] = [];

    const favMovies = knownMovies.filter((m) => favoriteIds.includes(m.id));
    const now = Date.now();

    // Refresh first N favorites deeply so NguonC/YanHH3D latestEp is up to date
    const targetsForDeepCheck = favMovies.slice(0, deepCheckLimit);
    const detailResults = await Promise.allSettled(
      targetsForDeepCheck.map((m) => ApiService.fetchMovieDetail(m.slug || '', m))
    );

    const refreshedLatestById = new Map<string, number>();
    targetsForDeepCheck.forEach((m, idx) => {
      const res = detailResults[idx];
      if (res.status === 'fulfilled' && res.value.latestEp) {
        refreshedLatestById.set(m.id, res.value.latestEp);
        updatedMovies.push({
          ...m,
          latestEp: Math.max(m.latestEp, res.value.latestEp),
          totalEp: Math.max(m.totalEp, res.value.totalEp || res.value.latestEp),
          serverGroups:
            res.value.serverGroups.length > 0 ? res.value.serverGroups : m.serverGroups
        });
      }
    });

    for (const m of favMovies) {
      const latestKnownEp = Math.max(m.latestEp || 1, refreshedLatestById.get(m.id) || 0);
      const existing = prevMeta[m.id];

      if (!existing) {
        nextMeta[m.id] = {
          donghuaId: m.id,
          lastSeenEp: latestKnownEp,
          latestKnownEp,
          newEpCount: 0,
          lastCheckedAt: now
        };
      } else {
        const diff = Math.max(0, latestKnownEp - existing.lastSeenEp);
        if (diff > 0 && latestKnownEp > existing.latestKnownEp) {
          newlyUpdatedTitles.push(`${m.title} (Tập ${latestKnownEp})`);
        }
        nextMeta[m.id] = {
          donghuaId: m.id,
          lastSeenEp: existing.lastSeenEp,
          latestKnownEp,
          newEpCount: diff,
          lastCheckedAt: now
        };
      }
    }

    StorageService.saveFavoriteMetaMap(nextMeta);
    return { metaMap: nextMeta, updatedMovies, newlyUpdatedTitles };
  }
}
