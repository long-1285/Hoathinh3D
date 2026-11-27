import React, { useState } from 'react';
import { DonghuaMovie } from '../../types';
import { ApiService } from '../../services/api.service';

interface MovieCardProps {
  movie: DonghuaMovie;
  isFavorite: boolean;
  onSelect: (movie: DonghuaMovie) => void;
  onToggleFavorite: (id: string, e: React.MouseEvent) => void;
  progressPct?: number;
  rankBadge?: number;
  newEpBadge?: number;
  offlineCount?: number;
}

export const MovieCard: React.FC<MovieCardProps> = ({
  movie,
  isFavorite,
  onSelect,
  onToggleFavorite,
  progressPct,
  rankBadge,
  newEpBadge,
  offlineCount
}) => {
  const [imgSrc, setImgSrc] = useState<string>(() =>
    ApiService.getOptimizedPosterUrl(movie.poster, 400)
  );

  return (
    <div
      onClick={() => onSelect(movie)}
      className="liquid-card rounded-2xl overflow-hidden cursor-pointer group flex flex-col relative tv-focus-target"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter') onSelect(movie);
      }}
    >
      {/* Poster Image Container */}
      <div className="relative aspect-[3/4] w-full overflow-hidden bg-slate-900">
        <img
          src={imgSrc}
          alt={movie.title}
          loading="lazy"
          decoding="async"
          onError={() => {
            if (imgSrc !== movie.poster) {
              setImgSrc(movie.poster);
            }
          }}
          className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        />

        {/* Rank Badge if trending */}
        {typeof rankBadge === 'number' && (
          <div
            className={`absolute top-2 left-2 z-10 w-7 h-7 rounded-xl flex items-center justify-center font-heading font-black text-xs text-white shadow-lg ${
              rankBadge === 1
                ? 'bg-gradient-to-br from-amber-400 to-orange-500'
                : rankBadge === 2
                ? 'bg-gradient-to-br from-slate-300 to-slate-500'
                : rankBadge === 3
                ? 'bg-gradient-to-br from-amber-600 to-amber-800'
                : 'bg-slate-900/85'
            }`}
          >
            #{rankBadge}
          </div>
        )}

        {/* Quality & Episode Badges */}
        <div
          className={`absolute ${
            typeof rankBadge === 'number' ? 'top-10' : 'top-2'
          } left-2 flex flex-col gap-1`}
        >
          <span className="px-2 py-0.5 rounded-md bg-black/70 backdrop-blur-md text-[10px] font-extrabold text-cyan-300 border border-cyan-500/30">
            {movie.quality}
          </span>
          <span className="px-2 py-0.5 rounded-md bg-indigo-900/80 backdrop-blur-md text-[10px] font-bold text-white border border-indigo-500/30">
            Tập {movie.latestEp}/{movie.totalEp}
          </span>
          {typeof newEpBadge === 'number' && newEpBadge > 0 && (
            <span className="px-2 py-0.5 rounded-md bg-emerald-500 text-slate-950 text-[10px] font-extrabold shadow">
              +{newEpBadge} tập mới
            </span>
          )}
          {typeof offlineCount === 'number' && offlineCount > 0 && (
            <span className="px-2 py-0.5 rounded-md bg-amber-400/95 text-slate-950 text-[10px] font-extrabold shadow">
              <i className="fa-solid fa-cloud-arrow-down mr-1"></i>
              {offlineCount} offline
            </span>
          )}
        </div>

        {/* Favorite Heart Button */}
        <button
          type="button"
          onClick={(e) => onToggleFavorite(movie.id, e)}
          className={`absolute top-2 right-2 w-8 h-8 rounded-full flex items-center justify-center backdrop-blur-md transition-all ${
            isFavorite
              ? 'bg-rose-500/90 text-white shadow-lg shadow-rose-500/30'
              : 'bg-black/50 text-white hover:bg-rose-500/80'
          }`}
        >
          <i className="fa-solid fa-heart text-xs"></i>
        </button>

        {/* Watch Progress bar if available */}
        {typeof progressPct === 'number' && progressPct > 0 && (
          <div className="absolute bottom-0 inset-x-0 h-1.5 bg-black/60">
            <div
              className="h-full bg-gradient-to-r from-indigo-500 to-cyan-400"
              style={{ width: `${progressPct}%` }}
            ></div>
          </div>
        )}

        {/* Rating overlay */}
        <div className="absolute bottom-2 left-2 flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-black/70 backdrop-blur-md text-[11px] font-bold text-amber-400">
          <i className="fa-solid fa-star text-[10px]"></i>
          <span>{movie.rating}</span>
        </div>
      </div>

      {/* Movie Details */}
      <div className="p-3 flex flex-col flex-1 justify-between gap-1">
        <div>
          <h4 className="font-heading font-bold text-xs sm:text-sm text-main line-clamp-1 group-hover:text-indigo-600 dark:group-hover:text-cyan-400 transition-colors">
            {movie.title}
          </h4>
          <p className="text-[11px] text-muted line-clamp-1">{movie.originalTitle}</p>
        </div>

        <div className="flex items-center justify-between text-[11px] text-muted pt-1 border-t border-slate-200/50 dark:border-white/10">
          <span className="flex items-center gap-1">
            <i className="fa-regular fa-eye text-[10px] text-indigo-500 dark:text-cyan-400"></i>
            {movie.views}
          </span>
          <span className="text-[10px] text-indigo-600 dark:text-indigo-300 font-semibold truncate max-w-[110px]">
            {movie.studio}
          </span>
        </div>
      </div>
    </div>
  );
};
