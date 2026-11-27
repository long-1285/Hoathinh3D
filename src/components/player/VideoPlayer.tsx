import React, { useEffect, useMemo, useRef, useState } from 'react';
import Hls from 'hls.js';
import {
  AppSettings,
  DeviceMode,
  DonghuaMovie,
  DonghuaSource,
  EpisodeItem,
  OfflineEpisodeRecord,
  ServerGroup
} from '../../types';
import { StorageService } from '../../services/storage.service';
import { ApiService, isDubbedServerName, selectPreferredServerIdx } from '../../services/api.service';
import { WpDonghuaService } from '../../services/yanhh3d.service';
import { STREAM_URLS } from '../../constants/initialData';
import { useWakeLock } from '../../hooks/useWakeLock';
import { useTVRemote } from '../../hooks/useTVRemote';
import { OfflineCacheService } from '../../services/offline.service';
import { focusFirstTvTarget } from '../../utils/tvSpatialNav';
import {
  ASPECT_LABELS,
  AspectMode,
  EP_PAGE_SIZE,
  HlsQualityLevel,
  appendAutoplayParam,
  extractEpisodeNumber,
  findEpisodeIdxByNumber,
  formatPlayerTime,
  getDeviceHlsConfig,
  resolvePlayerDeviceProfile
} from './playerUtils';
import { PlayerDrawer, PlayerDrawerType } from './PlayerDrawer';

interface VideoPlayerProps {
  movie: DonghuaMovie;
  initialEpisode?: number;
  initialServerIdx?: number;
  sources: DonghuaSource[];
  settings: AppSettings;
  resolvedDevice?: Exclude<DeviceMode, 'auto'>;
  sleepPauseTick?: number;
  onClose: () => void;
  onToast: (msg: string) => void;
}

export const VideoPlayer: React.FC<VideoPlayerProps> = ({
  movie,
  initialEpisode = 1,
  initialServerIdx,
  sources: _sources,
  settings,
  resolvedDevice,
  sleepPauseTick = 0,
  onClose,
  onToast
}) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const autoSkippedEpRef = useRef<string | null>(null);
  const failedServersRef = useRef<Set<number>>(new Set());
  const hudTimerRef = useRef<number | null>(null);
  const gestureFadeTimerRef = useRef<number | null>(null);
  const lastTapRef = useRef<{ time: number; side: 'left' | 'right' | 'center' }>({
    time: 0,
    side: 'center'
  });

  // Touch/Pointer Gesture Session (Vertical = Brightness/Volume, Horizontal = Scrub Seek)
  const gestureSessionRef = useRef<{
    active: boolean;
    axis: 'none' | 'vertical' | 'horizontal';
    side: 'left' | 'right';
    startX: number;
    startY: number;
    startValue: number;
    startTime: number;
  } | null>(null);

  const deviceProfile = useMemo(
    () => resolvePlayerDeviceProfile(resolvedDevice, settings.deviceMode),
    [resolvedDevice, settings.deviceMode]
  );
  const isTvProfile = deviceProfile === 'tv';
  const isPcProfile = deviceProfile === 'pc';
  const isMobileProfile = deviceProfile === 'mobile';

  const [serverGroups, setServerGroups] = useState<ServerGroup[]>(movie.serverGroups || []);
  const [activeServerIdx, setActiveServerIdx] = useState<number>(() =>
    typeof initialServerIdx === 'number'
      ? initialServerIdx
      : selectPreferredServerIdx(movie.serverGroups || [], settings.defaultAudio)
  );
  const [currentEpIdx, setCurrentEpIdx] = useState<number>(() => {
    const groups = movie.serverGroups || [];
    const srvIdx =
      typeof initialServerIdx === 'number'
        ? initialServerIdx
        : selectPreferredServerIdx(groups, settings.defaultAudio);
    const eps = groups[srvIdx]?.episodes || [];
    return eps.length > 0
      ? findEpisodeIdxByNumber(eps, initialEpisode)
      : Math.max(0, initialEpisode - 1);
  });
  const [epRangeIdx, setEpRangeIdx] = useState<number>(
    Math.floor(Math.max(0, initialEpisode - 1) / EP_PAGE_SIZE)
  );
  const [preferEmbedMode, setPreferEmbedMode] = useState<boolean>(false);
  const [resolvedStreams, setResolvedStreams] = useState<
    Record<string, { linkEmbed: string; linkM3u8: string }>
  >({});
  const [isLoadingServers, setIsLoadingServers] = useState<boolean>(
    Boolean((movie.slug || movie.yanUrl) && (!movie.serverGroups || movie.serverGroups.length === 0))
  );
  const [isLoadingStream, setIsLoadingStream] = useState<boolean>(true);
  const [isEmbedLoading, setIsEmbedLoading] = useState<boolean>(false);
  const [hasStreamError, setHasStreamError] = useState<boolean>(false);
  const [streamInfo, setStreamInfo] = useState<string>('Đang kết nối luồng phát...');

  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [isMutedAutoplay, setIsMutedAutoplay] = useState<boolean>(false);
  const [embedReloadTick, setEmbedReloadTick] = useState<number>(0);
  const [showHud, setShowHud] = useState<boolean>(true);
  const [isLocked, setIsLocked] = useState<boolean>(false);
  const [isPortraitLocked, setIsPortraitLocked] = useState<boolean>(false);
  const [activeDrawer, setActiveDrawer] = useState<PlayerDrawerType>('none');

  const [currentTime, setCurrentTime] = useState<number>(0);
  const [duration, setDuration] = useState<number>(0);
  const [hoverSeekInfo, setHoverSeekInfo] = useState<{ time: number; pct: number } | null>(null);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(settings.playbackSpeed || 1);
  const [aspectRatio, setAspectRatio] = useState<AspectMode>(
    (settings.aspectRatio as AspectMode) || '16:9'
  );
  const [hlsLevels, setHlsLevels] = useState<HlsQualityLevel[]>([]);
  const [activeQualityIdx, setActiveQualityIdx] = useState<number>(-1);
  const [hasSkippedIntro, setHasSkippedIntro] = useState<boolean>(false);
  const [autoNextCountdown, setAutoNextCountdown] = useState<number | null>(null);
  const [offlineMap] = useState<Record<string, OfflineEpisodeRecord>>(() =>
    StorageService.getOfflineEpisodes()
  );

  // Brightness (0.15 to 1.0) & Volume (0.0 to 1.0)
  const [brightness, setBrightness] = useState<number>(1);
  const [volume, setVolume] = useState<number>(
    typeof settings.volume === 'number' ? settings.volume : 1
  );
  const [gestureOverlay, setGestureOverlay] = useState<{
    type: 'brightness' | 'volume' | 'seek';
    value: number;
    delta?: number;
  } | null>(null);

  const [showRippleLeft, setShowRippleLeft] = useState<boolean>(false);
  const [showRippleRight, setShowRippleRight] = useState<boolean>(false);
  const [resumePrompt, setResumePrompt] = useState<{ time: number } | null>(null);

  const seekStep = settings.seekStep || 10;

  useWakeLock(isPlaying);

  const wakeHud = () => {
    setShowHud(true);
    if (hudTimerRef.current) window.clearTimeout(hudTimerRef.current);
    hudTimerRef.current = window.setTimeout(() => {
      setShowHud(false);
    }, isTvProfile ? 4500 : 3500);
  };

  useEffect(() => {
    wakeHud();
    return () => {
      if (hudTimerRef.current) window.clearTimeout(hudTimerRef.current);
      if (gestureFadeTimerRef.current) window.clearTimeout(gestureFadeTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (activeDrawer !== 'none' || autoNextCountdown !== null) {
      const t = window.setTimeout(() => focusFirstTvTarget(), 45);
      return () => window.clearTimeout(t);
    } else if (isTvProfile && showHud) {
      const t = window.setTimeout(() => focusFirstTvTarget(containerRef.current), 45);
      return () => window.clearTimeout(t);
    }
  }, [activeDrawer, autoNextCountdown, isTvProfile]);

  // Sync video volume & speed on mount / change
  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.volume = volume;
      if (volume > 0 && videoRef.current.muted && !isMutedAutoplay) {
        videoRef.current.muted = false;
      }
    }
  }, [volume, isMutedAutoplay]);

  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.playbackRate = playbackSpeed;
    }
  }, [playbackSpeed]);

  // Pause video if sleep timer triggers
  useEffect(() => {
    if (sleepPauseTick > 0 && videoRef.current && !videoRef.current.paused) {
      videoRef.current.pause();
    }
  }, [sleepPauseTick]);

  // TV Remote & Spatial D-Pad controls inside player
  useTVRemote({
    onBack: () => {
      if (autoNextCountdown !== null) {
        setAutoNextCountdown(null);
      } else if (activeDrawer !== 'none') {
        setActiveDrawer('none');
      } else if (isLocked) {
        setIsLocked(false);
        wakeHud();
      } else {
        onClose();
      }
    },
    onPlayPause: () => {
      if (isLocked) return;
      wakeHud();
      togglePlay();
    },
    onSeekForward: () => {
      if (isLocked) return;
      wakeHud();
      handleSeekDelta(seekStep);
    },
    onSeekBackward: () => {
      if (isLocked) return;
      wakeHud();
      handleSeekDelta(-seekStep);
    },
    onVolumeUp: () => {
      if (isLocked) return;
      wakeHud();
      adjustVolume(0.1);
    },
    onVolumeDown: () => {
      if (isLocked) return;
      wakeHud();
      adjustVolume(-0.1);
    },
    onToggleFullscreen: () => toggleFullscreen(),
    onAnyRemoteKey: wakeHud,
    spatialNavigation: true
  });

  // 1. Fetch live episodes only when movie doesn't already include serverGroups
  useEffect(() => {
    let isCancelled = false;

    async function loadRealEpisodes() {
      if (movie.serverGroups && movie.serverGroups.length > 0) {
        setIsLoadingServers(false);
        return;
      }
      if (movie.slug || movie.yanUrl || (movie.wpSources && movie.wpSources.length > 0)) {
        setIsLoadingServers(true);
        setIsLoadingStream(true);
        try {
          const detail = await ApiService.fetchMovieDetail(movie.slug || '', movie);
          if (!isCancelled && detail.serverGroups && detail.serverGroups.length > 0) {
            setServerGroups(detail.serverGroups);
            const targetSrvIdx =
              typeof initialServerIdx === 'number' && initialServerIdx < detail.serverGroups.length
                ? initialServerIdx
                : selectPreferredServerIdx(detail.serverGroups, settings.defaultAudio);
            setActiveServerIdx(targetSrvIdx);
            const srvEps = detail.serverGroups[targetSrvIdx]?.episodes || [];
            setCurrentEpIdx(findEpisodeIdxByNumber(srvEps, initialEpisode));
          }
        } finally {
          if (!isCancelled) {
            setIsLoadingServers(false);
          }
        }
      } else {
        setIsLoadingServers(false);
      }
    }

    loadRealEpisodes();
    return () => {
      isCancelled = true;
    };
  }, [movie.slug, movie.yanUrl]);

  useEffect(() => {
    failedServersRef.current = new Set();
    setPreferEmbedMode(false);
    setHasSkippedIntro(false);
    setAutoNextCountdown(null);
    setEpRangeIdx(Math.floor(currentEpIdx / EP_PAGE_SIZE));
  }, [currentEpIdx, movie.id]);

  const currentServer = serverGroups[activeServerIdx] || serverGroups[0];
  const episodesList: EpisodeItem[] = currentServer?.episodes || [];
  const safeEpIdx =
    episodesList.length > 0 ? Math.min(currentEpIdx, episodesList.length - 1) : currentEpIdx;
  const currentEpisodeItem: EpisodeItem | undefined = episodesList[safeEpIdx];
  const currentEpisodeNum = extractEpisodeNumber(currentEpisodeItem, safeEpIdx);
  const totalEpCount = episodesList.length > 0 ? episodesList.length : movie.latestEp || 24;

  const epRanges = useMemo(() => {
    const ranges: { label: string; start: number; end: number }[] = [];
    for (let start = 0; start < totalEpCount; start += EP_PAGE_SIZE) {
      const end = Math.min(start + EP_PAGE_SIZE, totalEpCount);
      ranges.push({ label: `${start + 1} - ${end}`, start, end });
    }
    return ranges;
  }, [totalEpCount]);

  const currentRange = epRanges[epRangeIdx] || {
    label: `1 - ${totalEpCount}`,
    start: 0,
    end: totalEpCount
  };

  const wpPostId = currentEpisodeItem?.wpPostId || currentEpisodeItem?.yanPostId;
  const wpEpSlug = currentEpisodeItem?.wpEpSlug || currentEpisodeItem?.yanEpSlug;
  const wpServerType = currentEpisodeItem?.wpServerType || currentEpisodeItem?.yanServerType;
  const wpBaseUrl = currentEpisodeItem?.wpBaseUrl || 'https://yanhh3d.ee';
  const wpServerSv = currentEpisodeItem?.wpServerSv || '1';
  const wpPageUrl = currentEpisodeItem?.wpPageUrl || currentEpisodeItem?.yanPageUrl;

  const wpStreamKey =
    wpPostId && wpEpSlug && wpServerType
      ? `${wpBaseUrl}_${wpPostId}_${wpEpSlug}_${wpServerType}_${wpServerSv}`
      : '';
  const resolvedWp = wpStreamKey ? resolvedStreams[wpStreamKey] : undefined;
  const activeM3u8 = currentEpisodeItem?.linkM3u8 || resolvedWp?.linkM3u8 || '';
  const activeEmbed = currentEpisodeItem?.linkEmbed || resolvedWp?.linkEmbed || '';
  const needsWpResolve = Boolean(wpStreamKey && !activeM3u8 && !activeEmbed);

  const hasM3u8 = Boolean(activeM3u8);
  const hasEmbed = Boolean(activeEmbed);
  const useEmbedNow = Boolean(hasEmbed && (preferEmbedMode || !hasM3u8));

  const getActiveStreamUrl = (): string => {
    const offlineRecord =
      offlineMap[OfflineCacheService.buildEpisodeKey(movie.id, currentEpisodeNum)];
    if (activeM3u8) {
      return activeM3u8;
    }
    if (offlineRecord?.streamUrl) {
      return offlineRecord.streamUrl;
    }
    const baseIdx = movie.streamIndex % STREAM_URLS.length;
    return STREAM_URLS[baseIdx];
  };

  // Lazy-resolve WP Donghua server stream via player.php
  useEffect(() => {
    if (!needsWpResolve || !wpPostId || !wpEpSlug || !wpServerType) return;
    let cancelled = false;

    async function resolveWpStream() {
      setIsLoadingStream(true);
      setHasStreamError(false);
      setStreamInfo(
        `Đang kết nối ${currentServer?.serverName || 'Server 4K'} • ${currentEpisodeItem?.name || ''}`
      );
      try {
        const res = await WpDonghuaService.resolveStream(
          wpBaseUrl,
          wpPostId!,
          wpEpSlug!,
          wpServerType!,
          wpServerSv,
          wpPageUrl
        );
        if (cancelled) return;
        if (res.linkEmbed || res.linkM3u8) {
          setResolvedStreams((prev) => ({ ...prev, [wpStreamKey]: res }));
          setIsLoadingStream(false);
        } else {
          const switched = tryAutoFailover();
          if (!switched) {
            setIsLoadingStream(false);
            setHasStreamError(true);
          }
        }
      } catch {
        if (!cancelled) {
          const switched = tryAutoFailover();
          if (!switched) {
            setIsLoadingStream(false);
            setHasStreamError(true);
          }
        }
      }
    }

    resolveWpStream();
    return () => {
      cancelled = true;
    };
  }, [wpStreamKey, needsWpResolve]);

  const switchToServer = (nextServerIdx: number) => {
    const nextGroup = serverGroups[nextServerIdx];
    const mappedEpIdx = nextGroup
      ? findEpisodeIdxByNumber(nextGroup.episodes, currentEpisodeNum)
      : currentEpIdx;
    setActiveServerIdx(nextServerIdx);
    setCurrentEpIdx(mappedEpIdx);
  };

  const tryAutoFailover = (): boolean => {
    failedServersRef.current.add(activeServerIdx);
    const currentlyDubbed = isDubbedServerName(currentServer?.serverName || '');
    let nextIdx = serverGroups.findIndex(
      (g, idx) =>
        !failedServersRef.current.has(idx) &&
        isDubbedServerName(g.serverName || '') === currentlyDubbed
    );
    if (nextIdx < 0) {
      nextIdx = serverGroups.findIndex((_, idx) => !failedServersRef.current.has(idx));
    }
    if (nextIdx >= 0) {
      onToast(
        `Luồng hiện tại gặp sự cố — Tự động chuyển sang ${serverGroups[nextIdx].serverName}`
      );
      switchToServer(nextIdx);
      return true;
    }
    if (hasEmbed && !preferEmbedMode) {
      onToast('Chuyển sang trình phát Embed dự phòng');
      setPreferEmbedMode(true);
      return true;
    }
    return false;
  };

  const attemptAutoplayVideo = async (video: HTMLVideoElement) => {
    video.playbackRate = playbackSpeed;
    video.volume = volume;
    video.muted = volume === 0;
    try {
      await video.play();
      setIsMutedAutoplay(false);
    } catch {
      try {
        video.muted = true;
        await video.play();
        setIsMutedAutoplay(true);
      } catch {}
    }
  };

  // 2. Load stream into device-tuned HLS.js or HTML5 Video
  useEffect(() => {
    if (isLoadingServers || needsWpResolve) {
      return;
    }
    if (useEmbedNow) {
      setIsLoadingStream(false);
      setIsEmbedLoading(true);
      setHasStreamError(false);
      setHlsLevels([]);
      setActiveQualityIdx(-1);
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
      const existingProg = StorageService.getHistory()[movie.id];
      const baseSeconds =
        existingProg && existingProg.episode === currentEpisodeNum ? existingProg.currentTime : 0;
      const estimatedDuration = 1200;
      let elapsedSeconds = baseSeconds;
      const saveEmbedProg = (secs: number) => {
        StorageService.saveProgress({
          donghuaId: movie.id,
          title: movie.title,
          poster: movie.poster,
          episode: currentEpisodeNum,
          currentTime: secs,
          duration: estimatedDuration,
          progressPct: Math.min(95, Math.max(10, Math.round((secs / estimatedDuration) * 100))),
          server: currentServer?.serverName || 'YanHH3D',
          audio: isDubbedServerName(currentServer?.serverName || '') ? 'Thuyết Minh' : 'Vietsub',
          updatedAt: Date.now()
        });
      };
      saveEmbedProg(elapsedSeconds);
      const embedSafetyTimer = window.setTimeout(() => setIsEmbedLoading(false), 3800);
      const embedProgTimer = window.setInterval(() => {
        elapsedSeconds += 15;
        saveEmbedProg(elapsedSeconds);
      }, 15000);
      return () => {
        window.clearTimeout(embedSafetyTimer);
        window.clearInterval(embedProgTimer);
      };
    }

    const video = videoRef.current;
    if (!video) return;

    const streamUrl = getActiveStreamUrl();
    setIsLoadingStream(true);
    setHasStreamError(false);
    setHlsLevels([]);
    setActiveQualityIdx(-1);
    setStreamInfo(
      `Đang tải: ${currentEpisodeItem?.name || `Tập ${currentEpisodeNum}`} • ${
        currentServer?.serverName || 'Trình phát Tích hợp'
      }`
    );

    const history = StorageService.getHistory();
    const prev = history[movie.id];
    if (
      prev &&
      prev.episode === currentEpisodeNum &&
      prev.currentTime > 10 &&
      prev.currentTime < prev.duration - 15
    ) {
      setResumePrompt({ time: prev.currentTime });
    } else {
      setResumePrompt(null);
    }

    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }

    let recoveredMediaOnce = false;
    let recoveredNetworkOnce = false;

    if (
      Hls.isSupported() &&
      (streamUrl.includes('.m3u8') || streamUrl.includes('index.m3u8') || streamUrl.startsWith('http'))
    ) {
      const hls = new Hls(getDeviceHlsConfig(deviceProfile, settings.defaultQuality));
      hls.loadSource(streamUrl);
      hls.attachMedia(video);

      hls.on(Hls.Events.MANIFEST_PARSED, (_, data) => {
        setIsLoadingStream(false);
        const levels: HlsQualityLevel[] = [{ index: -1, label: 'Tự động (ABR)', height: 0, bitrate: 0 }];
        (data?.levels || []).forEach((lvl, idx) => {
          const h = lvl.height || 0;
          const label = h > 0 ? `${h}p` : `Luồng ${idx + 1}`;
          levels.push({ index: idx, label, height: h, bitrate: lvl.bitrate || 0 });
        });
        setHlsLevels(levels.length > 1 ? levels : []);
        setActiveQualityIdx(-1);

        const epKey = `${movie.id}_${currentEpIdx}_${activeServerIdx}`;
        const hasResume = prev && prev.episode === currentEpisodeNum && prev.currentTime > 10;
        if (settings.autoSkipIntro && !hasResume && autoSkippedEpRef.current !== epKey) {
          autoSkippedEpRef.current = epKey;
          video.currentTime = 85;
          setHasSkippedIntro(true);
          onToast('Auto Skip Intro: Đã nhảy qua +85s mở đầu');
        }
        attemptAutoplayVideo(video);
      });

      hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
          if (!recoveredMediaOnce && data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            recoveredMediaOnce = true;
            hls.recoverMediaError();
            return;
          }
          if (!recoveredNetworkOnce && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            recoveredNetworkOnce = true;
            hls.startLoad();
            return;
          }
          hls.destroy();
          setIsLoadingStream(false);
          const switched = tryAutoFailover();
          if (!switched) {
            setHasStreamError(true);
          }
        }
      });

      hlsRef.current = hls;
    } else {
      const onCanPlay = () => {
        setIsLoadingStream(false);
        attemptAutoplayVideo(video);
      };
      video.addEventListener('canplay', onCanPlay, { once: true });
      video.src = streamUrl;
      video.volume = volume;
      video.playbackRate = playbackSpeed;
      video.load();
      attemptAutoplayVideo(video);
      return () => {
        video.removeEventListener('canplay', onCanPlay);
      };
    }

    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };
  }, [
    movie.id,
    activeServerIdx,
    currentEpIdx,
    serverGroups,
    isLoadingServers,
    useEmbedNow,
    needsWpResolve,
    activeM3u8,
    deviceProfile
  ]);

  // 3. Track playback progress & auto-save
  useEffect(() => {
    const video = videoRef.current;
    if (!video || useEmbedNow) return;

    const handleTimeUpdate = () => {
      setCurrentTime(video.currentTime);
      setDuration(video.duration || 0);

      if (video.currentTime > 5 && video.duration) {
        StorageService.saveProgress({
          donghuaId: movie.id,
          title: movie.title,
          poster: movie.poster,
          episode: currentEpisodeNum,
          currentTime: video.currentTime,
          duration: video.duration,
          progressPct: Math.round((video.currentTime / video.duration) * 100),
          server: currentServer?.serverName || 'Trình phát Tích hợp',
          audio: isDubbedServerName(currentServer?.serverName || '') ? 'Thuyết Minh' : 'Vietsub',
          updatedAt: Date.now()
        });
      }
    };

    const handlePlay = () => setIsPlaying(true);
    const handlePause = () => setIsPlaying(false);
    const handleWaiting = () => setIsLoadingStream(true);
    const handlePlaying = () => setIsLoadingStream(false);
    const handleEnded = () => {
      setIsPlaying(false);
      if (settings.autoNext && currentEpIdx < totalEpCount - 1) {
        setAutoNextCountdown(5);
      }
    };

    video.addEventListener('timeupdate', handleTimeUpdate);
    video.addEventListener('play', handlePlay);
    video.addEventListener('pause', handlePause);
    video.addEventListener('waiting', handleWaiting);
    video.addEventListener('playing', handlePlaying);
    video.addEventListener('ended', handleEnded);

    return () => {
      video.removeEventListener('timeupdate', handleTimeUpdate);
      video.removeEventListener('play', handlePlay);
      video.removeEventListener('pause', handlePause);
      video.removeEventListener('waiting', handleWaiting);
      video.removeEventListener('playing', handlePlaying);
      video.removeEventListener('ended', handleEnded);
    };
  }, [movie, currentEpIdx, currentEpisodeNum, activeServerIdx, episodesList, currentServer, useEmbedNow, totalEpCount, settings.autoNext]);

  // Auto-next 5-second countdown effect
  useEffect(() => {
    if (autoNextCountdown === null) return;
    if (autoNextCountdown <= 0) {
      setAutoNextCountdown(null);
      if (currentEpIdx < totalEpCount - 1) {
        setCurrentEpIdx((p) => p + 1);
        onToast(`Đang phát ${episodesList[currentEpIdx + 1]?.name || `Tập ${currentEpIdx + 2}`}`);
      }
      return;
    }
    const timer = window.setTimeout(() => {
      setAutoNextCountdown((c) => (c !== null ? c - 1 : null));
    }, 1000);
    return () => window.clearTimeout(timer);
  }, [autoNextCountdown, currentEpIdx, totalEpCount]);

  // PC / Emulator extra keyboard shortcuts (K, J, L, M, S, N, P, 0..9)
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;
      if (isLocked || useEmbedNow) return;

      const k = e.key.toLowerCase();
      if (k === 'k') {
        e.preventDefault();
        wakeHud();
        togglePlay();
      } else if (k === 'j') {
        e.preventDefault();
        wakeHud();
        handleSeekDelta(-seekStep);
      } else if (k === 'l') {
        e.preventDefault();
        wakeHud();
        handleSeekDelta(seekStep);
      } else if (k === 'm') {
        e.preventDefault();
        wakeHud();
        toggleMute();
      } else if (k === 's') {
        e.preventDefault();
        wakeHud();
        handleSkipIntro();
      } else if (k === 'n' && currentEpIdx < totalEpCount - 1) {
        e.preventDefault();
        wakeHud();
        setCurrentEpIdx((p) => p + 1);
      } else if (k === 'p' && safeEpIdx > 0) {
        e.preventDefault();
        wakeHud();
        setCurrentEpIdx(safeEpIdx - 1);
      } else if (/^[0-9]$/.test(k) && videoRef.current && duration > 0) {
        e.preventDefault();
        wakeHud();
        const pct = parseInt(k, 10) * 0.1;
        videoRef.current.currentTime = duration * pct;
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isLocked, useEmbedNow, seekStep, currentEpIdx, safeEpIdx, totalEpCount, duration, volume]);

  const togglePlay = () => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) {
      video.play().catch(() => {});
    } else {
      video.pause();
    }
  };

  const toggleMute = () => {
    const video = videoRef.current;
    if (!video) return;
    if (video.muted || volume === 0) {
      const next = volume > 0 ? volume : 1;
      video.muted = false;
      video.volume = next;
      setVolume(next);
      setIsMutedAutoplay(false);
      showGestureBadge('volume', next);
    } else {
      video.muted = true;
      setVolume(0);
      showGestureBadge('volume', 0);
    }
  };

  const handleSeekDelta = (seconds: number) => {
    const video = videoRef.current;
    if (!video) return;
    video.currentTime = Math.max(0, Math.min(video.currentTime + seconds, video.duration || 0));
    if (seconds < 0) {
      setShowRippleLeft(true);
      setTimeout(() => setShowRippleLeft(false), 550);
    } else {
      setShowRippleRight(true);
      setTimeout(() => setShowRippleRight(false), 550);
    }
  };

  const handleSkipIntro = () => {
    const video = videoRef.current;
    if (!video) return;
    const target = Math.min(video.duration || 9999, Math.max(video.currentTime + 85, 85));
    video.currentTime = target;
    setHasSkippedIntro(true);
    onToast('Đã bỏ qua mở đầu (+85s)');
  };

  const showGestureBadge = (
    type: 'brightness' | 'volume' | 'seek',
    value: number,
    delta?: number
  ) => {
    setGestureOverlay({ type, value, delta });
    if (gestureFadeTimerRef.current) window.clearTimeout(gestureFadeTimerRef.current);
    gestureFadeTimerRef.current = window.setTimeout(() => {
      setGestureOverlay(null);
    }, 850);
  };

  const adjustVolume = (delta: number) => {
    const next = Math.max(0, Math.min(1, volume + delta));
    setVolume(next);
    if (videoRef.current) {
      videoRef.current.volume = next;
      videoRef.current.muted = next === 0;
    }
    showGestureBadge('volume', next);
  };

  const toggleFullscreen = async () => {
    if (!containerRef.current) return;
    try {
      if (!document.fullscreenElement) {
        await containerRef.current.requestFullscreen();
        if (isMobileProfile) {
          const orientation = screen.orientation as unknown as {
            lock?: (mode: string) => Promise<void>;
          };
          orientation?.lock?.('landscape').catch(() => {});
        }
      } else {
        await document.exitFullscreen();
      }
    } catch {}
  };

  const togglePictureInPicture = async () => {
    try {
      const video = videoRef.current;
      if (!video) return;
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
      } else if (document.pictureInPictureEnabled && !useEmbedNow) {
        await video.requestPictureInPicture();
      } else {
        setActiveDrawer((d) => (d === 'speed' ? 'none' : 'speed'));
      }
    } catch {
      setActiveDrawer((d) => (d === 'speed' ? 'none' : 'speed'));
    }
  };

  const toggleOrientationLock = async () => {
    const nextLocked = !isPortraitLocked;
    setIsPortraitLocked(nextLocked);
    try {
      const orientation = screen.orientation as unknown as {
        lock?: (mode: string) => Promise<void>;
      };
      if (orientation?.lock) {
        await orientation.lock(nextLocked ? 'portrait' : 'landscape');
      }
    } catch {}
    onToast(nextLocked ? 'Đã bật khóa xoay dọc (Khóa đứng)' : 'Đã chuyển chế độ xoay ngang');
  };

  const toggleAudioModeQuick = () => {
    if (serverGroups.length <= 1) {
      onToast(
        isDubbedServerName(currentServer?.serverName || '')
          ? 'Đang phát: Thuyết Minh'
          : 'Đang phát: Vietsub'
      );
      return;
    }
    const isCurrentlyThuyetMinh = isDubbedServerName(currentServer?.serverName || '');
    const targetIdx = serverGroups.findIndex((g) =>
      isCurrentlyThuyetMinh
        ? !isDubbedServerName(g.serverName)
        : isDubbedServerName(g.serverName)
    );
    const nextIdx = targetIdx >= 0 ? targetIdx : (activeServerIdx + 1) % serverGroups.length;
    switchToServer(nextIdx);
    onToast(`Đã chuyển sang: ${serverGroups[nextIdx].serverName}`);
  };

  const cycleAspectRatio = () => {
    const order: AspectMode[] = ['16:9', 'cover', 'stretch', '4:3'];
    const next = order[(order.indexOf(aspectRatio) + 1) % order.length];
    setAspectRatio(next);
    onToast(ASPECT_LABELS[next]);
  };

  const handleSelectQuality = (levelIdx: number) => {
    if (hlsRef.current) {
      hlsRef.current.currentLevel = levelIdx;
    }
    setActiveQualityIdx(levelIdx);
    const found = hlsLevels.find((l) => l.index === levelIdx);
    setActiveDrawer('none');
    onToast(`Chất lượng: ${found?.label || 'Tự động'}`);
  };

  const resumePlayback = () => {
    if (resumePrompt && videoRef.current) {
      videoRef.current.currentTime = resumePrompt.time;
      setResumePrompt(null);
      onToast(`Đã tiếp tục xem từ ${formatPlayerTime(resumePrompt.time)}`);
    }
  };

  // ==========================================================
  // TOUCH / POINTER GESTURE HANDLERS (Brightness, Volume, Horizontal Scrub)
  // ==========================================================
  const beginGesture = (clientX: number, clientY: number, rect: DOMRect) => {
    const relX = clientX - rect.left;
    const side: 'left' | 'right' = relX < rect.width / 2 ? 'left' : 'right';
    gestureSessionRef.current = {
      active: true,
      axis: 'none',
      side,
      startX: clientX,
      startY: clientY,
      startValue: side === 'left' ? brightness : volume,
      startTime: videoRef.current?.currentTime || currentTime
    };
  };

  const updateGesture = (clientX: number, clientY: number, rect: DOMRect) => {
    const sess = gestureSessionRef.current;
    if (!sess || !sess.active) return;

    const dx = clientX - sess.startX;
    const dy = clientY - sess.startY;

    if (sess.axis === 'none') {
      if (Math.abs(dy) > 14 && Math.abs(dy) > Math.abs(dx) * 1.2) {
        sess.axis = 'vertical';
      } else if (Math.abs(dx) > 18 && Math.abs(dx) > Math.abs(dy) * 1.2 && duration > 0) {
        sess.axis = 'horizontal';
      } else {
        return;
      }
    }

    if (sess.axis === 'vertical') {
      const sensitivity = Math.max(180, rect.height * 0.65);
      const delta = -dy / sensitivity;
      if (sess.side === 'left') {
        const nextB = Math.max(0.15, Math.min(1, sess.startValue + delta));
        setBrightness(nextB);
        showGestureBadge('brightness', nextB);
      } else {
        const nextV = Math.max(0, Math.min(1, sess.startValue + delta));
        setVolume(nextV);
        if (videoRef.current) {
          videoRef.current.volume = nextV;
          videoRef.current.muted = nextV === 0;
        }
        showGestureBadge('volume', nextV);
      }
    } else if (sess.axis === 'horizontal') {
      const seekSpan = Math.min(180, Math.max(60, duration * 0.15));
      const deltaSec = Math.round((dx / Math.max(220, rect.width * 0.75)) * seekSpan);
      const targetSec = Math.max(0, Math.min(duration, sess.startTime + deltaSec));
      showGestureBadge('seek', targetSec, deltaSec);
    }
  };

  const endGesture = () => {
    const sess = gestureSessionRef.current;
    gestureSessionRef.current = null;
    if (!sess) return false;
    if (sess.axis === 'horizontal' && gestureOverlay?.type === 'seek' && videoRef.current) {
      videoRef.current.currentTime = gestureOverlay.value;
      setCurrentTime(gestureOverlay.value);
    }
    return sess.axis !== 'none';
  };

  const handleStageTouchStart = (e: React.TouchEvent<HTMLDivElement>) => {
    if (useEmbedNow || activeDrawer !== 'none') return;
    if (isLocked) {
      wakeHud();
      return;
    }
    if (e.touches.length !== 1) return;
    beginGesture(e.touches[0].clientX, e.touches[0].clientY, e.currentTarget.getBoundingClientRect());
  };

  const handleStageTouchMove = (e: React.TouchEvent<HTMLDivElement>) => {
    if (isLocked || useEmbedNow || e.touches.length !== 1) return;
    updateGesture(e.touches[0].clientX, e.touches[0].clientY, e.currentTarget.getBoundingClientRect());
  };

  const handleStageTouchEnd = (e: React.TouchEvent<HTMLDivElement>) => {
    const wasGesture = endGesture();
    if (wasGesture) return;

    if (isLocked) {
      wakeHud();
      return;
    }

    const touch = e.changedTouches[0];
    if (!touch) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const relX = touch.clientX - rect.left;
    const side: 'left' | 'right' | 'center' =
      relX < rect.width * 0.34 ? 'left' : relX > rect.width * 0.66 ? 'right' : 'center';

    const now = Date.now();
    if (
      side !== 'center' &&
      lastTapRef.current.side === side &&
      now - lastTapRef.current.time < 280
    ) {
      handleSeekDelta(side === 'left' ? -seekStep : seekStep);
      lastTapRef.current = { time: 0, side: 'center' };
    } else {
      lastTapRef.current = { time: now, side };
    }
  };

  const handleStagePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === 'touch' || useEmbedNow || isLocked || activeDrawer !== 'none') return;
    beginGesture(e.clientX, e.clientY, e.currentTarget.getBoundingClientRect());
  };

  const handleStagePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === 'touch') return;
    updateGesture(e.clientX, e.clientY, e.currentTarget.getBoundingClientRect());
  };

  const handleStagePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === 'touch') return;
    endGesture();
  };

  const handleStageWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    if (useEmbedNow || isLocked || activeDrawer !== 'none') return;
    if (!isPcProfile && !isTvProfile) return;
    const delta = e.deltaY < 0 ? 0.05 : -0.05;
    adjustVolume(delta);
    wakeHud();
  };

  const currentAudioLabel = isDubbedServerName(currentServer?.serverName || '')
    ? 'Thuyết minh'
    : 'Vietsub';

  const activeQualityLabel =
    hlsLevels.find((l) => l.index === activeQualityIdx)?.label.replace('Tự động (ABR)', 'Tự động') ||
    movie.quality ||
    'Auto';

  const progressPct = duration > 0 ? Math.min(100, (currentTime / duration) * 100) : 0;
  const hideCursorClass = !showHud && isPlaying && !useEmbedNow && !isLocked ? 'cursor-none' : '';
  const showSkipIntroBtn =
    !useEmbedNow &&
    !isLocked &&
    !isLoadingStream &&
    !hasSkippedIntro &&
    currentTime >= 2 &&
    currentTime < 180;

  return (
    <div
      ref={containerRef}
      data-tv-modal="true"
      data-player-profile={deviceProfile}
      onMouseMove={() => {
        if (!isLocked) wakeHud();
      }}
      onWheel={handleStageWheel}
      className={`fixed inset-0 z-50 bg-black select-none overflow-hidden flex ${hideCursorClass} ${
        useEmbedNow && activeEmbed ? 'flex-col' : 'items-center justify-center'
      }`}
    >
      {/* Ambient Cinema Glow */}
      {settings.extensions?.ambientGlow && (
        <div className="ambient-glow inset-0 bg-cyan-500/10 pointer-events-none"></div>
      )}

      {/* Video / Embed Stage */}
      {useEmbedNow && activeEmbed ? (
        <div className="order-2 relative flex-1 w-full min-h-0 bg-black z-10">
          {isEmbedLoading && (
            <div className="absolute inset-0 z-20 bg-slate-950/80 flex flex-col items-center justify-center gap-2.5 pointer-events-none">
              <div className="w-10 h-10 border-4 border-amber-400 border-t-transparent rounded-full animate-spin"></div>
              <span className="text-xs font-semibold text-white/90">
                Đang khởi tạo trình phát Embed ({currentServer?.serverName || 'Server'})...
              </span>
            </div>
          )}
          <iframe
            key={`${activeEmbed}_${safeEpIdx}_${activeServerIdx}_${embedReloadTick}`}
            src={appendAutoplayParam(activeEmbed)}
            referrerPolicy="unsafe-url"
            onLoad={() => setIsEmbedLoading(false)}
            className="w-full h-full border-0 bg-black"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen; web-share"
            allowFullScreen
            title={`${movie.title} - ${currentEpisodeItem?.name || `Tập ${currentEpisodeNum}`}`}
          />
        </div>
      ) : (
        <video
          ref={videoRef}
          playsInline
          style={{ filter: brightness > 0.9 ? undefined : `brightness(${brightness})` }}
          className={`transition-all duration-150 ${
            settings.extensions?.aiUpscale ? 'video-ai-upscale' : ''
          } ${
            aspectRatio === 'cover'
              ? 'w-full h-full object-cover'
              : aspectRatio === 'stretch'
              ? 'w-full h-full object-fill'
              : aspectRatio === '4:3'
              ? 'h-full max-w-full aspect-[4/3] mx-auto object-cover'
              : 'w-full h-full object-contain'
          }`}
        />
      )}

      {/* Hardware-independent Brightness Dimmer Overlay */}
      {brightness < 1 && (
        <div
          className="absolute inset-0 bg-black pointer-events-none z-[13] transition-opacity duration-75"
          style={{ opacity: Math.max(0, (1 - brightness) * 0.82) }}
        />
      )}

      {/* Left Edge Brightness Swipe Strip in Embed Mode */}
      {useEmbedNow && !isLocked && activeDrawer === 'none' && (
        <div
          onTouchStart={handleStageTouchStart}
          onTouchMove={handleStageTouchMove}
          onTouchEnd={handleStageTouchEnd}
          className="absolute left-0 top-16 bottom-20 w-10 z-[14] touch-none"
        />
      )}

      {/* Gesture Capture Overlay Layer */}
      {!useEmbedNow && (
        <div
          onTouchStart={handleStageTouchStart}
          onTouchMove={handleStageTouchMove}
          onTouchEnd={handleStageTouchEnd}
          onPointerDown={handleStagePointerDown}
          onPointerMove={handleStagePointerMove}
          onPointerUp={handleStagePointerUp}
          onClick={() => {
            if (activeDrawer !== 'none') {
              setActiveDrawer('none');
              return;
            }
            if (isLocked) {
              wakeHud();
              return;
            }
            setShowHud((prev) => {
              const next = !prev;
              if (next) wakeHud();
              return next;
            });
          }}
          onDoubleClick={(e) => {
            if (isLocked) return;
            const rect = e.currentTarget.getBoundingClientRect();
            const relX = e.clientX - rect.left;
            if (relX < rect.width * 0.35) handleSeekDelta(-seekStep);
            else if (relX > rect.width * 0.65) handleSeekDelta(seekStep);
            else toggleFullscreen();
          }}
          className="absolute inset-0 z-[12] touch-none cursor-pointer"
        />
      )}

      {/* Double-Tap Seek Ripples */}
      {showRippleLeft && (
        <div className="absolute left-8 top-1/2 -translate-y-1/2 z-30 pointer-events-none px-4 py-2.5 rounded-2xl bg-black/70 backdrop-blur-md text-white font-bold text-sm flex items-center gap-2">
          <i className="fa-solid fa-backward"></i> -{seekStep}s
        </div>
      )}
      {showRippleRight && (
        <div className="absolute right-8 top-1/2 -translate-y-1/2 z-30 pointer-events-none px-4 py-2.5 rounded-2xl bg-black/70 backdrop-blur-md text-white font-bold text-sm flex items-center gap-2">
          +{seekStep}s <i className="fa-solid fa-forward"></i>
        </div>
      )}

      {/* Gesture Feedback HUD (Brightness, Volume, or Horizontal Scrub Seek) */}
      {gestureOverlay && gestureOverlay.type === 'seek' ? (
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 z-40 pointer-events-none px-5 py-3 rounded-2xl bg-black/80 backdrop-blur-xl border border-amber-400/40 shadow-2xl text-center">
          <div className="text-base sm:text-lg font-extrabold text-amber-400 tabular-nums">
            {formatPlayerTime(gestureOverlay.value)}
          </div>
          <div className="text-xs font-semibold text-white mt-0.5">
            {(gestureOverlay.delta || 0) >= 0 ? `+${gestureOverlay.delta || 0}s` : `${gestureOverlay.delta}s`}
          </div>
        </div>
      ) : (
        gestureOverlay && (
          <div
            className={`absolute top-1/2 -translate-y-1/2 z-40 pointer-events-none flex flex-col items-center gap-2.5 px-4 py-4 rounded-2xl bg-black/75 backdrop-blur-xl border border-white/15 shadow-2xl ${
              gestureOverlay.type === 'brightness' ? 'left-8 sm:left-14' : 'right-8 sm:right-14'
            }`}
          >
            <i
              className={`fa-solid text-lg ${
                gestureOverlay.type === 'brightness'
                  ? 'fa-sun text-amber-400'
                  : gestureOverlay.value === 0
                  ? 'fa-volume-xmark text-rose-400'
                  : gestureOverlay.value < 0.5
                  ? 'fa-volume-low text-cyan-400'
                  : 'fa-volume-high text-cyan-400'
              }`}
            ></i>
            <div className="w-1.5 h-28 rounded-full bg-white/20 overflow-hidden flex flex-col justify-end">
              <div
                className={`w-full rounded-full transition-all duration-75 ${
                  gestureOverlay.type === 'brightness'
                    ? 'bg-gradient-to-t from-amber-500 to-yellow-300'
                    : 'bg-gradient-to-t from-indigo-500 to-cyan-400'
                }`}
                style={{ height: `${Math.round(gestureOverlay.value * 100)}%` }}
              ></div>
            </div>
            <span className="text-[11px] font-bold text-white tabular-nums">
              {Math.round(gestureOverlay.value * 100)}%
            </span>
            <span className="text-[9px] text-slate-300 uppercase tracking-wider">
              {gestureOverlay.type === 'brightness' ? 'Độ sáng' : 'Âm lượng'}
            </span>
          </div>
        )
      )}

      {/* Bilingual Subtitles Indicator Overlay */}
      {!useEmbedNow && settings.extensions?.bilingualSub && (
        <div className="absolute bottom-24 left-1/2 -translate-x-1/2 z-20 pointer-events-none px-3 py-1 rounded-xl bg-black/70 backdrop-blur-md border border-white/15 text-center max-w-[90%]">
          <p className="text-[11px] font-bold text-cyan-300 leading-tight">
            Phụ đề Song Ngữ Việt - Trung (AI Sub) đang bật
          </p>
          <p className="text-[10px] text-slate-300 leading-tight">
            双语字幕已开启 • {movie.title}
          </p>
        </div>
      )}

      {/* Skip Intro Floating Pill (Matching HH3D Reference) */}
      {showSkipIntroBtn && (
        <button
          type="button"
          onClick={() => {
            wakeHud();
            handleSkipIntro();
          }}
          className={`absolute ${
            isTvProfile ? 'bottom-32 right-6 px-4 py-2.5 text-sm' : 'bottom-28 right-4 px-3.5 py-2 text-xs'
          } z-30 rounded-xl bg-black/80 hover:bg-amber-400 hover:text-slate-950 text-amber-300 font-extrabold border border-amber-400/50 shadow-xl backdrop-blur-md flex items-center gap-2 transition-all tv-focus-target`}
        >
          <i className="fa-solid fa-forward-fast"></i>
          <span>Bỏ qua Mở đầu (+85s)</span>
        </button>
      )}

      {/* Auto-Next 5-Second Countdown Overlay */}
      {autoNextCountdown !== null && (
        <div className="absolute inset-0 z-35 bg-slate-950/85 backdrop-blur-md flex flex-col items-center justify-center p-4 text-center z-30">
          <p className="text-xs uppercase tracking-widest text-amber-400 font-bold mb-1">
            Tự động chuyển tập
          </p>
          <h4 className="text-base sm:text-xl font-bold text-white mb-4">
            {episodesList[currentEpIdx + 1]?.name || `Tập ${currentEpIdx + 2}`} sẽ phát sau{' '}
            <span className="text-amber-400 text-2xl">{autoNextCountdown}</span>s
          </h4>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setAutoNextCountdown(null)}
              className="px-4 py-2.5 rounded-xl bg-white/15 hover:bg-white/25 text-xs sm:text-sm font-semibold text-white tv-focus-target"
            >
              Hủy tự chuyển
            </button>
            <button
              type="button"
              data-tv-autofocus="true"
              onClick={() => {
                setAutoNextCountdown(null);
                setCurrentEpIdx((p) => p + 1);
              }}
              className="px-5 py-2.5 rounded-xl bg-amber-400 hover:bg-amber-300 text-slate-950 text-xs sm:text-sm font-extrabold shadow-lg flex items-center gap-2 tv-focus-target"
            >
              <i className="fa-solid fa-forward-step"></i>
              <span>Phát ngay</span>
            </button>
          </div>
        </div>
      )}

      {/* Loading Spinner */}
      {isLoadingStream && !useEmbedNow && (
        <div className="absolute inset-0 bg-black/50 backdrop-blur-[2px] flex flex-col items-center justify-center gap-3 z-20 pointer-events-none">
          <div className="w-11 h-11 border-4 border-amber-400 border-t-transparent rounded-full animate-spin"></div>
          <span className="text-xs font-semibold text-white/90">{streamInfo}</span>
        </div>
      )}

      {/* Error Fallback Banner */}
      {hasStreamError && !useEmbedNow && (
        <div className="absolute inset-0 bg-slate-950/90 flex flex-col items-center justify-center gap-3 z-30 p-4 text-center">
          <i className="fa-solid fa-triangle-exclamation text-amber-400 text-3xl"></i>
          <h4 className="text-sm font-bold text-white">
            Tất cả máy chủ HLS hiện tại đều gặp sự cố kết nối
          </h4>
          <p className="text-xs text-slate-400 max-w-sm">
            Vui lòng thử tải lại tập phim hoặc chuyển sang chế độ phát Embed
          </p>
          <div className="flex flex-wrap justify-center gap-2">
            {hasEmbed && (
              <button
                type="button"
                onClick={() => {
                  setHasStreamError(false);
                  setPreferEmbedMode(true);
                }}
                className="px-4 py-2 rounded-xl bg-indigo-600 text-white font-bold text-xs tv-focus-target"
              >
                Phát qua Embed Web
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                const v = videoRef.current;
                if (v) {
                  setHasStreamError(false);
                  v.load();
                  v.play().catch(() => {});
                }
              }}
              className="px-4 py-2 rounded-xl bg-white/15 text-white font-bold text-xs tv-focus-target"
            >
              Thử lại
            </button>
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl bg-rose-500/25 text-rose-200 font-bold text-xs tv-focus-target"
            >
              Đóng trình phát
            </button>
          </div>
        </div>
      )}

      {/* Muted Autoplay Unmute Pill */}
      {isMutedAutoplay && !useEmbedNow && !isLocked && (
        <button
          type="button"
          onClick={() => {
            if (videoRef.current) {
              videoRef.current.muted = false;
              videoRef.current.volume = volume > 0 ? volume : 1;
              if (volume === 0) setVolume(1);
            }
            setIsMutedAutoplay(false);
            onToast('Đã bật âm thanh');
          }}
          className="absolute top-16 right-5 z-30 px-3.5 py-2 rounded-full bg-amber-400 hover:bg-amber-300 text-slate-950 font-extrabold text-xs shadow-2xl flex items-center gap-2 tv-focus-target"
        >
          <i className="fa-solid fa-volume-xmark"></i>
          <span>Nhấn để bật âm thanh</span>
        </button>
      )}

      {/* Resume Toast */}
      {resumePrompt && !useEmbedNow && !isLocked && (
        <div className="absolute top-16 left-5 z-30 flex items-center gap-2 px-3.5 py-2 rounded-xl bg-black/80 border border-amber-400/50 shadow-2xl backdrop-blur-md">
          <i className="fa-solid fa-clock-rotate-left text-amber-400 text-xs"></i>
          <span className="text-xs text-white">
            Xem dở ở {formatPlayerTime(resumePrompt.time)}
          </span>
          <button
            type="button"
            onClick={resumePlayback}
            className="px-2.5 py-1 rounded-lg bg-amber-400 hover:bg-amber-300 text-slate-950 font-extrabold text-xs ml-1 tv-focus-target"
          >
            Xem tiếp
          </button>
          <button
            type="button"
            onClick={() => setResumePrompt(null)}
            className="text-slate-400 hover:text-white ml-1 text-xs"
          >
            <i className="fa-solid fa-xmark"></i>
          </button>
        </div>
      )}

      {/* Screen Lock Mode Floating Unlock Button */}
      {isLocked && (
        <div
          className={`absolute top-5 right-5 z-40 transition-opacity duration-300 ${
            showHud ? 'opacity-100' : 'opacity-25'
          }`}
        >
          <button
            type="button"
            onClick={() => {
              setIsLocked(false);
              wakeHud();
              onToast('Đã mở khóa màn hình trình phát');
            }}
            className="px-3.5 py-2 rounded-full bg-black/70 border border-white/25 text-white text-xs font-bold flex items-center gap-2 backdrop-blur-md shadow-lg tv-focus-target"
          >
            <i className="fa-solid fa-lock text-amber-400"></i>
            <span>Mở khóa</span>
          </button>
        </div>
      )}

      {/* ==========================================================
           TOP OVERLAY BAR (HH3D Cinema Style + Device Adaptive)
         ========================================================== */}
      {!isLocked && (
        <div
          className={`${
            useEmbedNow
              ? 'order-1 shrink-0 bg-slate-950/95 border-b border-white/10 px-4 sm:px-6 py-2.5'
              : `absolute top-0 inset-x-0 bg-gradient-to-b from-black/85 via-black/40 to-transparent px-3 sm:px-6 pt-[max(0.85rem,env(safe-area-inset-top))] pb-8 transition-opacity duration-300 ${
                  showHud || !isPlaying ? 'opacity-100' : 'opacity-0 pointer-events-none'
                }`
          } z-20 flex items-center justify-between gap-3`}
        >
          {/* Left: Back arrow + Title - Episode + Classification badge */}
          <div className="flex items-center gap-2.5 sm:gap-3 min-w-0">
            <button
              type="button"
              onClick={onClose}
              className={`${
                isTvProfile ? 'w-11 h-11' : 'w-9 h-9'
              } rounded-full hover:bg-white/15 flex items-center justify-center text-white transition-all shrink-0 tv-focus-target`}
              aria-label="Quay lại"
            >
              <i className={`fa-solid fa-arrow-left ${isTvProfile ? 'text-lg' : 'text-base'}`}></i>
            </button>

            <div className="flex items-center gap-2 sm:gap-2.5 min-w-0 flex-wrap">
              <h2
                className={`${
                  isTvProfile ? 'text-base sm:text-lg' : 'text-sm sm:text-base'
                } font-bold text-white truncate max-w-[48vw] sm:max-w-md`}
              >
                {movie.title} - {currentEpisodeItem?.name || `Tập ${currentEpIdx + 1}`}
              </h2>
              <span className="px-2.5 py-0.5 rounded-md bg-black/55 border border-white/10 text-[11px] font-semibold text-slate-200 flex items-center gap-1.5 shrink-0">
                <span
                  className={`w-0.5 h-3 rounded-full ${
                    useEmbedNow ? 'bg-indigo-400' : 'bg-amber-400'
                  }`}
                ></span>
                <span>
                  {useEmbedNow
                    ? 'Embed dự phòng'
                    : `Trình phát Tích hợp • ${activeQualityLabel}`}
                </span>
              </span>
              {useEmbedNow && serverGroups.some((g) => g.episodes.some((ep) => Boolean(ep.linkM3u8))) && (
                <button
                  type="button"
                  onClick={() => {
                    const idx = serverGroups.findIndex((g) =>
                      g.episodes.some((ep) => Boolean(ep.linkM3u8))
                    );
                    if (idx >= 0) {
                      setPreferEmbedMode(false);
                      switchToServer(idx);
                      onToast('Đã chuyển sang Trình phát Tích hợp (HLS)');
                    }
                  }}
                  className="px-2.5 py-0.5 rounded-md bg-amber-400 hover:bg-amber-300 text-slate-950 text-[11px] font-extrabold shrink-0 tv-focus-target"
                >
                  Dùng Trình phát Tích hợp
                </button>
              )}
            </div>
          </div>

          {/* Right: Reload / Prev / Lock / Speed-PiP / Fullscreen-TV */}
          <div className="flex items-center gap-1 sm:gap-2 shrink-0">
            {useEmbedNow && (
              <button
                type="button"
                onClick={() => {
                  setEmbedReloadTick((t) => t + 1);
                  onToast('Đang tải lại trình phát...');
                }}
                className="w-9 h-9 rounded-full hover:bg-white/15 flex items-center justify-center text-white transition-all tv-focus-target"
                title="Tải lại luồng phát"
              >
                <i className="fa-solid fa-rotate-right text-sm"></i>
              </button>
            )}

            <button
              type="button"
              disabled={safeEpIdx <= 0}
              onClick={() => {
                wakeHud();
                if (safeEpIdx > 0) {
                  const prevIdx = safeEpIdx - 1;
                  setCurrentEpIdx(prevIdx);
                  onToast(
                    `Chuyển về ${episodesList[prevIdx]?.name || `Tập ${prevIdx + 1}`}`
                  );
                }
              }}
              className={`${
                isTvProfile ? 'w-11 h-11' : 'w-9 h-9'
              } rounded-full hover:bg-white/15 flex items-center justify-center text-white disabled:opacity-35 transition-all tv-focus-target`}
              title="Tập trước (P)"
            >
              <i className="fa-solid fa-backward-step text-sm"></i>
            </button>

            <button
              type="button"
              onClick={() => {
                setIsLocked(true);
                setActiveDrawer('none');
                onToast('Đã khóa cảm ứng màn hình');
              }}
              className={`${
                isTvProfile ? 'w-11 h-11' : 'w-9 h-9'
              } rounded-full hover:bg-white/15 flex items-center justify-center text-white transition-all tv-focus-target`}
              title="Khóa màn hình"
            >
              <i className="fa-solid fa-lock text-sm"></i>
            </button>

            <button
              type="button"
              onClick={() => {
                wakeHud();
                if (isPcProfile || isTvProfile) {
                  setActiveDrawer((d) => (d === 'speed' ? 'none' : 'speed'));
                } else {
                  togglePictureInPicture();
                }
              }}
              className={`${
                isTvProfile ? 'w-11 h-11' : 'w-9 h-9'
              } rounded-full hover:bg-white/15 flex items-center justify-center text-white transition-all tv-focus-target`}
              title="Tốc độ / PiP"
            >
              <i className="fa-regular fa-window-restore text-sm"></i>
            </button>

            <button
              type="button"
              onClick={toggleFullscreen}
              className={`${
                isTvProfile ? 'w-11 h-11' : 'w-9 h-9'
              } rounded-full hover:bg-white/15 flex items-center justify-center text-white transition-all tv-focus-target`}
              title="Toàn màn hình / TV (F)"
            >
              <i className="fa-solid fa-tv text-sm"></i>
            </button>
          </div>
        </div>
      )}

      {/* ==========================================================
           CENTER TRANSPORT CONTROLS (Rewind | Play/Pause | Forward)
         ========================================================== */}
      {!isLocked && !useEmbedNow && !isLoadingStream && !hasStreamError && (
        <div
          className={`absolute inset-0 z-20 flex items-center justify-center ${
            isTvProfile ? 'gap-14' : 'gap-7 sm:gap-12'
          } pointer-events-none transition-opacity duration-300 ${
            showHud || !isPlaying ? 'opacity-100' : 'opacity-0'
          }`}
        >
          {/* Rewind seekStep */}
          <button
            type="button"
            onClick={() => {
              wakeHud();
              handleSeekDelta(-seekStep);
            }}
            className={`pointer-events-auto ${
              isTvProfile
                ? 'w-16 h-16'
                : 'w-12 h-12 sm:w-14 sm:h-14'
            } rounded-full bg-black/50 hover:bg-black/75 backdrop-blur-sm flex items-center justify-center text-white transition-all active:scale-90 tv-focus-target`}
            title={`Tua lùi ${seekStep}s (J)`}
          >
            <div className="relative flex items-center justify-center">
              <i className={`fa-solid fa-rotate-left ${isTvProfile ? 'text-3xl' : 'text-2xl'}`}></i>
              <span className="absolute text-[9px] font-extrabold mt-0.5">{seekStep}</span>
            </div>
          </button>

          {/* Center Play / Pause */}
          <button
            type="button"
            data-tv-autofocus="true"
            onClick={() => {
              wakeHud();
              togglePlay();
            }}
            className={`pointer-events-auto ${
              isTvProfile
                ? 'w-24 h-24'
                : 'w-16 h-16 sm:w-20 sm:h-20'
            } rounded-full bg-black/55 hover:bg-black/80 backdrop-blur-sm flex items-center justify-center text-white shadow-2xl transition-all active:scale-95 tv-focus-target`}
            title={isPlaying ? 'Tạm dừng (Space)' : 'Phát (Space)'}
          >
            <i
              className={`fa-solid ${isPlaying ? 'fa-pause' : 'fa-play'} ${
                isTvProfile ? 'text-4xl' : 'text-2xl sm:text-3xl'
              } ${isPlaying ? '' : 'ml-1'}`}
            ></i>
          </button>

          {/* Forward seekStep */}
          <button
            type="button"
            onClick={() => {
              wakeHud();
              handleSeekDelta(seekStep);
            }}
            className={`pointer-events-auto ${
              isTvProfile
                ? 'w-16 h-16'
                : 'w-12 h-12 sm:w-14 sm:h-14'
            } rounded-full bg-black/50 hover:bg-black/75 backdrop-blur-sm flex items-center justify-center text-white transition-all active:scale-90 tv-focus-target`}
            title={`Tua tới ${seekStep}s (L)`}
          >
            <div className="relative flex items-center justify-center">
              <i className={`fa-solid fa-rotate-right ${isTvProfile ? 'text-3xl' : 'text-2xl'}`}></i>
              <span className="absolute text-[9px] font-extrabold mt-0.5">{seekStep}</span>
            </div>
          </button>
        </div>
      )}

      {/* ==========================================================
           BOTTOM TIMELINE & 6-ACTION BAR (HH3D Reference Layout)
         ========================================================== */}
      {!isLocked && (
        <div
          className={`${
            useEmbedNow
              ? 'order-3 shrink-0 bg-slate-950/95 border-t border-white/10 px-4 sm:px-6 py-2'
              : `absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/95 via-black/70 to-transparent px-3 sm:px-6 pt-8 pb-[max(0.75rem,env(safe-area-inset-bottom))] space-y-2.5 sm:space-y-3 transition-opacity duration-300 ${
                  showHud || !isPlaying ? 'opacity-100' : 'opacity-0 pointer-events-none'
                }`
          } z-20`}
        >
          {/* Timeline Scrubber Row */}
          {!useEmbedNow && (
            <div className="flex items-center gap-3 relative">
              <span className={`${isTvProfile ? 'text-sm' : 'text-xs'} font-medium text-white tabular-nums shrink-0`}>
                {formatPlayerTime(currentTime)}
              </span>

              <div
                className="relative flex-1 flex items-center h-6 group"
                onMouseMove={(e) => {
                  if (!duration) return;
                  const rect = e.currentTarget.getBoundingClientRect();
                  const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                  setHoverSeekInfo({ time: pct * duration, pct: pct * 100 });
                }}
                onMouseLeave={() => setHoverSeekInfo(null)}
              >
                {hoverSeekInfo && (
                  <div
                    className="hidden sm:block absolute -top-7 -translate-x-1/2 px-2 py-0.5 rounded bg-black/85 border border-amber-400/50 text-[10px] font-bold text-amber-300 tabular-nums pointer-events-none"
                    style={{ left: `${hoverSeekInfo.pct}%` }}
                  >
                    {formatPlayerTime(hoverSeekInfo.time)}
                  </div>
                )}
                <div className={`w-full ${isTvProfile ? 'h-1.5' : 'h-1'} rounded-full bg-white/30 overflow-hidden`}>
                  <div
                    className="h-full bg-amber-400 rounded-full"
                    style={{ width: `${progressPct}%` }}
                  ></div>
                </div>
                <div
                  className={`absolute ${
                    isTvProfile ? 'w-4 h-4' : 'w-3.5 h-3.5'
                  } rounded-full bg-amber-400 shadow-[0_0_8px_rgba(251,191,36,0.9)] pointer-events-none -translate-x-1/2`}
                  style={{ left: `${progressPct}%` }}
                ></div>
                <input
                  type="range"
                  min={0}
                  max={duration || 100}
                  value={currentTime}
                  onChange={(e) => {
                    wakeHud();
                    const val = parseFloat(e.target.value);
                    setCurrentTime(val);
                    if (videoRef.current) videoRef.current.currentTime = val;
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'ArrowLeft') {
                      e.preventDefault();
                      e.stopPropagation();
                      wakeHud();
                      handleSeekDelta(-seekStep);
                    } else if (e.key === 'ArrowRight') {
                      e.preventDefault();
                      e.stopPropagation();
                      wakeHud();
                      handleSeekDelta(seekStep);
                    }
                  }}
                  className="absolute inset-0 w-full opacity-0 cursor-pointer tv-focus-target rounded-full"
                  aria-label="Thanh thời gian phát video"
                />
              </div>

              <span className={`${isTvProfile ? 'text-sm' : 'text-xs'} font-medium text-white/90 tabular-nums shrink-0`}>
                {formatPlayerTime(duration)}
              </span>
            </div>
          )}

          {/* 6 Bottom Control Actions */}
          <div className="grid grid-cols-6 gap-1 items-center text-center">
            {/* 1. Aspect Ratio */}
            <button
              type="button"
              onClick={() => {
                wakeHud();
                cycleAspectRatio();
              }}
              className="flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl text-white/90 hover:text-white hover:bg-white/10 transition-all tv-focus-target"
            >
              <i className={`fa-solid fa-expand ${isTvProfile ? 'text-base' : 'text-sm'}`}></i>
              <span className={`${isTvProfile ? 'text-xs' : 'text-[10px] sm:text-xs'} font-medium truncate max-w-full px-1`}>
                {ASPECT_LABELS[aspectRatio]}
              </span>
            </button>

            {/* 2. Adaptive Slot: Orientation Lock (Mobile/Tablet) or Quality Selector (TV/PC) */}
            {isTvProfile || isPcProfile ? (
              <button
                type="button"
                onClick={() => {
                  wakeHud();
                  setActiveDrawer((d) => (d === 'quality' ? 'none' : 'quality'));
                }}
                className={`flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl transition-all tv-focus-target ${
                  activeDrawer === 'quality'
                    ? 'text-amber-400 bg-white/10'
                    : 'text-white/90 hover:text-white hover:bg-white/10'
                }`}
              >
                <i className={`fa-solid fa-sliders ${isTvProfile ? 'text-base' : 'text-sm'}`}></i>
                <span className={`${isTvProfile ? 'text-xs' : 'text-[10px] sm:text-xs'} font-medium truncate max-w-full px-1`}>
                  {activeQualityLabel}
                </span>
              </button>
            ) : (
              <button
                type="button"
                onClick={() => {
                  wakeHud();
                  toggleOrientationLock();
                }}
                className={`flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl transition-all tv-focus-target ${
                  isPortraitLocked
                    ? 'text-amber-400 bg-white/10'
                    : 'text-white/90 hover:text-white hover:bg-white/10'
                }`}
              >
                <i className="fa-solid fa-mobile-screen text-sm"></i>
                <span className="text-[10px] sm:text-xs font-medium truncate max-w-full px-1">
                  {isPortraitLocked ? 'Đang khóa đứng' : 'Khóa đứng'}
                </span>
              </button>
            )}

            {/* 3. Server Source (Nguồn) */}
            <button
              type="button"
              onClick={() => {
                wakeHud();
                setActiveDrawer((d) => (d === 'sources' ? 'none' : 'sources'));
              }}
              className={`flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl transition-all tv-focus-target ${
                activeDrawer === 'sources'
                  ? 'text-amber-400 bg-white/10'
                  : 'text-white/90 hover:text-white hover:bg-white/10'
              }`}
            >
              <i className={`fa-solid fa-layer-group ${isTvProfile ? 'text-base' : 'text-sm'}`}></i>
              <span className={`${isTvProfile ? 'text-xs' : 'text-[10px] sm:text-xs'} font-medium`}>
                Nguồn
              </span>
            </button>

            {/* 4. Audio Mode (Thuyết minh / Vietsub) */}
            <button
              type="button"
              onClick={() => {
                wakeHud();
                toggleAudioModeQuick();
              }}
              className="flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl text-white/90 hover:text-white hover:bg-white/10 transition-all tv-focus-target"
            >
              <i className={`fa-solid fa-closed-captioning ${isTvProfile ? 'text-base' : 'text-sm'}`}></i>
              <span className={`${isTvProfile ? 'text-xs' : 'text-[10px] sm:text-xs'} font-medium truncate max-w-full px-1`}>
                {currentAudioLabel}
              </span>
            </button>

            {/* 5. Next Episode (Tập sau) */}
            <button
              type="button"
              disabled={currentEpIdx >= totalEpCount - 1}
              onClick={() => {
                wakeHud();
                if (currentEpIdx < totalEpCount - 1) {
                  setCurrentEpIdx((p) => p + 1);
                  onToast(
                    `Chuyển sang ${episodesList[currentEpIdx + 1]?.name || `Tập ${currentEpIdx + 2}`}`
                  );
                }
              }}
              className="flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl text-white/90 hover:text-white hover:bg-white/10 disabled:opacity-40 transition-all tv-focus-target"
            >
              <i className={`fa-solid fa-forward-step ${isTvProfile ? 'text-base' : 'text-sm'}`}></i>
              <span className={`${isTvProfile ? 'text-xs' : 'text-[10px] sm:text-xs'} font-medium`}>
                Tập sau
              </span>
            </button>

            {/* 6. Episode List Drawer (Danh sách tập) */}
            <button
              type="button"
              onClick={() => {
                wakeHud();
                setActiveDrawer((d) => (d === 'episodes' ? 'none' : 'episodes'));
              }}
              className={`flex flex-col items-center justify-center gap-1 py-1.5 rounded-xl transition-all tv-focus-target ${
                activeDrawer === 'episodes'
                  ? 'text-amber-400 bg-white/10'
                  : 'text-white/90 hover:text-white hover:bg-white/10'
              }`}
            >
              <i className={`fa-solid fa-list-ul ${isTvProfile ? 'text-base' : 'text-sm'}`}></i>
              <span className={`${isTvProfile ? 'text-xs' : 'text-[10px] sm:text-xs'} font-medium truncate max-w-full px-1`}>
                Danh sách tập
              </span>
            </button>
          </div>
        </div>
      )}

      {/* Overlay Drawer (Episodes / Sources / Quality / Speed) */}
      {!isLocked && (
        <PlayerDrawer
          activeDrawer={activeDrawer}
          deviceProfile={deviceProfile}
          movieId={movie.id}
          episodesList={episodesList}
          totalEpCount={totalEpCount}
          currentEpIdx={currentEpIdx}
          epRanges={epRanges}
          epRangeIdx={epRangeIdx}
          currentRange={currentRange}
          serverGroups={serverGroups}
          activeServerIdx={activeServerIdx}
          hasM3u8={hasM3u8}
          hasEmbed={hasEmbed}
          useEmbedNow={useEmbedNow}
          playbackSpeed={playbackSpeed}
          hlsLevels={hlsLevels}
          activeQualityIdx={activeQualityIdx}
          offlineMap={offlineMap}
          onClose={() => setActiveDrawer('none')}
          onSelectEpRange={setEpRangeIdx}
          onSelectEpisode={(realIdx) => {
            setCurrentEpIdx(realIdx);
            setActiveDrawer('none');
          }}
          onToggleEmbedMode={() => setPreferEmbedMode((p) => !p)}
          onSelectServer={(idx) => {
            failedServersRef.current.clear();
            setPreferEmbedMode(false);
            switchToServer(idx);
            setActiveDrawer('none');
            onToast(`Đã chuyển máy chủ: ${serverGroups[idx].serverName}`);
          }}
          onSelectSpeed={(spd) => {
            setPlaybackSpeed(spd);
            if (videoRef.current) videoRef.current.playbackRate = spd;
            setActiveDrawer('none');
            onToast(`Tốc độ phát: ${spd}x`);
          }}
          onSelectQuality={handleSelectQuality}
        />
      )}
    </div>
  );
};
