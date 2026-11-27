export interface DonghuaSource {
  id: string;
  name: string;
  url: string;
  hostname?: string;
  type: 'Smart Scraper' | 'M3U8 Stream' | 'Iframe Embed' | 'JSON / CMS API' | string;
  ping: string;
  quality: string;
  desc: string;
  enabled: boolean;
  isCustom: boolean;
  addedAt?: number;
}

export interface EpisodeItem {
  name: string;
  slug: string;
  linkM3u8: string;
  linkEmbed?: string;
  yanPostId?: number;
  yanEpSlug?: string;
  yanServerType?: string;
  yanPageUrl?: string;
  wpBaseUrl?: string;
  wpPostId?: number;
  wpEpSlug?: string;
  wpServerType?: string;
  wpServerSv?: string;
  wpPageUrl?: string;
}

export interface WpSourceRef {
  baseUrl: string;
  postId: number;
  url: string;
  sourceName: string;
}

export interface ServerGroup {
  serverName: string;
  episodes: EpisodeItem[];
}

export interface DonghuaMovie {
  id: string;
  title: string;
  originalTitle: string;
  slug?: string;
  yanPostId?: number;
  yanUrl?: string;
  wpSources?: WpSourceRef[];
  statusLabel?: string;
  studio: string;
  latestEp: number;
  totalEp: number;
  rating: number;
  views: string;
  viewsNum: number;
  quality: string;
  audioModes: ('Vietsub' | 'Thuyết Minh')[];
  sources: string[];
  genres: string[];
  scheduleDay: 'Thứ 2' | 'Thứ 3' | 'Thứ 4' | 'Thứ 5' | 'Thứ 6' | 'Thứ 7' | 'Chủ Nhật';
  scheduleTime: string;
  poster: string;
  thumb?: string;
  streamIndex: number;
  episodes?: { [ep: number]: string };
  serverGroups?: ServerGroup[];
  description?: string;
  customWebUrl?: string;
}

export interface WatchProgress {
  donghuaId: string;
  title: string;
  poster: string;
  episode: number;
  currentTime: number;
  duration: number;
  progressPct: number;
  server: string;
  audio: string;
  updatedAt: number;
}

export interface GenreItem {
  id: string;
  label: string;
  icon: string;
  count?: number;
  sourceSlug?: string;
  yanCategoryId?: number;
  yanStatusId?: number;
  yanShowtimeId?: number;
  wpCategoryId?: number;
  wpStatusId?: number;
  wpShowtimeId?: number;
}

export type DeviceMode = 'auto' | 'mobile' | 'tablet' | 'tv';
export type ThemePreset = 'pearl' | 'aqua' | 'sakura' | 'dark';
export type SettingsSubtab =
  | 'account'
  | 'overview'
  | 'player'
  | 'sources'
  | 'appearance'
  | 'backup'
  | 'extensions';

export interface ExtensionSettings {
  adblock: boolean;
  aiUpscale: boolean;
  bilingualSub: boolean;
  ambientGlow: boolean;
}

export interface AppSettings {
  deviceMode: DeviceMode;
  theme: ThemePreset;
  glassBlur: number;
  ambientOrbs: boolean;
  preferredSource: string;
  cloudAutoSync: boolean;
  autoNext: boolean;
  autoSkipIntro: boolean;
  seekStep: number;
  bilingualSub: boolean;
  defaultAudio: 'Vietsub' | 'Thuyết Minh';
  defaultQuality: 'Auto' | '4K 60FPS' | '2K HDR' | '1080p Pro' | '1080p' | '4K';
  aspectRatio: '16:9' | '4:3' | 'cover' | 'stretch';
  volume: number;
  playbackSpeed: number;
  extensions: ExtensionSettings;
}

export interface UserProfile {
  name: string;
  email: string;
  avatar: string;
  lastSync: number | null;
}

export interface FavoriteEpisodeMeta {
  donghuaId: string;
  lastSeenEp: number;
  latestKnownEp: number;
  newEpCount: number;
  lastCheckedAt: number;
}

export interface OfflineEpisodeRecord {
  key: string;
  donghuaId: string;
  title: string;
  poster: string;
  episodeNum: number;
  episodeName: string;
  serverName: string;
  streamUrl: string;
  cachedUrls: string[];
  segmentCount: number;
  approxBytes: number;
  savedAt: number;
}
