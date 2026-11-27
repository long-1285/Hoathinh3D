import React, { useEffect, useMemo, useState } from 'react';
import {
  AppSettings,
  DeviceMode,
  DonghuaMovie,
  DonghuaSource,
  FavoriteEpisodeMeta,
  GenreItem,
  OfflineEpisodeRecord,
  SettingsSubtab,
  UserProfile,
  WatchProgress
} from './types';
import { StorageService } from './services/storage.service';
import { ApiService } from './services/api.service';
import { FavoritesMonitorService, OfflineCacheService } from './services/offline.service';
import { GENRES_DATA } from './constants/initialData';
import { Header } from './components/navigation/Header';
import { BottomLiquidNav, TabType } from './components/navigation/BottomLiquidNav';
import { HomeView } from './components/home/HomeView';
import { ScheduleView } from './components/schedule/ScheduleView';
import { HistoryView } from './components/history/HistoryView';
import { FavoritesView } from './components/favorites/FavoritesView';
import { SettingsView } from './components/settings/SettingsView';
import { VideoPlayer } from './components/player/VideoPlayer';
import { MovieDetailModal } from './components/catalog/MovieDetailModal';
import { Toast } from './components/common/Toast';
import { GoogleLoginModal } from './components/common/GoogleLoginModal';
import { scoreMovieMatch } from './utils/vietnamese';
import { useTVRemote } from './hooks/useTVRemote';

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabType>('home');
  const [settingsSubtab, setSettingsSubtab] = useState<SettingsSubtab>('account');
  const [sources, setSources] = useState<DonghuaSource[]>([]);
  const [activeSourceId, setActiveSourceId] = useState<string>('YanHH3D');
  const [genres, setGenres] = useState<GenreItem[]>(GENRES_DATA);
  const [selectedGenre, setSelectedGenre] = useState<string>('Tất cả');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [favoriteMetaMap, setFavoriteMetaMap] = useState<Record<string, FavoriteEpisodeMeta>>(() =>
    StorageService.getFavoriteMetaMap()
  );
  const [offlineEpisodes, setOfflineEpisodes] = useState<Record<string, OfflineEpisodeRecord>>(() =>
    StorageService.getOfflineEpisodes()
  );
  const [isCheckingFavUpdates, setIsCheckingFavUpdates] = useState<boolean>(false);
  const [watchHistory, setWatchHistory] = useState<Record<string, WatchProgress>>({});
  const [allMovies, setAllMovies] = useState<DonghuaMovie[]>([]);
  const [detailMovie, setDetailMovie] = useState<DonghuaMovie | null>(null);
  const [selectedMovie, setSelectedMovie] = useState<DonghuaMovie | null>(null);
  const [playerInitialEp, setPlayerInitialEp] = useState<number>(1);
  const [playerInitialServerIdx, setPlayerInitialServerIdx] = useState<number | undefined>(undefined);
  const [settings, setSettings] = useState<AppSettings>(() => StorageService.getSettings());
  const [user, setUser] = useState<UserProfile | null>(null);
  const [isGoogleModalOpen, setIsGoogleModalOpen] = useState<boolean>(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [isLoadingFeed, setIsLoadingFeed] = useState<boolean>(false);
  const [isLoadingGenre, setIsLoadingGenre] = useState<boolean>(false);
  const [activeGenreMovies, setActiveGenreMovies] = useState<DonghuaMovie[] | null>(null);
  const [isSearching, setIsSearching] = useState<boolean>(false);
  const [remoteSearchResults, setRemoteSearchResults] = useState<DonghuaMovie[]>([]);
  const [viewportWidth, setViewportWidth] = useState<number>(
    typeof window !== 'undefined' ? window.innerWidth : 1280
  );

  // Sleep Timer state
  const [sleepTimerMinutes, setSleepTimerMinutes] = useState<number>(0);
  const [sleepRemainingSec, setSleepRemainingSec] = useState<number | null>(null);
  const [sleepPauseTick, setSleepPauseTick] = useState<number>(0);

  // Spatial D-Pad navigation when browsing outside the VideoPlayer
  useTVRemote({
    spatialNavigation: true,
    enabled: !selectedMovie && !detailMovie && !isGoogleModalOpen,
    onBack: () => {
      if (activeTab !== 'home') {
        setActiveTab('home');
      }
    }
  });

  // Compute resolved device layout
  const resolvedDevice: Exclude<DeviceMode, 'auto'> = useMemo(() => {
    if (settings.deviceMode !== 'auto') return settings.deviceMode;
    if (viewportWidth < 640) return 'mobile';
    if (viewportWidth < 1100) return 'tablet';
    return 'tv';
  }, [settings.deviceMode, viewportWidth]);

  useEffect(() => {
    const onResize = () => setViewportWidth(window.innerWidth);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // Apply theme, blur, and device attributes to document
  useEffect(() => {
    document.body.classList.remove('theme-aqua', 'theme-sakura', 'theme-dark');
    document.documentElement.classList.remove('dark');

    if (settings.theme === 'aqua') {
      document.body.classList.add('theme-aqua');
    } else if (settings.theme === 'sakura') {
      document.body.classList.add('theme-sakura');
    } else if (settings.theme === 'dark') {
      document.body.classList.add('theme-dark');
      document.documentElement.classList.add('dark');
    }

    document.documentElement.style.setProperty('--glass-blur', `${settings.glassBlur || 24}px`);
    document.body.setAttribute('data-device-mode', settings.deviceMode);
    document.body.setAttribute('data-resolved-device', resolvedDevice);
  }, [settings.theme, settings.glassBlur, settings.deviceMode, resolvedDevice]);

  // Initialize data on mount & fetch live Donghua feed
  useEffect(() => {
    const loadedSources = StorageService.getSources();
    setSources(loadedSources);

    const savedSettings = StorageService.getSettings();
    setSettings(savedSettings);

    const preferredExists = loadedSources.find(
      (s) => s.id === savedSettings.preferredSource && s.enabled
    );
    const initialSource = preferredExists
      ? preferredExists.id
      : loadedSources.find((s) => s.enabled)?.id || loadedSources[0]?.id || 'YanHH3D';
    setActiveSourceId(initialSource);

    setFavorites(StorageService.getFavorites());
    setWatchHistory(StorageService.getHistory());
    setUser(StorageService.getUser());

    const baseMovies = StorageService.getAllMovies();
    setAllMovies(baseMovies);

    loadLiveDonghuaFeed(initialSource);
  }, []);

  // Sleep Timer countdown effect
  useEffect(() => {
    if (sleepRemainingSec === null) return;
    if (sleepRemainingSec <= 0) {
      setSleepRemainingSec(null);
      setSleepTimerMinutes(0);
      setSleepPauseTick((t) => t + 1);
      triggerToast('Hẹn giờ ngủ: Đã tự động tạm dừng phát phim!');
      return;
    }

    const timer = window.setTimeout(() => {
      setSleepRemainingSec((prev) => (prev !== null ? prev - 1 : null));
    }, 1000);

    return () => window.clearTimeout(timer);
  }, [sleepRemainingSec]);

  const handleSetSleepTimer = (minutes: number) => {
    setSleepTimerMinutes(minutes);
    if (minutes <= 0) {
      setSleepRemainingSec(null);
      triggerToast('Đã tắt hẹn giờ ngủ');
    } else {
      setSleepRemainingSec(minutes * 60);
      triggerToast(`Đã đặt hẹn giờ tắt phim sau ${minutes} phút!`);
    }
  };

  const loadLiveDonghuaFeed = async (sourceId = activeSourceId) => {
    setIsLoadingFeed(true);
    try {
      const [liveItems, sourceGenres] = await Promise.all([
        ApiService.fetchDonghuaList(1, 30, sourceId),
        ApiService.fetchCategoriesForSource(sourceId)
      ]);

      if (sourceGenres && sourceGenres.length > 0) {
        setGenres(sourceGenres);
      } else {
        setGenres(GENRES_DATA);
      }

      if (liveItems && liveItems.length > 0) {
        const customMovies = StorageService.getCustomMovies();
        const map = new Map<string, DonghuaMovie>();
        customMovies.forEach((m) => map.set(m.slug || m.id, m));
        liveItems.forEach((m) => {
          const key = m.slug || m.id;
          if (!map.has(key)) map.set(key, m);
        });
        if (sourceId === 'YanHH3D') {
          StorageService.getAllMovies().forEach((m) => {
            const key = m.slug || m.id;
            if (!map.has(key)) map.set(key, m);
          });
        }

        const mergedCatalog = Array.from(map.values());
        setAllMovies(mergedCatalog);
        setActiveGenreMovies(null);
        const srcLabel = sources.find((s) => s.id === sourceId)?.name || sourceId;
        triggerToast(`Đã nạp ${liveItems.length} phim từ nguồn ${srcLabel}`);

        const currentFavs = StorageService.getFavorites();
        if (currentFavs.length > 0) {
          FavoritesMonitorService.checkNewEpisodes(currentFavs, mergedCatalog, 3)
            .then(({ metaMap, newlyUpdatedTitles }) => {
              setFavoriteMetaMap(metaMap);
              if (newlyUpdatedTitles.length > 0) {
                triggerToast(`Tủ phim có tập mới: ${newlyUpdatedTitles.slice(0, 2).join(', ')}`);
              }
            })
            .catch(() => {});
        }
      }
    } catch (err) {
      console.warn('Không thể tải feed trực tiếp, giữ catalog tĩnh:', err);
    } finally {
      setIsLoadingFeed(false);
    }
  };

  const triggerToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  const handleUpdateSettings = (newSettings: Partial<AppSettings>) => {
    const updated = StorageService.saveSettings(newSettings);
    setSettings(updated);
    if (updated.cloudAutoSync && user) {
      StorageService.saveCloudSnapshot();
    }
  };

  const handleSelectSource = (sourceId: string) => {
    setActiveSourceId(sourceId);
    setSelectedGenre('Tất cả');
    setActiveGenreMovies(null);
    handleUpdateSettings({ preferredSource: sourceId });
    const src = sources.find((s) => s.id === sourceId);
    triggerToast(`Đã kết nối nguồn ưu tiên: ${src?.name || sourceId}`);
    loadLiveDonghuaFeed(sourceId);
  };

  const handleToggleFavorite = (id: string, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    const isFav = StorageService.toggleFavorite(id);
    setFavorites(StorageService.getFavorites());
    if (isFav) {
      const targetMovie = allMovies.find((m) => m.id === id);
      setFavoriteMetaMap(
        StorageService.markFavoriteEpisodeSeen(id, targetMovie?.latestEp || 1)
      );
    }
    triggerToast(isFav ? 'Đã thêm vào tủ phim ❤️' : 'Đã xóa khỏi tủ phim');
  };

  const handleCheckFavoritesNewEpisodes = async () => {
    if (favorites.length === 0) return;
    setIsCheckingFavUpdates(true);
    try {
      const { metaMap, updatedMovies, newlyUpdatedTitles } =
        await FavoritesMonitorService.checkNewEpisodes(favorites, allMovies, 6);
      setFavoriteMetaMap(metaMap);
      if (updatedMovies.length > 0) {
        setAllMovies((prev) =>
          prev.map((m) => updatedMovies.find((u) => u.id === m.id) || m)
        );
      }
      const totalNew = Object.values(metaMap).reduce((s, item) => s + (item.newEpCount || 0), 0);
      if (newlyUpdatedTitles.length > 0) {
        triggerToast(`Có tập mới: ${newlyUpdatedTitles.slice(0, 2).join(', ')}`);
      } else if (totalNew > 0) {
        triggerToast(`Tủ phim đang có ${totalNew} tập mới chưa xem!`);
      } else {
        triggerToast('Tất cả phim yêu thích đã cập nhật mới nhất!');
      }
    } finally {
      setIsCheckingFavUpdates(false);
    }
  };

  const handlePlayOfflineRecord = (rec: OfflineEpisodeRecord) => {
    const existing = allMovies.find((m) => m.id === rec.donghuaId);
    const offlineServer = {
      serverName: `${rec.serverName} (Offline)`,
      episodes: [
        {
          name: rec.episodeName,
          slug: `tap-${rec.episodeNum}`,
          linkM3u8: rec.streamUrl
        }
      ]
    };
    const movieToPlay: DonghuaMovie = existing
      ? { ...existing, serverGroups: [offlineServer] }
      : {
          id: rec.donghuaId,
          title: rec.title,
          originalTitle: 'Phát từ bộ nhớ đệm Offline',
          studio: 'Offline Cache',
          latestEp: rec.episodeNum,
          totalEp: rec.episodeNum,
          rating: 9.8,
          views: 'Offline',
          viewsNum: 0,
          quality: 'Offline HLS',
          audioModes: ['Thuyết Minh'],
          sources: ['Offline'],
          genres: ['Tu Tiên'],
          scheduleDay: 'Chủ Nhật',
          scheduleTime: '00:00',
          poster: rec.poster,
          streamIndex: 0,
          serverGroups: [offlineServer]
        };
    handleOpenPlayer(movieToPlay, rec.episodeNum, 0);
  };

  const handleRemoveOfflineRecord = async (key: string) => {
    await OfflineCacheService.removeOfflineEpisode(key);
    setOfflineEpisodes(StorageService.getOfflineEpisodes());
    triggerToast('Đã xóa bản lưu offline');
  };

  const handleClearHistory = () => {
    StorageService.clearHistory();
    setWatchHistory({});
    triggerToast('Đã xóa toàn bộ lịch sử xem');
  };

  const handleOpenPlayer = (movie: DonghuaMovie, ep?: number, serverIdx?: number) => {
    setDetailMovie(null);
    setSelectedMovie(movie);
    setPlayerInitialEp(ep || 1);
    setPlayerInitialServerIdx(serverIdx);
  };

  const runLiveSearch = async (query: string) => {
    const clean = query.trim();
    if (!clean) {
      setRemoteSearchResults([]);
      setIsSearching(false);
      return;
    }
    setIsSearching(true);
    try {
      const liveSearchResults = await ApiService.searchDonghua(clean);
      setRemoteSearchResults(liveSearchResults || []);
      if (liveSearchResults && liveSearchResults.length > 0) {
        setAllMovies((prev) => {
          const map = new Map<string, DonghuaMovie>();
          prev.forEach((m) => map.set(m.slug || m.id, m));
          liveSearchResults.forEach((m) => {
            const key = m.slug || m.id;
            const existing = map.get(key);
            if (!existing) {
              map.set(key, m);
            } else {
              map.set(key, {
                ...existing,
                yanPostId: existing.yanPostId || m.yanPostId,
                yanUrl: existing.yanUrl || m.yanUrl,
                wpSources: [...(existing.wpSources || []), ...(m.wpSources || [])],
                sources: Array.from(new Set([...existing.sources, ...m.sources]))
              });
            }
          });
          return Array.from(map.values());
        });
      }
    } finally {
      setIsSearching(false);
    }
  };

  const handleSearchChange = (query: string) => {
    setSearchQuery(query);
    if (query.trim().length > 0 && activeTab !== 'home') {
      setActiveTab('home');
    }
    if (!query.trim()) {
      setRemoteSearchResults([]);
      setIsSearching(false);
    }
  };

  // Live search when query changes
  useEffect(() => {
    const clean = searchQuery.trim();
    if (!clean) {
      setRemoteSearchResults([]);
      setIsSearching(false);
      return;
    }

    const timer = setTimeout(() => {
      runLiveSearch(clean);
    }, 350);

    return () => clearTimeout(timer);
  }, [searchQuery]);

  // Fetch category / status movies from active source when selecting a taxonomy chip
  useEffect(() => {
    if (selectedGenre === 'Tất cả') {
      setActiveGenreMovies(null);
      setIsLoadingGenre(false);
      return;
    }
    const genreObj = genres.find((g) => g.id === selectedGenre);
    if (
      !genreObj?.sourceSlug &&
      !genreObj?.wpCategoryId &&
      !genreObj?.wpStatusId &&
      !genreObj?.yanCategoryId &&
      !genreObj?.yanStatusId
    ) {
      setActiveGenreMovies(null);
      setIsLoadingGenre(false);
      return;
    }

    let cancelled = false;
    setIsLoadingGenre(true);
    setActiveGenreMovies(null);
    ApiService.fetchBySourceAndGenre(activeSourceId, genreObj, 1, 30)
      .then((fetched) => {
        if (cancelled) return;
        setActiveGenreMovies(fetched);
      })
      .catch(() => {
        if (!cancelled) setActiveGenreMovies([]);
      })
      .finally(() => {
        if (!cancelled) setIsLoadingGenre(false);
      });

    return () => {
      cancelled = true;
    };
  }, [selectedGenre, genres, activeSourceId]);

  // Filter movies based on active source, selected genre chip and Vietnamese ranked search
  const filteredMovies = useMemo(() => {
    const cleanSearch = searchQuery.trim();
    if (cleanSearch) {
      const poolMap = new Map<string, DonghuaMovie>();
      remoteSearchResults.forEach((m) => poolMap.set(m.slug || m.id, m));
      allMovies.forEach((m) => {
        const key = m.slug || m.id;
        if (!poolMap.has(key)) poolMap.set(key, m);
      });

      return Array.from(poolMap.values())
        .map((movie) => ({ movie, score: scoreMovieMatch(movie, cleanSearch) }))
        .filter((x) => x.score > 0)
        .sort((a, b) => b.score - a.score || (b.movie.viewsNum || 0) - (a.movie.viewsNum || 0))
        .map((x) => x.movie);
    }

    if (selectedGenre !== 'Tất cả') {
      if (activeGenreMovies !== null && activeGenreMovies.length > 0) {
        return activeGenreMovies;
      }
      const needle = selectedGenre.toLowerCase();
      return allMovies.filter((movie) => {
        const matchesStatus = movie.statusLabel?.toLowerCase() === needle;
        const matchesAudio =
          (needle.includes('thuyết minh') || needle.includes('lồng tiếng')) &&
          movie.audioModes.some((a) => a.toLowerCase().includes('thuyết'));
        const matchesGenre = movie.genres.some((g) => g.toLowerCase().includes(needle));
        return matchesStatus || matchesAudio || matchesGenre;
      });
    }

    return allMovies;
  }, [allMovies, activeGenreMovies, remoteSearchResults, selectedGenre, searchQuery]);

  return (
    <div className="min-h-screen text-main flex flex-col relative">
      {/* Liquid Ambient Floating Orbs */}
      <div
        className="pointer-events-none fixed inset-0 overflow-hidden z-0 transition-opacity duration-500"
        style={{ opacity: settings.ambientOrbs ? 1 : 0 }}
      >
        <div className="liquid-orb orb-1"></div>
        <div className="liquid-orb orb-2"></div>
        <div className="liquid-orb orb-3"></div>
      </div>

      {/* Toast Notification */}
      <Toast message={toastMessage} />

      {/* Google Login Modal */}
      <GoogleLoginModal
        isOpen={isGoogleModalOpen}
        onClose={() => setIsGoogleModalOpen(false)}
        onConfirm={(newUser) => {
          StorageService.saveUser(newUser);
          const syncTs = StorageService.saveCloudSnapshot();
          const syncedUser = { ...newUser, lastSync: syncTs };
          StorageService.saveUser(syncedUser);
          setUser(syncedUser);
          triggerToast(`Đã đăng nhập Google: ${syncedUser.name} & đồng bộ Cloud!`);
        }}
      />

      {/* Header */}
      <Header
        sources={sources}
        activeSourceId={activeSourceId}
        onSelectSource={handleSelectSource}
        searchQuery={searchQuery}
        onSearchChange={handleSearchChange}
        onSearchSubmit={() => {
          if (activeTab !== 'home') setActiveTab('home');
          runLiveSearch(searchQuery);
        }}
        onOpenSettings={() => setActiveTab('settings')}
        onNavigateHome={() => setActiveTab('home')}
      />

      {/* Main Content Area */}
      <main
        id="main-app-container"
        className="relative z-10 flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 transition-all duration-300"
      >
        {activeTab === 'home' && (
          <div className="space-y-4">
            {isLoadingFeed && (
              <div className="flex items-center justify-center gap-2 py-1.5 px-3 rounded-full bg-indigo-500/10 border border-indigo-500/20 text-indigo-600 dark:text-cyan-300 text-xs animate-pulse mx-auto w-fit">
                <i className="fa-solid fa-cloud-arrow-down text-xs"></i>
                <span>Đang kết nối cụm kho hoạt hình 3D...</span>
              </div>
            )}

            <HomeView
              movies={filteredMovies}
              genres={genres}
              sources={sources}
              selectedGenre={selectedGenre}
              onSelectGenre={setSelectedGenre}
              selectedSourceId={activeSourceId}
              onSelectSource={handleSelectSource}
              favorites={favorites}
              watchHistory={watchHistory}
              onSelectMovie={(m) => setDetailMovie(m)}
              onQuickPlayMovie={(m, ep) => handleOpenPlayer(m, ep)}
              onToggleFavorite={handleToggleFavorite}
              onClearHistory={handleClearHistory}
              resolvedDevice={resolvedDevice}
              searchQuery={searchQuery}
              isSearching={isSearching}
              isLoadingGenre={isLoadingGenre}
              onClearSearch={() => handleSearchChange('')}
            />
          </div>
        )}

        {activeTab === 'schedule' && (
          <ScheduleView movies={allMovies} onSelectMovie={(m) => setDetailMovie(m)} />
        )}

        {activeTab === 'history' && (
          <HistoryView
            watchHistory={watchHistory}
            movies={allMovies}
            onSelectMovie={(m, ep) => handleOpenPlayer(m, ep)}
            onClearHistory={handleClearHistory}
          />
        )}

        {activeTab === 'favorites' && (
          <FavoritesView
            favorites={favorites}
            movies={allMovies}
            favoriteMetaMap={favoriteMetaMap}
            offlineEpisodes={offlineEpisodes}
            isCheckingUpdates={isCheckingFavUpdates}
            onCheckNewEpisodes={handleCheckFavoritesNewEpisodes}
            onSelectMovie={(m) => setDetailMovie(m)}
            onPlayOfflineEpisode={handlePlayOfflineRecord}
            onRemoveOfflineEpisode={handleRemoveOfflineRecord}
            onToggleFavorite={handleToggleFavorite}
            resolvedDevice={resolvedDevice}
          />
        )}

        {activeTab === 'settings' && (
          <SettingsView
            settings={settings}
            activeSubtab={settingsSubtab}
            onChangeSubtab={setSettingsSubtab}
            onUpdateSettings={handleUpdateSettings}
            sources={sources}
            onRefreshSources={() => setSources(StorageService.getSources())}
            onSelectPreferredSource={handleSelectSource}
            movies={allMovies}
            watchHistory={watchHistory}
            favorites={favorites}
            onRefreshMovies={() => setAllMovies(StorageService.getAllMovies())}
            user={user}
            onUpdateUser={(u) => {
              StorageService.saveUser(u);
              setUser(u);
            }}
            onOpenGoogleLogin={() => setIsGoogleModalOpen(true)}
            onNavigateTab={(tab) => setActiveTab(tab)}
            onOpenSamplePlayer={() => {
              if (allMovies.length > 0) handleOpenPlayer(allMovies[0]);
            }}
            sleepTimerMinutes={sleepTimerMinutes}
            sleepRemainingSec={sleepRemainingSec}
            onSetSleepTimer={handleSetSleepTimer}
            onToast={triggerToast}
          />
        )}
      </main>

      {/* Movie Detail Modal (Hh3dDetailContainer + Backdrop) */}
      {detailMovie && (
        <MovieDetailModal
          movie={detailMovie}
          isFavorite={favorites.includes(detailMovie.id)}
          progress={watchHistory[detailMovie.id]}
          defaultAudio={settings.defaultAudio}
          onClose={() => setDetailMovie(null)}
          onPlayEpisode={(m, ep, srvIdx) => handleOpenPlayer(m, ep, srvIdx)}
          onToggleFavorite={(id) => handleToggleFavorite(id)}
          onMarkFavoriteSeen={(id, latestEp) => {
            setFavoriteMetaMap(StorageService.markFavoriteEpisodeSeen(id, latestEp));
          }}
          onOfflineUpdated={() => setOfflineEpisodes(StorageService.getOfflineEpisodes())}
          onToast={triggerToast}
        />
      )}

      {/* Video Player Modal */}
      {selectedMovie && (
        <VideoPlayer
          movie={selectedMovie}
          initialEpisode={playerInitialEp}
          initialServerIdx={playerInitialServerIdx}
          sources={sources}
          settings={settings}
          resolvedDevice={resolvedDevice}
          sleepPauseTick={sleepPauseTick}
          onClose={() => {
            setSelectedMovie(null);
            setWatchHistory(StorageService.getHistory());
          }}
          onToast={triggerToast}
        />
      )}

      {/* Bottom Liquid Navigation */}
      <BottomLiquidNav
        activeTab={activeTab}
        onChangeTab={setActiveTab}
        favoritesCount={favorites.length}
      />
    </div>
  );
};
