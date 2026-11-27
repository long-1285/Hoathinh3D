import React from 'react';
import { DonghuaMovie, WatchProgress } from '../../types';

interface HistoryViewProps {
  watchHistory: Record<string, WatchProgress>;
  movies: DonghuaMovie[];
  onSelectMovie: (movie: DonghuaMovie, ep?: number) => void;
  onClearHistory: () => void;
}

export const HistoryView: React.FC<HistoryViewProps> = ({
  watchHistory,
  movies,
  onSelectMovie,
  onClearHistory
}) => {
  const historyList = Object.values(watchHistory).sort((a, b) => b.updatedAt - a.updatedAt);

  return (
    <div className="space-y-6 pb-28">
      {/* Header */}
      <div className="liquid-glass p-5 rounded-3xl flex items-center justify-between">
        <div>
          <h2 className="font-heading font-extrabold text-lg text-main">Lịch Sử Xem Phim</h2>
          <p className="text-xs text-muted">
            Đã lưu {historyList.length} bộ phim bạn từng thưởng thức
          </p>
        </div>
        {historyList.length > 0 && (
          <button
            type="button"
            onClick={onClearHistory}
            className="px-3.5 py-1.5 rounded-xl bg-rose-500/15 hover:bg-rose-500/25 text-rose-600 dark:text-rose-300 border border-rose-500/25 text-xs font-bold transition-all tv-focus-target"
          >
            <i className="fa-solid fa-trash mr-1.5"></i> Xóa tất cả
          </button>
        )}
      </div>

      {historyList.length === 0 ? (
        <div className="text-center py-20 liquid-glass rounded-3xl">
          <i className="fa-solid fa-clock-rotate-left text-muted text-4xl mb-3"></i>
          <p className="text-sm text-main font-semibold">Chưa có lịch sử xem</p>
          <p className="text-xs text-muted mt-1">
            Các bộ phim bạn đã xem sẽ tự động lưu vào đây
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3.5">
          {historyList.map((item) => {
            const matched = movies.find((m) => m.id === item.donghuaId);
            return (
              <div
                key={item.donghuaId}
                onClick={() => matched && onSelectMovie(matched, item.episode)}
                className="liquid-card rounded-2xl overflow-hidden cursor-pointer group flex flex-col justify-between tv-focus-target"
              >
                <div className="relative aspect-video bg-slate-900">
                  <img src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                  <div className="absolute inset-0 bg-black/35 group-hover:bg-black/15 transition-all flex items-center justify-center">
                    <div className="w-10 h-10 rounded-full bg-gradient-to-r from-indigo-600 to-cyan-500 text-white flex items-center justify-center shadow-lg">
                      <i className="fa-solid fa-play text-sm ml-0.5"></i>
                    </div>
                  </div>
                  <div className="absolute bottom-0 inset-x-0 h-1.5 bg-black/60">
                    <div
                      className="h-full bg-gradient-to-r from-indigo-500 to-cyan-400"
                      style={{ width: `${item.progressPct}%` }}
                    ></div>
                  </div>
                </div>

                <div className="p-3">
                  <h4 className="font-heading font-bold text-sm text-main line-clamp-1">
                    {item.title}
                  </h4>
                  <div className="flex items-center justify-between text-xs text-indigo-600 dark:text-cyan-400 font-semibold mt-1">
                    <span>Đang xem Tập {item.episode}</span>
                    <span className="font-mono">{item.progressPct}%</span>
                  </div>
                  <p className="text-[10px] text-muted mt-1">
                    {item.server} • {item.audio}
                  </p>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
