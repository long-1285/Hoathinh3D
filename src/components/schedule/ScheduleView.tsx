import React, { useEffect, useMemo, useState } from 'react';
import { DonghuaMovie } from '../../types';
import { YanHH3DService } from '../../services/yanhh3d.service';

interface ScheduleViewProps {
  movies: DonghuaMovie[];
  onSelectMovie: (movie: DonghuaMovie) => void;
}

const DAY_TO_SHOWTIME_ID: Record<
  'Thứ 2' | 'Thứ 3' | 'Thứ 4' | 'Thứ 5' | 'Thứ 6' | 'Thứ 7' | 'Chủ Nhật',
  number
> = {
  'Chủ Nhật': 4,
  'Thứ 2': 5,
  'Thứ 3': 6,
  'Thứ 4': 7,
  'Thứ 5': 8,
  'Thứ 6': 9,
  'Thứ 7': 10
};

export const ScheduleView: React.FC<ScheduleViewProps> = ({ movies, onSelectMovie }) => {
  const days: ('Thứ 2' | 'Thứ 3' | 'Thứ 4' | 'Thứ 5' | 'Thứ 6' | 'Thứ 7' | 'Chủ Nhật')[] = [
    'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7', 'Chủ Nhật'
  ];

  const jsDay = new Date().getDay();
  const dayMap: {
    [key: number]: 'Thứ 2' | 'Thứ 3' | 'Thứ 4' | 'Thứ 5' | 'Thứ 6' | 'Thứ 7' | 'Chủ Nhật';
  } = {
    1: 'Thứ 2',
    2: 'Thứ 3',
    3: 'Thứ 4',
    4: 'Thứ 5',
    5: 'Thứ 6',
    6: 'Thứ 7',
    0: 'Chủ Nhật'
  };
  const todayName = dayMap[jsDay] || 'Chủ Nhật';

  const [activeDay, setActiveDay] = useState(todayName);
  const [yanDayMovies, setYanDayMovies] = useState<DonghuaMovie[]>([]);
  const [isLoadingDay, setIsLoadingDay] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const showtimeId = DAY_TO_SHOWTIME_ID[activeDay];
    if (!showtimeId) return;

    setIsLoadingDay(true);
    YanHH3DService.fetchYanMovies({ showtimeId, perPage: 30 })
      .then((res) => {
        if (!cancelled) {
          setYanDayMovies(res.movies);
          setIsLoadingDay(false);
        }
      })
      .catch(() => {
        if (!cancelled) setIsLoadingDay(false);
      });

    return () => {
      cancelled = true;
    };
  }, [activeDay]);

  const filteredMovies = useMemo(() => {
    const map = new Map<string, DonghuaMovie>();
    yanDayMovies.forEach((m) => map.set(m.slug || m.id, m));
    movies
      .filter((m) => m.scheduleDay === activeDay)
      .forEach((m) => {
        const key = m.slug || m.id;
        if (!map.has(key)) map.set(key, m);
      });
    return Array.from(map.values());
  }, [yanDayMovies, movies, activeDay]);

  return (
    <div className="space-y-6 pb-28">
      {/* Header Banner */}
      <div className="liquid-glass p-5 rounded-3xl flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div>
          <h2 className="font-heading font-extrabold text-lg text-main">
            Lịch Chiếu Hoạt Hình 3D
          </h2>
          <p className="text-xs text-muted">
            Theo dõi lịch phát sóng các bộ phim Donghua hot nhất tuần
          </p>
        </div>
        <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-indigo-500/15 text-indigo-600 dark:text-cyan-300 border border-indigo-500/25 text-xs font-bold w-fit">
          <i className="fa-solid fa-clock"></i>
          <span>Hôm nay: {todayName}</span>
        </div>
      </div>

      {/* Day Tabs */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 no-scrollbar">
        {days.map((d) => {
          const isActive = activeDay === d;
          const isToday = d === todayName;
          return (
            <button
              key={d}
              type="button"
              onClick={() => setActiveDay(d)}
              className={`flex-shrink-0 flex items-center gap-1.5 px-4 py-2 rounded-2xl text-xs font-bold transition-all tv-focus-target liquid-pill ${
                isActive ? 'active' : 'text-main'
              }`}
            >
              <span>{d}</span>
              {isToday && (
                <span
                  className={`w-1.5 h-1.5 rounded-full ${
                    isActive ? 'bg-white' : 'bg-indigo-500 dark:bg-cyan-400'
                  }`}
                ></span>
              )}
            </button>
          );
        })}
      </div>

      {/* Movies in Active Day */}
      <div className="space-y-3">
        {isLoadingDay && (
          <div className="flex items-center justify-center gap-2 py-2 text-xs text-indigo-600 dark:text-cyan-300 animate-pulse">
            <i className="fa-solid fa-spinner animate-spin"></i>
            <span>Đang đồng bộ lịch chiếu {activeDay} từ YanHH3D...</span>
          </div>
        )}
        {filteredMovies.length === 0 ? (
          <div className="text-center py-16 liquid-glass rounded-3xl">
            <i className="fa-solid fa-calendar-xmark text-muted text-3xl mb-3"></i>
            <p className="text-sm text-muted">Chưa có lịch phát sóng cho {activeDay}</p>
          </div>
        ) : (
          filteredMovies.map((m) => (
            <div
              key={m.id}
              onClick={() => onSelectMovie(m)}
              className="liquid-card p-3 rounded-2xl flex items-center gap-4 cursor-pointer transition-all tv-focus-target"
            >
              <img
                src={m.poster}
                alt={m.title}
                className="w-16 h-20 object-cover rounded-xl flex-shrink-0"
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="px-2 py-0.5 rounded-md bg-indigo-500/15 text-indigo-600 dark:text-cyan-300 font-bold text-[10px]">
                    <i className="fa-regular fa-clock mr-1"></i> {m.scheduleTime}
                  </span>
                  <span className="text-[10px] text-muted">{m.studio}</span>
                </div>
                <h4 className="font-heading font-bold text-sm text-main line-clamp-1">{m.title}</h4>
                <p className="text-xs text-muted line-clamp-1 mt-0.5">{m.originalTitle}</p>
              </div>
              <div className="text-right flex-shrink-0 pr-2">
                <span className="text-xs font-bold text-indigo-600 dark:text-cyan-400">
                  Tập {m.latestEp}
                </span>
                <p className="text-[10px] text-muted">Mới nhất</p>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};
