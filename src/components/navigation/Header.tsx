import React from 'react';
import { DonghuaSource } from '../../types';
import appAvatar from '../../assets/app-avatar.jpg';

interface HeaderProps {
  sources: DonghuaSource[];
  activeSourceId: string;
  onSelectSource: (sourceId: string) => void;
  searchQuery: string;
  onSearchChange: (query: string) => void;
  onSearchSubmit?: () => void;
  onOpenSettings: () => void;
  onNavigateHome: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  sources,
  activeSourceId,
  onSelectSource,
  searchQuery,
  onSearchChange,
  onSearchSubmit,
  onOpenSettings,
  onNavigateHome
}) => {
  const enabledSources = sources.filter((s) => s.enabled);
  const activeSource = enabledSources.find((s) => s.id === activeSourceId) || enabledSources[0] || sources[0];
  const hasCustomSources = sources.some((s) => s.isCustom);

  return (
    <header className="sticky top-0 z-40 liquid-glass border-x-0 border-t-0 px-3 sm:px-6 py-2.5">
      <div
        id="header-inner-container"
        className="max-w-7xl mx-auto flex flex-wrap md:flex-nowrap items-center justify-between gap-2.5 transition-all duration-300"
      >
        {/* Brand Logo & Tag */}
        <button
          type="button"
          onClick={onNavigateHome}
          className="flex items-center gap-2.5 cursor-pointer group shrink-0 text-left tv-focus-target rounded-2xl p-0.5"
        >
          <div className="w-10 h-10 rounded-full bg-gradient-to-br from-indigo-600 via-sky-500 to-cyan-400 p-[1.5px] shadow-md shadow-indigo-500/25 group-hover:scale-105 transition-transform shrink-0">
            <img
              src={appAvatar}
              alt="HoatHinh3D Avatar"
              className="w-full h-full rounded-full object-cover"
            />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <span className="font-heading font-extrabold text-base sm:text-lg tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-indigo-700 via-sky-600 to-cyan-500 dark:from-indigo-300 dark:via-sky-300 dark:to-cyan-300">
                HoatHinh3D
              </span>
              <span className="text-[9px] font-extrabold px-1.5 py-0.5 rounded-md bg-gradient-to-r from-indigo-600 to-cyan-500 text-white shadow-sm">
                LIQUID
              </span>
            </div>
            <p className="text-[10px] text-muted hidden sm:block">
              Tổng hợp Donghua 3D • Đa nguồn Web tùy chỉnh
            </p>
          </div>
        </button>

        {/* Global Search Bar */}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSearchSubmit?.();
          }}
          className="order-3 md:order-2 w-full md:flex-1 md:max-w-lg relative"
        >
          <i className="fa-solid fa-magnifying-glass absolute left-3.5 top-1/2 -translate-y-1/2 text-indigo-500 text-xs"></i>
          <input
            type="search"
            enterKeyHint="search"
            placeholder={`Tìm tiếng Việt (Đấu Phá, dau pha, dptk...) trên ${enabledSources.length} nguồn...`}
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            className="w-full pl-9 pr-24 py-2 rounded-full text-xs sm:text-sm liquid-input placeholder-slate-400 font-medium tv-focus-target"
          />
          <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center gap-1">
            <span className="px-2 py-0.5 rounded-full bg-indigo-500/10 text-indigo-600 dark:text-indigo-300 text-[10px] font-extrabold hidden sm:inline-block">
              {enabledSources.length} Nguồn
            </span>
            {searchQuery && (
              <button
                type="button"
                onClick={() => onSearchChange('')}
                className="w-5 h-5 rounded-full bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 text-slate-600 dark:text-slate-200 flex items-center justify-center text-[10px]"
                title="Xóa tìm kiếm"
              >
                <i className="fa-solid fa-xmark"></i>
              </button>
            )}
          </div>
        </form>

        {/* Right Quick Actions */}
        <div className="order-2 md:order-3 flex items-center gap-1.5 sm:gap-2 shrink-0">
          {/* Active Source Selector */}
          <div className="relative">
            <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none"></span>
            <select
              value={activeSource?.id || ''}
              onChange={(e) => onSelectSource(e.target.value)}
              aria-label="Chọn nguồn phát ưu tiên"
              className="appearance-none liquid-pill pl-6 pr-7 py-1.5 rounded-xl text-xs font-bold text-indigo-600 dark:text-cyan-300 focus:outline-none cursor-pointer tv-focus-target max-w-[130px] sm:max-w-[165px] truncate"
            >
              {enabledSources.map((src) => (
                <option key={src.id} value={src.id} className="bg-white dark:bg-slate-900 text-slate-900 dark:text-white">
                  {src.name} ({src.ping})
                </option>
              ))}
            </select>
            <i className="fa-solid fa-globe text-muted text-[11px] absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none"></i>
          </div>

          {/* Settings Shortcut */}
          <button
            type="button"
            onClick={onOpenSettings}
            className="w-9 h-9 rounded-xl liquid-pill flex items-center justify-center text-indigo-600 dark:text-cyan-400 relative tv-focus-target"
            title="Trung tâm Cài đặt (7 phân mục)"
          >
            <i className="fa-solid fa-sliders text-sm"></i>
            {hasCustomSources && (
              <span className="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full bg-cyan-500 ring-2 ring-white dark:ring-slate-900"></span>
            )}
          </button>
        </div>
      </div>
    </header>
  );
};
