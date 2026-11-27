import React from 'react';
import {
  DeviceMode,
  DonghuaMovie,
  FavoriteEpisodeMeta,
  OfflineEpisodeRecord
} from '../../types';
import { MovieCard } from '../catalog/MovieCard';

interface FavoritesViewProps {
  favorites: string[];
  movies: DonghuaMovie[];
  favoriteMetaMap: Record<string, FavoriteEpisodeMeta>;
  offlineEpisodes: Record<string, OfflineEpisodeRecord>;
  isCheckingUpdates: boolean;
  onCheckNewEpisodes: () => void;
  onSelectMovie: (movie: DonghuaMovie) => void;
  onPlayOfflineEpisode: (record: OfflineEpisodeRecord) => void;
  onRemoveOfflineEpisode: (key: string) => void;
  onToggleFavorite: (id: string, e: React.MouseEvent) => void;
  resolvedDevice?: Exclude<DeviceMode, 'auto'>;
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 MB';
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export const FavoritesView: React.FC<FavoritesViewProps> = ({
  favorites,
  movies,
  favoriteMetaMap,
  offlineEpisodes,
  isCheckingUpdates,
  onCheckNewEpisodes,
  onSelectMovie,
  onPlayOfflineEpisode,
  onRemoveOfflineEpisode,
  onToggleFavorite,
  resolvedDevice
}) => {
  const favoriteMovies = movies.filter((m) => favorites.includes(m.id));
  const offlineList = Object.values(offlineEpisodes).sort((a, b) => b.savedAt - a.savedAt);
  const offlineCountByMovie = offlineList.reduce<Record<string, number>>((acc, rec) => {
    acc[rec.donghuaId] = (acc[rec.donghuaId] || 0) + 1;
    return acc;
  }, {});
  const totalNewCount = favoriteMovies.reduce(
    (sum, m) => sum + (favoriteMetaMap[m.id]?.newEpCount || 0),
    0
  );

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
      {/* Header */}
      <div className="liquid-glass p-5 rounded-3xl flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-heading font-extrabold text-lg text-main flex items-center gap-2">
            <span>Tủ Phim Yêu Thích</span>
            {totalNewCount > 0 && (
              <span className="px-2.5 py-0.5 rounded-full bg-emerald-500 text-slate-950 text-[11px] font-extrabold">
                +{totalNewCount} tập mới
              </span>
            )}
          </h2>
          <p className="text-xs text-muted">
            {favoriteMovies.length} phim đang theo dõi • {offlineList.length} tập đã lưu offline
          </p>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={isCheckingUpdates || favoriteMovies.length === 0}
            onClick={onCheckNewEpisodes}
            className="px-3.5 py-2 rounded-2xl liquid-pill text-xs font-bold flex items-center gap-2 text-indigo-600 dark:text-cyan-300 disabled:opacity-50 tv-focus-target"
          >
            <i className={`fa-solid ${isCheckingUpdates ? 'fa-spinner animate-spin' : 'fa-bell'}`}></i>
            <span>{isCheckingUpdates ? 'Đang kiểm tra...' : 'Kiểm tra tập mới'}</span>
          </button>
          <div className="w-10 h-10 rounded-2xl bg-rose-500/15 text-rose-500 flex items-center justify-center border border-rose-500/25">
            <i className="fa-solid fa-heart text-base"></i>
          </div>
        </div>
      </div>

      {/* Offline Saved Episodes Section */}
      {offlineList.length > 0 && (
        <section className="liquid-glass p-4 rounded-3xl space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading font-extrabold text-sm text-main flex items-center gap-2">
              <i className="fa-solid fa-cloud-arrow-down text-emerald-500"></i>
              <span>Tập Phim Đã Lưu Offline ({offlineList.length})</span>
            </h3>
            <span className="text-[11px] text-muted">Phát trực tiếp từ CacheStorage không cần mạng</span>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {offlineList.map((rec) => (
              <div
                key={rec.key}
                className="liquid-card p-3 rounded-2xl flex items-center gap-3"
              >
                <img
                  src={rec.poster}
                  alt={rec.title}
                  className="w-14 h-18 rounded-xl object-cover shrink-0 bg-slate-900"
                />
                <div className="min-w-0 flex-1">
                  <h4 className="font-bold text-xs text-main line-clamp-1">{rec.title}</h4>
                  <p className="text-[11px] text-emerald-600 dark:text-emerald-300 font-bold">
                    {rec.episodeName} • {formatBytes(rec.approxBytes)}
                  </p>
                  <p className="text-[10px] text-muted truncate">{rec.serverName}</p>
                  <div className="flex items-center gap-2 mt-2">
                    <button
                      type="button"
                      onClick={() => onPlayOfflineEpisode(rec)}
                      className="px-2.5 py-1 rounded-lg bg-gradient-to-r from-indigo-600 to-cyan-500 text-white text-[11px] font-bold tv-focus-target"
                    >
                      <i className="fa-solid fa-play mr-1"></i>Phát Offline
                    </button>
                    <button
                      type="button"
                      onClick={() => onRemoveOfflineEpisode(rec.key)}
                      className="px-2 py-1 rounded-lg bg-rose-500/15 text-rose-500 text-[11px] font-bold tv-focus-target"
                      title="Xóa khỏi bộ nhớ đệm offline"
                    >
                      <i className="fa-solid fa-trash-can"></i>
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {favoriteMovies.length === 0 ? (
        <div className="text-center py-20 liquid-glass rounded-3xl">
          <i className="fa-regular fa-heart text-muted text-4xl mb-3"></i>
          <p className="text-sm text-main font-semibold">Chưa có phim yêu thích</p>
          <p className="text-xs text-muted mt-1">
            Bấm vào biểu tượng trái tim trên các bộ phim để lưu lại xem sau
          </p>
        </div>
      ) : (
        <div className={`grid ${gridColsClass} gap-3.5`}>
          {favoriteMovies.map((m) => (
            <MovieCard
              key={m.id}
              movie={m}
              isFavorite={true}
              onSelect={onSelectMovie}
              onToggleFavorite={onToggleFavorite}
              newEpBadge={favoriteMetaMap[m.id]?.newEpCount}
              offlineCount={offlineCountByMovie[m.id]}
            />
          ))}
        </div>
      )}
    </div>
  );
};
