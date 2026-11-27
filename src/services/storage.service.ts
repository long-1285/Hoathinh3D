import {
  AppSettings,
  DonghuaMovie,
  DonghuaSource,
  FavoriteEpisodeMeta,
  OfflineEpisodeRecord,
  ThemePreset,
  UserProfile,
  WatchProgress
} from '../types';
import { DEFAULT_BUILT_IN_SOURCES, INITIAL_DONGHUA_CATALOG } from '../constants/initialData';

export const STORAGE_KEYS = {
  HISTORY: 'hh3d_watch_history_v2',
  FAVORITES: 'hh3d_favorites_v2',
  FAVORITES_EP_META: 'hh3d_favorites_ep_meta_v1',
  OFFLINE_EPISODES: 'hh3d_offline_episodes_v1',
  SOURCES: 'hh3d_sources_list_v2',
  CUSTOM_MOVIES: 'hh3d_custom_movies_v2',
  SETTINGS: 'hh3d_app_settings_v2',
  GOOGLE_USER: 'hh3d_google_user_v2',
  CLOUD_BACKUP: 'hh3d_google_cloud_snapshot_v2',
  AUDIO_DEFAULT_TM_MIGRATED: 'hh3d_audio_default_tm_v1',
  SOURCES_V4_TWO_ONLY: 'hh3d_sources_v4_yanhh3d_nguonc_only'
};

const VALID_THEMES: ThemePreset[] = ['pearl', 'aqua', 'sakura', 'dark'];

export const DEFAULT_SETTINGS: AppSettings = {
  deviceMode: 'auto',
  theme: 'pearl',
  glassBlur: 24,
  ambientOrbs: true,
  preferredSource: 'YanHH3D',
  cloudAutoSync: true,
  autoNext: true,
  autoSkipIntro: false,
  seekStep: 10,
  bilingualSub: false,
  defaultAudio: 'Thuyết Minh',
  defaultQuality: '4K 60FPS',
  aspectRatio: '16:9',
  volume: 1,
  playbackSpeed: 1,
  extensions: {
    adblock: true,
    aiUpscale: false,
    bilingualSub: false,
    ambientGlow: true
  }
};

export class StorageService {
  // Watch History
  static getHistory(): Record<string, WatchProgress> {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.HISTORY);
      return data ? JSON.parse(data) : {};
    } catch {
      return {};
    }
  }

  static saveProgress(progress: WatchProgress): void {
    try {
      const history = this.getHistory();
      history[progress.donghuaId] = progress;
      localStorage.setItem(STORAGE_KEYS.HISTORY, JSON.stringify(history));
    } catch (e) {
      console.error('Error saving progress:', e);
    }
  }

  static clearHistory(): void {
    localStorage.removeItem(STORAGE_KEYS.HISTORY);
  }

  // Favorites
  static getFavorites(): string[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.FAVORITES);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  static toggleFavorite(id: string): boolean {
    const favs = this.getFavorites();
    const idx = favs.indexOf(id);
    let isFav = false;
    if (idx >= 0) {
      favs.splice(idx, 1);
    } else {
      favs.unshift(id);
      isFav = true;
    }
    localStorage.setItem(STORAGE_KEYS.FAVORITES, JSON.stringify(favs));
    return isFav;
  }

  // Favorite New-Episode Meta
  static getFavoriteMetaMap(): Record<string, FavoriteEpisodeMeta> {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.FAVORITES_EP_META);
      return data ? JSON.parse(data) : {};
    } catch {
      return {};
    }
  }

  static saveFavoriteMetaMap(map: Record<string, FavoriteEpisodeMeta>): void {
    try {
      localStorage.setItem(STORAGE_KEYS.FAVORITES_EP_META, JSON.stringify(map));
    } catch {}
  }

  static markFavoriteEpisodeSeen(donghuaId: string, latestEp: number): Record<string, FavoriteEpisodeMeta> {
    const map = this.getFavoriteMetaMap();
    const safeEp = Math.max(1, latestEp || 1);
    map[donghuaId] = {
      donghuaId,
      lastSeenEp: safeEp,
      latestKnownEp: Math.max(safeEp, map[donghuaId]?.latestKnownEp || 1),
      newEpCount: 0,
      lastCheckedAt: Date.now()
    };
    this.saveFavoriteMetaMap(map);
    return map;
  }

  // Offline HLS Episodes Index
  static getOfflineEpisodes(): Record<string, OfflineEpisodeRecord> {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.OFFLINE_EPISODES);
      return data ? JSON.parse(data) : {};
    } catch {
      return {};
    }
  }

  static saveOfflineEpisode(record: OfflineEpisodeRecord): void {
    try {
      const map = this.getOfflineEpisodes();
      map[record.key] = record;
      localStorage.setItem(STORAGE_KEYS.OFFLINE_EPISODES, JSON.stringify(map));
    } catch {}
  }

  static deleteOfflineEpisode(key: string): void {
    try {
      const map = this.getOfflineEpisodes();
      delete map[key];
      localStorage.setItem(STORAGE_KEYS.OFFLINE_EPISODES, JSON.stringify(map));
    } catch {}
  }

  // Sources (chỉ giữ 2 nguồn chính thức YanHH3D và NguonC)
  static getSources(): DonghuaSource[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SOURCES);
      if (data) {
        const parsed = JSON.parse(data) as DonghuaSource[];
        if (Array.isArray(parsed) && parsed.length > 0) {
          if (!localStorage.getItem(STORAGE_KEYS.SOURCES_V4_TWO_ONLY)) {
            this.saveSources(DEFAULT_BUILT_IN_SOURCES);
            localStorage.setItem(STORAGE_KEYS.SOURCES_V4_TWO_ONLY, '1');
            return DEFAULT_BUILT_IN_SOURCES;
          }
          return parsed;
        }
      }
    } catch {}
    localStorage.setItem(STORAGE_KEYS.SOURCES_V4_TWO_ONLY, '1');
    this.saveSources(DEFAULT_BUILT_IN_SOURCES);
    return DEFAULT_BUILT_IN_SOURCES;
  }

  static saveSources(sources: DonghuaSource[]): void {
    localStorage.setItem(STORAGE_KEYS.SOURCES, JSON.stringify(sources));
  }

  static addCustomSource(source: DonghuaSource): void {
    const sources = this.getSources();
    sources.push(source);
    this.saveSources(sources);
  }

  static toggleSourceEnabled(id: string): { ok: boolean; sources: DonghuaSource[]; reason?: string } {
    const sources = this.getSources();
    const target = sources.find((s) => s.id === id);
    if (!target) return { ok: false, sources, reason: 'Không tìm thấy nguồn' };

    const enabledCount = sources.filter((s) => s.enabled).length;
    if (target.enabled && enabledCount <= 1) {
      return { ok: false, sources, reason: 'Phải giữ ít nhất 1 nguồn phim hoạt động!' };
    }

    const updated = sources.map((s) => (s.id === id ? { ...s, enabled: !s.enabled } : s));
    this.saveSources(updated);
    return { ok: true, sources: updated };
  }

  static deleteSource(id: string): void {
    let sources = this.getSources();
    sources = sources.filter((s) => s.id !== id);
    this.saveSources(sources);
  }

  // Settings
  static getSettings(): AppSettings {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SETTINGS);
      if (!data) {
        localStorage.setItem(STORAGE_KEYS.AUDIO_DEFAULT_TM_MIGRATED, '1');
        return DEFAULT_SETTINGS;
      }
      const parsed = JSON.parse(data);
      const validTheme: ThemePreset = VALID_THEMES.includes(parsed.theme) ? parsed.theme : 'pearl';
      let defaultAudio: 'Vietsub' | 'Thuyết Minh' =
        parsed.defaultAudio === 'Vietsub' || parsed.defaultAudio === 'Thuyết Minh'
          ? parsed.defaultAudio
          : 'Thuyết Minh';

      let preferredSource = parsed.preferredSource || 'YanHH3D';
      if (preferredSource !== 'YanHH3D' && preferredSource !== 'NguonC') {
        preferredSource = 'YanHH3D';
      }

      if (!localStorage.getItem(STORAGE_KEYS.AUDIO_DEFAULT_TM_MIGRATED)) {
        defaultAudio = 'Thuyết Minh';
        localStorage.setItem(STORAGE_KEYS.AUDIO_DEFAULT_TM_MIGRATED, '1');
        localStorage.setItem(
          STORAGE_KEYS.SETTINGS,
          JSON.stringify({
            ...DEFAULT_SETTINGS,
            ...parsed,
            preferredSource,
            theme: validTheme,
            defaultAudio
          })
        );
      }

      return {
        ...DEFAULT_SETTINGS,
        ...parsed,
        preferredSource,
        defaultAudio,
        theme: validTheme,
        extensions: {
          ...DEFAULT_SETTINGS.extensions,
          ...(parsed.extensions || {})
        }
      };
    } catch {
      return DEFAULT_SETTINGS;
    }
  }

  static saveSettings(settings: Partial<AppSettings>): AppSettings {
    const current = this.getSettings();
    const updated: AppSettings = {
      ...current,
      ...settings,
      extensions: settings.extensions
        ? { ...current.extensions, ...settings.extensions }
        : current.extensions
    };
    localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(updated));
    return updated;
  }

  // Google / Cloud User
  static getUser(): UserProfile | null {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.GOOGLE_USER);
      return data ? JSON.parse(data) : null;
    } catch {
      return null;
    }
  }

  static saveUser(user: UserProfile | null): void {
    if (user) {
      localStorage.setItem(STORAGE_KEYS.GOOGLE_USER, JSON.stringify(user));
    } else {
      localStorage.removeItem(STORAGE_KEYS.GOOGLE_USER);
    }
  }

  // Custom movies
  static getCustomMovies(): DonghuaMovie[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.CUSTOM_MOVIES);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  static addCustomMovie(movie: DonghuaMovie): void {
    const list = this.getCustomMovies();
    list.unshift(movie);
    localStorage.setItem(STORAGE_KEYS.CUSTOM_MOVIES, JSON.stringify(list));
  }

  static getAllMovies(): DonghuaMovie[] {
    return [...this.getCustomMovies(), ...INITIAL_DONGHUA_CATALOG];
  }

  // Backup & Restore
  static exportFullBackup(): string {
    const payload = {
      version: '3.5.0-universal',
      timestamp: Date.now(),
      history: this.getHistory(),
      favorites: this.getFavorites(),
      sources: this.getSources(),
      customMovies: this.getCustomMovies(),
      settings: this.getSettings(),
      user: this.getUser()
    };
    return JSON.stringify(payload, null, 2);
  }

  static importFullBackup(jsonString: string): boolean {
    try {
      const payload = JSON.parse(jsonString);
      if (payload.history) localStorage.setItem(STORAGE_KEYS.HISTORY, JSON.stringify(payload.history));
      if (payload.favorites) localStorage.setItem(STORAGE_KEYS.FAVORITES, JSON.stringify(payload.favorites));
      if (Array.isArray(payload.sources)) localStorage.setItem(STORAGE_KEYS.SOURCES, JSON.stringify(payload.sources));
      if (Array.isArray(payload.sourcesList)) localStorage.setItem(STORAGE_KEYS.SOURCES, JSON.stringify(payload.sourcesList));
      if (Array.isArray(payload.customMovies)) localStorage.setItem(STORAGE_KEYS.CUSTOM_MOVIES, JSON.stringify(payload.customMovies));
      if (payload.settings) localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(payload.settings));
      if (payload.user) localStorage.setItem(STORAGE_KEYS.GOOGLE_USER, JSON.stringify(payload.user));
      return true;
    } catch (e) {
      console.error('Import failed:', e);
      return false;
    }
  }

  // Cloud Snapshot
  static saveCloudSnapshot(): number {
    const timestamp = Date.now();
    const json = this.exportFullBackup();
    localStorage.setItem(STORAGE_KEYS.CLOUD_BACKUP, json);
    const user = this.getUser();
    if (user) {
      this.saveUser({ ...user, lastSync: timestamp });
    }
    return timestamp;
  }

  static getCloudSnapshotMeta(): { timestamp: number } | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEYS.CLOUD_BACKUP);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return parsed?.timestamp ? { timestamp: parsed.timestamp } : null;
    } catch {
      return null;
    }
  }

  static restoreCloudSnapshot(): boolean {
    try {
      const raw = localStorage.getItem(STORAGE_KEYS.CLOUD_BACKUP);
      if (!raw) return false;
      return this.importFullBackup(raw);
    } catch {
      return false;
    }
  }
}
