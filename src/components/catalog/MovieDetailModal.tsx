import React, { useEffect, useMemo, useRef, useState } from 'react';
import { DonghuaMovie, EpisodeItem, OfflineEpisodeRecord, ServerGroup, WatchProgress } from '../../types';
import { ApiService, selectPreferredServerIdx } from '../../services/api.service';
import { OfflineCacheService } from '../../services/offline.service';
import { StorageService } from '../../services/storage.service';
import { focusFirstTvTarget, moveSpatialFocus } from '../../utils/tvSpatialNav';

interface MovieDetailModalProps {
  movie: DonghuaMovie;
  isFavorite: boolean;
  progress?: WatchProgress;
  defaultAudio?: 'Vietsub' | 'Thuyết Minh';
  onClose: () => void;
  onPlayEpisode: (movie: DonghuaMovie, episode: number, serverIdx?: number) => void;
  onToggleFavorite: (id: string) => void;
  onMarkFavoriteSeen?: (donghuaId: string, latestEp: number) => void;
  onOfflineUpdated?: () => void;
  onToast?: (msg: string) => void;
}

const EP_PAGE_SIZE = 50;

function parseEpisodeNum(ep: EpisodeItem | undefined, fallback: number): number {
  if (!ep) return fallback;
  const m = (ep.slug || ep.name || '').match(/(\d+(?:\.\d+)?)/);
  if (m) {
    const n = parseFloat(m[1]);
    if (!Number.isNaN(n) && n > 0) return n;
  }
  return fallback;
}

export const MovieDetailModal: React.FC<MovieDetailModalProps> = ({
  movie,
  isFavorite,
  progress,
  defaultAudio = 'Thuyết Minh',
  onClose,
  onPlayEpisode,
  onToggleFavorite,
  onMarkFavoriteSeen,
  onOfflineUpdated,
  onToast
}) => {
  const modalRef = useRef<HTMLDivElement | null>(null);
  const [serverGroups, setServerGroups] = useState<ServerGroup[]>(movie.serverGroups || []);
  const [activeServerIdx, setActiveServerIdx] = useState<number>(() =>
    selectPreferredServerIdx(movie.serverGroups || [], defaultAudio)
  );
  const [epRangeIdx, setEpRangeIdx] = useState<number>(0);
  const [description, setDescription] = useState<string>(movie.description || '');
  const [latestEp, setLatestEp] = useState<number>(movie.latestEp);
  const [totalEp, setTotalEp] = useState<number>(movie.totalEp);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [offlineMap, setOfflineMap] = useState<Record<string, OfflineEpisodeRecord>>(() =>
    StorageService.getOfflineEpisodes()
  );
  const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
  const [downloadPct, setDownloadPct] = useState<number>(0);

  useEffect(() => {
    let cancelled = false;
    async function loadDetail() {
      if (!movie.slug && !movie.yanUrl) return;
      setIsLoading(true);
      try {
        const detail = await ApiService.fetchMovieDetail(movie.slug || '', movie);
        if (!cancelled) {
          if (detail.serverGroups && detail.serverGroups.length > 0) {
            setServerGroups(detail.serverGroups);
            setActiveServerIdx(selectPreferredServerIdx(detail.serverGroups, defaultAudio));
            setEpRangeIdx(0);
          }
          if (detail.description) {
            setDescription(detail.description);
          }
          if (detail.latestEp) {
            setLatestEp(detail.latestEp);
            if (isFavorite && onMarkFavoriteSeen) {
              onMarkFavoriteSeen(movie.id, detail.latestEp);
            }
          }
          if (detail.totalEp) {
            setTotalEp(detail.totalEp);
          }
        }
      } catch (err) {
        console.warn('loadDetail error:', err);
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    }
    loadDetail();
    return () => {
      cancelled = true;
    };
  }, [movie.slug, movie.yanUrl, movie.id, defaultAudio]);

  useEffect(() => {
    if (isFavorite && onMarkFavoriteSeen) {
      onMarkFavoriteSeen(movie.id, latestEp);
    }
  }, [movie.id, isFavorite, latestEp]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      focusFirstTvTarget(modalRef.current);
    }, 60);
    return () => window.clearTimeout(timer);
  }, [movie.id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag)) return;

      if (e.key === 'Escape' || e.key === 'Backspace' || e.key === 'BrowserBack') {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key === 'ArrowRight') {
        if (moveSpatialFocus('right')) e.preventDefault();
      } else if (e.key === 'ArrowLeft') {
        if (moveSpatialFocus('left')) e.preventDefault();
      } else if (e.key === 'ArrowUp') {
        if (moveSpatialFocus('up')) e.preventDefault();
      } else if (e.key === 'ArrowDown') {
        if (moveSpatialFocus('down')) e.preventDefault();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const currentServer = serverGroups[activeServerIdx] || serverGroups[0];
  const episodes = currentServer?.episodes || [];
  const totalEpCount = episodes.length > 0 ? episodes.length : Math.max(latestEp || 1, movie.latestEp || 1, 1);

  const epRanges = useMemo(() => {
    const ranges: { label: string; start: number; end: number }[] = [];
    for (let start = 0; start < totalEpCount; start += EP_PAGE_SIZE) {
      const end = Math.min(start + EP_PAGE_SIZE, totalEpCount);
      ranges.push({
        label: `${start + 1} - ${end}`,
        start,
        end
      });
    }
    return ranges;
  }, [totalEpCount]);

  const currentRange = epRanges[epRangeIdx] || { label: `1 - ${totalEpCount}`, start: 0, end: totalEpCount };
  const enrichedMovie: DonghuaMovie = {
    ...movie,
    latestEp,
    totalEp,
    serverGroups: serverGroups.length > 0 ? serverGroups : movie.serverGroups,
    description: description || movie.description
  };

  // Find a downloadable HLS episode matching current progress or episode 1 across serverGroups
  const targetDownloadEpNum = progress?.episode || 1;
  const hlsCandidate = useMemo(() => {
    const srvOrder = [currentServer, ...serverGroups].filter(Boolean) as ServerGroup[];
    for (const srv of srvOrder) {
      const ep =
        srv.episodes.find((e, idx) => parseEpisodeNum(e, idx + 1) === targetDownloadEpNum) ||
        srv.episodes[0];
      if (ep?.linkM3u8 && ep.linkM3u8.includes('.m3u8')) {
        return { srvName: srv.serverName, ep, epNum: parseEpisodeNum(ep, targetDownloadEpNum) };
      }
    }
    return null;
  }, [currentServer, serverGroups, targetDownloadEpNum]);

  const offlineTargetKey = OfflineCacheService.buildEpisodeKey(
    movie.id,
    hlsCandidate?.epNum || targetDownloadEpNum
  );
  const isTargetSavedOffline = Boolean(offlineMap[offlineTargetKey]);

  const handleToggleOfflineEpisode = async () => {
    if (isTargetSavedOffline) {
      await OfflineCacheService.removeOfflineEpisode(offlineTargetKey);
      setOfflineMap(StorageService.getOfflineEpisodes());
      onOfflineUpdated?.();
      onToast?.(`Đã xóa bản lưu offline Tập ${hlsCandidate?.epNum || targetDownloadEpNum}`);
      return;
    }
    if (!hlsCandidate) {
      onToast?.('Máy chủ hiện tại phát qua Embed trực tuyến, chưa có luồng HLS (.m3u8) để lưu offline.');
      return;
    }
    try {
      setDownloadingKey(offlineTargetKey);
      setDownloadPct(5);
      await OfflineCacheService.cacheHlsEpisode(
        {
          donghuaId: movie.id,
          title: movie.title,
          poster: movie.poster,
          episodeNum: hlsCandidate.epNum,
          episodeName: hlsCandidate.ep.name || `Tập ${hlsCandidate.epNum}`,
          serverName: hlsCandidate.srvName,
          streamUrl: hlsCandidate.ep.linkM3u8
        },
        (pct) => setDownloadPct(pct)
      );
      setOfflineMap(StorageService.getOfflineEpisodes());
      onOfflineUpdated?.();
      onToast?.(`Đã lưu offline Tập ${hlsCandidate.epNum} (${movie.title})!`);
    } catch (err: any) {
      onToast?.(err?.message || 'Lưu offline thất bại');
    } finally {
      setDownloadingKey(null);
      setDownloadPct(0);
    }
  };

  return (
    <div
      ref={modalRef}
      data-tv-modal="true"
      className="fixed inset-0 z-50 bg-slate-950/75 backdrop-blur-xl overflow-y-auto flex items-start justify-center p-3 sm:p-6"
    >
      <div className="relative max-w-4xl w-full liquid-glass rounded-3xl overflow-hidden shadow-2xl my-auto">
        {/* Hh3dPortalBackdropView: Blurred Dynamic Backdrop */}
        <div className="relative h-52 sm:h-64 w-full overflow-hidden bg-slate-900">
          <img
            src={movie.thumb || movie.poster}
            alt={movie.title}
            className="w-full h-full object-cover scale-110 blur-md opacity-60"
          />
          <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/60 to-transparent"></div>

          <button
            type="button"
            onClick={onClose}
            className="absolute top-4 right-4 w-10 h-10 rounded-full bg-black/55 hover:bg-black/80 text-white flex items-center justify-center backdrop-blur-md border border-white/15 tv-focus-target"
            aria-label="Đóng chi tiết"
          >
            <i className="fa-solid fa-xmark text-base"></i>
          </button>

          <div className="absolute bottom-4 left-4 right-4 flex items-end gap-4">
            <img
              src={movie.poster}
              alt={movie.title}
              className="w-24 sm:w-32 aspect-[3/4] rounded-2xl object-cover shadow-2xl border-2 border-white/25 shrink-0"
            />
            <div className="min-w-0 flex-1 text-white pb-1">
              <div className="flex flex-wrap items-center gap-1.5 mb-1.5">
                <span className="px-2 py-0.5 rounded-md bg-cyan-500 text-slate-950 text-[10px] font-extrabold">
                  {movie.quality}
                </span>
                <span className="px-2 py-0.5 rounded-md bg-indigo-600/90 text-white text-[10px] font-bold">
                  Tập {latestEp}/{totalEp}
                </span>
                <span className="px-2 py-0.5 rounded-md bg-amber-500/20 text-amber-300 text-[10px] font-bold">
                  <i className="fa-solid fa-star mr-1"></i>
                  {movie.rating}
                </span>
              </div>
              <h2 className="font-heading font-extrabold text-lg sm:text-2xl line-clamp-2 leading-tight">
                {movie.title}
              </h2>
              <p className="text-xs text-slate-300 line-clamp-1 mt-0.5">{movie.originalTitle}</p>
            </div>
          </div>
        </div>

        {/* Hh3dDetailContainer Body */}
        <div className="p-5 sm:p-6 space-y-5">
          {/* Primary CTA & Meta */}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-2.5">
              <button
                type="button"
                data-tv-autofocus="true"
                onClick={() =>
                  onPlayEpisode(enrichedMovie, progress?.episode || 1, activeServerIdx)
                }
                className="px-5 py-2.5 rounded-2xl bg-gradient-to-r from-indigo-600 to-cyan-500 hover:brightness-110 text-white text-xs sm:text-sm font-extrabold shadow-lg shadow-indigo-500/30 flex items-center gap-2 tv-focus-target"
              >
                <i className="fa-solid fa-play"></i>
                <span>
                  {progress
                    ? `Tiếp tục Tập ${progress.episode} (${progress.progressPct}%)`
                    : 'Xem Ngay Tập 1'}
                </span>
              </button>

              <button
                type="button"
                onClick={() => onToggleFavorite(movie.id)}
                className={`px-4 py-2.5 rounded-2xl liquid-pill text-xs font-bold flex items-center gap-2 tv-focus-target ${
                  isFavorite ? 'text-rose-500 border-rose-500/40' : 'text-main'
                }`}
              >
                <i className={`${isFavorite ? 'fa-solid' : 'fa-regular'} fa-heart`}></i>
                <span>{isFavorite ? 'Đã thích' : 'Thêm tủ phim'}</span>
              </button>

              <button
                type="button"
                disabled={downloadingKey !== null}
                onClick={handleToggleOfflineEpisode}
                className={`px-3.5 py-2.5 rounded-2xl liquid-pill text-xs font-bold flex items-center gap-2 tv-focus-target ${
                  isTargetSavedOffline
                    ? 'text-emerald-600 dark:text-emerald-300 border-emerald-500/40'
                    : hlsCandidate
                    ? 'text-indigo-600 dark:text-cyan-300'
                    : 'text-muted opacity-80'
                }`}
                title={
                  hlsCandidate
                    ? `Lưu Tập ${hlsCandidate.epNum} (${hlsCandidate.srvName}) để xem không cần mạng`
                    : 'Nguồn này phát Embed trực tuyến (không có .m3u8 trực tiếp)'
                }
              >
                <i
                  className={`fa-solid ${
                    downloadingKey
                      ? 'fa-spinner animate-spin'
                      : isTargetSavedOffline
                      ? 'fa-circle-check'
                      : 'fa-cloud-arrow-down'
                  }`}
                ></i>
                <span>
                  {downloadingKey
                    ? `Đang lưu ${downloadPct}%`
                    : isTargetSavedOffline
                    ? `Đã lưu Tập ${hlsCandidate?.epNum || targetDownloadEpNum}`
                    : hlsCandidate
                    ? `Lưu Offline Tập ${hlsCandidate.epNum}`
                    : 'Embed Online'}
                </span>
              </button>
            </div>

            <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
              <span>
                <i className="fa-regular fa-calendar mr-1 text-indigo-500"></i>
                {movie.scheduleDay} • {movie.scheduleTime}
              </span>
              <span>•</span>
              <span>{movie.studio}</span>
            </div>
          </div>

          {/* Genres & Synopsis */}
          <div className="space-y-2">
            <div className="flex flex-wrap gap-1.5">
              {movie.genres.map((g) => (
                <span
                  key={g}
                  className="px-2.5 py-0.5 rounded-full bg-indigo-500/10 text-indigo-600 dark:text-cyan-300 text-[11px] font-semibold"
                >
                  {g}
                </span>
              ))}
            </div>
            <p className="text-xs sm:text-sm text-muted leading-relaxed">
              {description ||
                `${movie.title} (${movie.originalTitle}) — Bộ hoạt hình 3D đặc sắc do ${movie.studio} sản xuất, hỗ trợ chất lượng ${movie.quality} cùng đa máy chủ Vietsub và Thuyết Minh.`}
            </p>
          </div>

          {/* Server Switcher */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h3 className="font-heading font-extrabold text-sm text-main flex items-center gap-2">
                <i className="fa-solid fa-server text-indigo-600 dark:text-cyan-400"></i>
                <span>Chọn Máy Chủ &amp; Danh Sách Tập ({totalEpCount} tập)</span>
              </h3>
              {isLoading && (
                <span className="text-[11px] text-indigo-600 dark:text-cyan-400 animate-pulse font-semibold">
                  <i className="fa-solid fa-spinner animate-spin mr-1"></i>
                  Đang nạp đa nguồn...
                </span>
              )}
            </div>

            {serverGroups.length > 0 && (
              <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-1">
                {serverGroups.map((srv, idx) => (
                  <button
                    key={idx}
                    type="button"
                    onClick={() => {
                      setActiveServerIdx(idx);
                      setEpRangeIdx(0);
                    }}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold whitespace-nowrap liquid-pill tv-focus-target ${
                      activeServerIdx === idx ? 'active' : 'text-main'
                    }`}
                  >
                    {srv.serverName} ({srv.episodes.length})
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Episode Range Pagination (1-50, 51-100, ...) */}
          {epRanges.length > 1 && (
            <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar pb-1">
              {epRanges.map((rng, idx) => (
                <button
                  key={rng.label}
                  type="button"
                  onClick={() => setEpRangeIdx(idx)}
                  className={`px-2.5 py-1 rounded-lg text-[11px] font-bold shrink-0 tv-focus-target ${
                    epRangeIdx === idx
                      ? 'bg-indigo-600 text-white'
                      : 'bg-slate-200/70 dark:bg-slate-800 text-main'
                  }`}
                >
                  Tập {rng.label}
                </button>
              ))}
            </div>
          )}

          {/* Episode Grid */}
          <div className="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 gap-2 max-h-56 overflow-y-auto pr-1">
            {episodes.length > 0
              ? episodes.slice(currentRange.start, currentRange.end).map((ep, localIdx) => {
                  const fallbackNum = currentRange.start + localIdx + 1;
                  const realEpNum = parseEpisodeNum(ep, fallbackNum);
                  const isWatching = progress?.episode === realEpNum;
                  const isOffline = Boolean(
                    offlineMap[OfflineCacheService.buildEpisodeKey(movie.id, realEpNum)]
                  );
                  return (
                    <button
                      key={`${realEpNum}_${localIdx}`}
                      type="button"
                      onClick={() => onPlayEpisode(enrichedMovie, realEpNum, activeServerIdx)}
                      className={`py-2 px-1.5 rounded-xl text-xs font-bold truncate transition-all tv-focus-target relative ${
                        isWatching
                          ? 'bg-gradient-to-r from-indigo-600 to-cyan-500 text-white shadow'
                          : isOffline
                          ? 'liquid-card text-emerald-600 dark:text-emerald-300 border-emerald-500/40'
                          : 'liquid-card text-main hover:border-indigo-500'
                      }`}
                    >
                      {ep.name
                        ? /^\d+$/.test(ep.name.trim())
                          ? `Tập ${ep.name.trim()}`
                          : ep.name
                        : `Tập ${realEpNum}`}
                    </button>
                  );
                })
              : Array.from(
                  { length: currentRange.end - currentRange.start },
                  (_, i) => currentRange.start + i + 1
                ).map((epNum) => (
                  <button
                    key={epNum}
                    type="button"
                    onClick={() => onPlayEpisode(enrichedMovie, epNum, activeServerIdx)}
                    className={`py-2 px-1.5 rounded-xl text-xs font-bold transition-all tv-focus-target ${
                      progress?.episode === epNum
                        ? 'bg-gradient-to-r from-indigo-600 to-cyan-500 text-white shadow'
                        : 'liquid-card text-main hover:border-indigo-500'
                    }`}
                  >
                    Tập {epNum}
                  </button>
                ))}
          </div>
        </div>
      </div>
    </div>
  );
};
