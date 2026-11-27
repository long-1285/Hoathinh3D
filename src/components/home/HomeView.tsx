import React, { useEffect, useMemo, useState } from 'react';
import { DeviceMode, DonghuaMovie, DonghuaSource, GenreItem, WatchProgress } from '../../types';
import { MovieCard } from '../catalog/MovieCard';

interface HomeViewProps {
  movies: DonghuaMovie[];
  genres: GenreItem[];
  sources: DonghuaSource[];
  selectedGenre: string;
  onSelectGenre: (genre: string) => void;
  selectedSourceId: string;
  onSelectSource: (sourceId: string) => void;
  favorites: string[];
  watchHistory: Record<string, WatchProgress>;
  onSelectMovie: (movie: DonghuaMovie) => void;
  onQuickPlayMovie: (movie: DonghuaMovie, ep?: number) => void;
  onToggleFavorite: (id: string, e: React.MouseEvent) => void;
  onClearHistory: () => void;
  resolvedDevice?: Exclude<DeviceMode, 'auto'>;
  searchQuery?: string;
  isSearching?: boolean;
  isLoadingGenre?: boolean;
  onClearSearch?: () => void;
}

export const HomeView: React.FC<HomeViewProps> = ({
  movies,
  genres,
  sources,
  selectedGenre,
  onSelectGenre,
  selectedSourceId,
  favorites,
  watchHistory,
  onSelectMovie,
  onQuickPlayMovie,
  onToggleFavorite,
  onClearHistory,
  resolvedDevice,
  searchQuery = '',
  isSearching = false,
  isLoadingGenre = false,
  onClearSearch
}) => {
  const activeSourceName =
    sources.find((s) => s.id === selectedSourceId)?.name || selectedSourceId;
  const isSearchActive = searchQuery.trim().length > 0;
  const continueList = Object.values(watchHistory).sort((a, b) => b.updatedAt - a.updatedAt);

  // Trending top 8 sorted by viewsNum & rating
  const trendingMovies = useMemo(() => {
    return [...movies]
      .sort((a, b) => (b.viewsNum || 0) - (a.viewsNum || 0))
      .slice(0, 8);
  }, [movies]);

  // Android TV Horizontal Row Carousels grouped by popular Donghua categories
  const tvCarouselRows = useMemo(() => {
    if (resolvedDevice !== 'tv' || isSearchActive || selectedGenre !== 'Tất cả') return [];
    const defs = [
      { id: 'dubbed', title: 'Thuyết Minh & Lồng Tiếng Hot', icon: 'fa-microphone-lines', filter: (m: DonghuaMovie) => m.audioModes.includes('Thuyết Minh') },
      { id: 'tutien', title: 'Tu Tiên • Tiên Hiệp Đỉnh Cao', icon: 'fa-wand-sparkles', filter: (m: DonghuaMovie) => m.genres.some((g) => g.toLowerCase().includes('tu tiên') || g.toLowerCase().includes('tiên hiệp')) },
      { id: 'huyenhuyen', title: 'Huyền Huyễn • Nghịch Thiên', icon: 'fa-dragon', filter: (m: DonghuaMovie) => m.genres.some((g) => g.toLowerCase().includes('huyền huyễn') || g.toLowerCase().includes('trùng sinh')) }
    ];
    return defs
      .map((d) => ({ ...d, items: movies.filter(d.filter).slice(0, 12) }))
      .filter((r) => r.items.length >= 3);
  }, [movies, resolvedDevice, isSearchActive, selectedGenre]);

  // Hero spotlight slides (top 5)
  const heroSlides = useMemo(() => trendingMovies.slice(0, 5), [trendingMovies]);
  const [heroIdx, setHeroIdx] = useState<number>(0);

  useEffect(() => {
    if (heroSlides.length <= 1) return;
    const timer = window.setInterval(() => {
      setHeroIdx((prev) => (prev + 1) % heroSlides.length);
    }, 7000);
    return () => window.clearInterval(timer);
  }, [heroSlides.length]);

  const activeHero = heroSlides[heroIdx % Math.max(1, heroSlides.length)];

  const gridColsClass =
    resolvedDevice === 'mobile'
      ? 'grid-cols-2'
      : resolvedDevice === 'tablet'
      ? 'grid-cols-3'
      : resolvedDevice === 'tv'
      ? 'grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4'
      : 'grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5';

  return (
    <div className="space-y-6 pb-28">
      {/* Search Status Header (Shown immediately at top when searching) */}
      {isSearchActive && (
        <section className="liquid-glass p-4 rounded-3xl flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-2xl bg-gradient-to-br from-indigo-600 to-cyan-500 text-white flex items-center justify-center shadow">
              <i className={`fa-solid ${isSearching ? 'fa-spinner animate-spin' : 'fa-magnifying-glass'} text-sm`}></i>
            </div>
            <div>
              <h2 className="font-heading font-extrabold text-sm sm:text-base text-main">
                Kết quả tìm kiếm: “{searchQuery.trim()}”
              </h2>
              <p className="text-xs text-muted">
                {isSearching
                  ? 'Đang quét đồng thời trên tất cả các nguồn phim...'
                  : `Tìm thấy ${movies.length} phim khớp nhất (hỗ trợ tiếng Việt có dấu, không dấu & viết tắt)`}
              </p>
            </div>
          </div>
          {onClearSearch && (
            <button
              type="button"
              onClick={onClearSearch}
              className="px-3 py-1.5 rounded-xl bg-rose-500/15 hover:bg-rose-500/25 text-rose-600 dark:text-rose-300 text-xs font-bold flex items-center gap-1.5 tv-focus-target"
            >
              <i className="fa-solid fa-xmark"></i>
              <span>Xóa tìm kiếm</span>
            </button>
          )}
        </section>
      )}

      {/* Hero Spotlight Banner */}
      {!isSearchActive && activeHero && (
        <section className="relative rounded-3xl overflow-hidden liquid-glass min-h-[210px] sm:min-h-[260px] flex items-end shadow-xl">
          <img
            src={activeHero.thumb || activeHero.poster}
            alt={activeHero.title}
            className="absolute inset-0 w-full h-full object-cover object-center scale-105 opacity-75"
          />
          <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/70 to-slate-950/20"></div>
          <div className="absolute inset-0 bg-gradient-to-r from-slate-950/90 via-slate-950/45 to-transparent"></div>

          <div className="relative z-10 w-full p-5 sm:p-7 flex flex-col sm:flex-row sm:items-end justify-between gap-4">
            <div className="max-w-xl space-y-2 text-white">
              <div className="flex flex-wrap items-center gap-2">
                <span className="px-2.5 py-0.5 rounded-full bg-gradient-to-r from-indigo-600 to-cyan-500 text-[10px] font-extrabold uppercase tracking-wider shadow">
                  Niên Phiên Nổi Bật
                </span>
                <span className="px-2 py-0.5 rounded-md bg-white/15 backdrop-blur-md text-[10px] font-bold text-cyan-300">
                  {activeHero.quality}
                </span>
                <span className="px-2 py-0.5 rounded-md bg-amber-500/20 text-amber-300 text-[10px] font-bold">
                  <i className="fa-solid fa-star mr-1"></i>
                  {activeHero.rating} • Tập {activeHero.latestEp}
                </span>
              </div>

              <h2 className="font-heading font-extrabold text-xl sm:text-3xl leading-tight line-clamp-1">
                {activeHero.title}
              </h2>
              <p className="text-xs sm:text-sm text-slate-300 line-clamp-2">
                {activeHero.originalTitle} • {activeHero.studio}
              </p>

              <div className="flex flex-wrap items-center gap-2.5 pt-1">
                <button
                  type="button"
                  onClick={() => onQuickPlayMovie(activeHero, 1)}
                  className="px-4 py-2 rounded-2xl bg-gradient-to-r from-indigo-600 to-cyan-500 hover:brightness-110 text-white text-xs font-extrabold shadow-lg shadow-indigo-500/30 flex items-center gap-1.5 tv-focus-target"
                >
                  <i className="fa-solid fa-play"></i>
                  <span>Xem Ngay</span>
                </button>
                <button
                  type="button"
                  onClick={() => onSelectMovie(activeHero)}
                  className="px-4 py-2 rounded-2xl bg-white/15 hover:bg-white/25 backdrop-blur-md text-white text-xs font-bold border border-white/20 flex items-center gap-1.5 tv-focus-target"
                >
                  <i className="fa-solid fa-circle-info"></i>
                  <span>Chi Tiết &amp; Tập</span>
                </button>
              </div>
            </div>

            {/* Slide Switcher Dots */}
            {heroSlides.length > 1 && (
              <div className="flex items-center gap-1.5 self-start sm:self-end">
                {heroSlides.map((slide, idx) => (
                  <button
                    key={slide.id}
                    type="button"
                    onClick={() => setHeroIdx(idx)}
                    aria-label={`Xem slide ${idx + 1}`}
                    className={`h-2 rounded-full transition-all tv-focus-target ${
                      heroIdx === idx
                        ? 'w-6 bg-cyan-400'
                        : 'w-2 bg-white/40 hover:bg-white/70'
                    }`}
                  />
                ))}
              </div>
            )}
          </div>
        </section>
      )}

      {/* Continue Watching Section */}
      {!isSearchActive && continueList.length > 0 && (
        <section className="liquid-glass p-4 rounded-3xl">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <i className="fa-solid fa-play text-indigo-600 dark:text-cyan-400 text-xs"></i>
              <h3 className="font-heading font-bold text-sm text-main">Tiếp tục xem</h3>
              <span className="text-[10px] px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-600 dark:text-cyan-300 font-semibold">
                {continueList.length} phim
              </span>
            </div>
            <button
              type="button"
              onClick={onClearHistory}
              className="text-xs text-muted hover:text-rose-500 transition-colors tv-focus-target rounded-lg px-2 py-0.5"
            >
              Xóa lịch sử
            </button>
          </div>

          <div className="flex gap-3 overflow-x-auto pb-2 no-scrollbar">
            {continueList.slice(0, 8).map((prog) => {
              const matchedMovie = movies.find((m) => m.id === prog.donghuaId);
              return (
                <button
                  key={prog.donghuaId}
                  type="button"
                  onClick={() =>
                    matchedMovie && onQuickPlayMovie(matchedMovie, prog.episode)
                  }
                  className="flex-shrink-0 w-44 liquid-card rounded-xl overflow-hidden cursor-pointer group text-left tv-focus-target"
                >
                  <div className="relative aspect-video bg-slate-900">
                    <img src={prog.poster} alt={prog.title} className="w-full h-full object-cover" />
                    <div className="absolute inset-0 bg-black/35 group-hover:bg-black/15 transition-all flex items-center justify-center">
                      <div className="w-8 h-8 rounded-full bg-gradient-to-r from-indigo-600 to-cyan-500 text-white flex items-center justify-center shadow-lg">
                        <i className="fa-solid fa-play text-xs ml-0.5"></i>
                      </div>
                    </div>
                    <div className="absolute bottom-0 inset-x-0 h-1 bg-black/50">
                      <div
                        className="h-full bg-gradient-to-r from-indigo-500 to-cyan-400"
                        style={{ width: `${prog.progressPct}%` }}
                      ></div>
                    </div>
                  </div>
                  <div className="p-2">
                    <h5 className="font-bold text-xs text-main line-clamp-1">{prog.title}</h5>
                    <p className="text-[10px] text-indigo-600 dark:text-cyan-400 font-semibold">
                      Tập {prog.episode} • {prog.progressPct}%
                    </p>
                  </div>
                </button>
              );
            })}
          </div>
        </section>
      )}

      {/* Trending Carousel (Bảng Xếp Hạng Thịnh Hành) */}
      {!isSearchActive && trendingMovies.length > 0 && selectedGenre === 'Tất cả' && (
        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <i className="fa-solid fa-fire-flame-curved text-amber-500 text-sm"></i>
              <h3 className="font-heading font-extrabold text-base text-main">
                Bảng Xếp Hạng Thịnh Hành
              </h3>
            </div>
            <span className="text-[11px] font-bold text-indigo-600 dark:text-cyan-300">
              Top {trendingMovies.length} Tu Tiên 3D
            </span>
          </div>

          <div className="flex gap-3.5 overflow-x-auto pb-2 no-scrollbar">
            {trendingMovies.map((m, idx) => (
              <div key={`trend_${m.id}`} className="flex-shrink-0 w-36 sm:w-44">
                <MovieCard
                  movie={m}
                  isFavorite={favorites.includes(m.id)}
                  onSelect={onSelectMovie}
                  onToggleFavorite={onToggleFavorite}
                  progressPct={watchHistory[m.id]?.progressPct}
                  rankBadge={idx + 1}
                />
              </div>
            ))}
          </div>
        </section>
      )}

      {/* Android TV Leanback Horizontal Row Carousels */}
      {tvCarouselRows.map((row) => (
        <section key={row.id} className="space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <i className={`fa-solid ${row.icon} text-cyan-400 text-sm`}></i>
              <h3 className="font-heading font-extrabold text-base text-main">{row.title}</h3>
            </div>
            <span className="text-[11px] font-semibold text-muted">
              D-Pad Trái/Phải • {row.items.length} phim
            </span>
          </div>
          <div className="flex gap-4 overflow-x-auto pb-2 pt-1 px-1 no-scrollbar scroll-smooth snap-x">
            {row.items.map((m) => (
              <div key={`tvrow_${row.id}_${m.id}`} className="flex-shrink-0 w-44 sm:w-48 snap-start">
                <MovieCard
                  movie={m}
                  isFavorite={favorites.includes(m.id)}
                  onSelect={onSelectMovie}
                  onToggleFavorite={onToggleFavorite}
                  progressPct={watchHistory[m.id]?.progressPct}
                />
              </div>
            ))}
          </div>
        </section>
      ))}

      {/* Genre Filter Chips */}
      {!isSearchActive && (
        <section className="flex items-center gap-2 overflow-x-auto pb-1 no-scrollbar">
          {genres.map((g) => {
            const isSelected = selectedGenre === g.id;
            return (
              <button
                key={g.id}
                type="button"
                onClick={() => onSelectGenre(g.id)}
                className={`flex-shrink-0 flex items-center gap-1.5 px-3.5 py-2 rounded-2xl text-xs font-semibold transition-all tv-focus-target liquid-pill ${
                  isSelected ? 'active' : 'text-main'
                }`}
              >
                <i className={`fa-solid ${g.icon} text-xs`}></i>
                <span>{g.label}</span>
              </button>
            );
          })}
        </section>
      )}

      {/* Movie Catalog Grid */}
      <section>
        <div className="flex flex-wrap items-center justify-between gap-2 mb-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-heading font-extrabold text-base text-main flex items-center gap-2">
              <span>Kho Nguồn Phim Đang Chọn:</span>
              <span className="px-2.5 py-0.5 rounded-full bg-indigo-500/15 text-indigo-600 dark:text-cyan-300 text-xs font-bold border border-indigo-500/20">
                {activeSourceName}
                {selectedGenre !== 'Tất cả' && !isSearchActive ? ` • ${selectedGenre}` : ''}
              </span>
            </h3>
            <span className="text-xs text-muted font-medium">({movies.length} kết quả)</span>
            {isLoadingGenre && (
              <span className="text-xs text-indigo-600 dark:text-cyan-300 animate-pulse font-semibold flex items-center gap-1">
                <i className="fa-solid fa-spinner animate-spin"></i>
                Đang lọc...
              </span>
            )}
          </div>
        </div>

        {movies.length === 0 ? (
          <div className="text-center py-16 liquid-glass rounded-3xl">
            <i className="fa-solid fa-film text-muted text-3xl mb-3"></i>
            <p className="text-sm text-muted">Không tìm thấy phim phù hợp với bộ lọc</p>
          </div>
        ) : (
          <div className={`grid ${gridColsClass} gap-3.5`}>
            {movies.map((m) => {
              const isFav = favorites.includes(m.id);
              const progress = watchHistory[m.id]?.progressPct;
              return (
                <MovieCard
                  key={m.id}
                  movie={m}
                  isFavorite={isFav}
                  onSelect={onSelectMovie}
                  onToggleFavorite={onToggleFavorite}
                  progressPct={progress}
                />
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
};
