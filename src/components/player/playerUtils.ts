import type { HlsConfig } from 'hls.js';
import { AppSettings, DeviceMode, EpisodeItem } from '../../types';
import { createCacheAwareHlsLoader } from '../../services/offline.service';

export const EP_PAGE_SIZE = 50;

export type AspectMode = '16:9' | 'cover' | 'stretch' | '4:3';
export type PlayerDeviceProfile = 'mobile' | 'tablet' | 'tv' | 'pc';

export interface HlsQualityLevel {
  index: number; // -1 = Auto
  label: string;
  height: number;
  bitrate: number;
}

export const ASPECT_LABELS: Record<AspectMode, string> = {
  '16:9': 'Tỷ lệ: Vừa',
  cover: 'Tỷ lệ: Lấp đầy',
  stretch: 'Tỷ lệ: Giãn',
  '4:3': 'Tỷ lệ: 4:3'
};

export function resolvePlayerDeviceProfile(
  resolvedDevice: Exclude<DeviceMode, 'auto'> | undefined,
  explicitMode: DeviceMode
): PlayerDeviceProfile {
  if (explicitMode === 'tv') return 'tv';
  if (explicitMode === 'mobile') return 'mobile';
  if (explicitMode === 'tablet') return 'tablet';

  const hasCoarsePointer =
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(pointer: coarse)').matches;
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  const isTvUa = /TV|AFT|BRAVIA|SmartTV|GoogleTV|Android TV|MIBOX|SHIELD/i.test(ua);
  const isMobileUa = /Android|iPhone|iPod|Mobile/i.test(ua) && !isTvUa;

  if (isTvUa) return 'tv';
  if (resolvedDevice === 'mobile') return 'mobile';
  if (resolvedDevice === 'tablet') return 'tablet';
  // On wide screens: desktop/emulator (fine pointer, non-mobile UA) maps to pc unless TV mode is forced
  if (!hasCoarsePointer && !isMobileUa && typeof window !== 'undefined' && window.innerWidth >= 1024) {
    return 'pc';
  }
  return resolvedDevice === 'tv' ? 'tv' : isMobileUa ? 'mobile' : 'pc';
}

export function getDeviceHlsConfig(
  profile: PlayerDeviceProfile,
  defaultQuality: AppSettings['defaultQuality']
): Partial<HlsConfig> {
  const isLowMemory = profile === 'mobile' || profile === 'tv';
  return {
    enableWorker: true,
    lowLatencyMode: false,
    startFragPrefetch: true,
    capLevelToPlayerSize: defaultQuality === 'Auto',
    abrEwmaDefaultEstimate: isLowMemory ? 2200000 : 4500000,
    maxBufferLength: isLowMemory ? 16 : 24,
    maxMaxBufferLength: isLowMemory ? 32 : 48,
    maxBufferSize: (isLowMemory ? 28 : 50) * 1000 * 1000,
    backBufferLength: isLowMemory ? 18 : 30,
    manifestLoadingTimeOut: 8000,
    levelLoadingTimeOut: 8000,
    fragLoadingTimeOut: 10000,
    loader: createCacheAwareHlsLoader()
  };
}

export function extractEpisodeNumber(ep: EpisodeItem | undefined, fallbackIdx: number): number {
  if (!ep) return fallbackIdx + 1;
  const m = (ep.slug || ep.name || '').match(/(\d+(?:\.\d+)?)/);
  if (m) {
    const n = parseFloat(m[1]);
    if (!Number.isNaN(n) && n > 0) return n;
  }
  return fallbackIdx + 1;
}

export function findEpisodeIdxByNumber(episodes: EpisodeItem[], targetEpNum: number): number {
  if (!episodes || episodes.length === 0) return Math.max(0, targetEpNum - 1);
  const exactIdx = episodes.findIndex((ep, i) => extractEpisodeNumber(ep, i) === targetEpNum);
  if (exactIdx >= 0) return exactIdx;
  return Math.max(0, Math.min(targetEpNum - 1, episodes.length - 1));
}

export function appendAutoplayParam(rawUrl: string): string {
  if (!rawUrl) return '';
  try {
    const u = new URL(rawUrl);
    if (!u.searchParams.has('autoplay')) {
      u.searchParams.set('autoplay', '1');
    }
    return u.toString();
  } catch {
    return rawUrl.includes('autoplay=')
      ? rawUrl
      : `${rawUrl}${rawUrl.includes('?') ? '&' : '?'}autoplay=1`;
  }
}

export function formatPlayerTime(secs: number): string {
  if (!secs || Number.isNaN(secs) || secs < 0) return '00:00';
  const total = Math.floor(secs);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) {
    return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  }
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}
