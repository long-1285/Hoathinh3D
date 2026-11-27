import React from 'react';

export type TabType = 'home' | 'schedule' | 'history' | 'favorites' | 'settings';

interface BottomLiquidNavProps {
  activeTab: TabType;
  onChangeTab: (tab: TabType) => void;
  favoritesCount?: number;
}

export const BottomLiquidNav: React.FC<BottomLiquidNavProps> = ({
  activeTab,
  onChangeTab,
  favoritesCount = 0
}) => {
  const tabs: { id: TabType; label: string; icon: string; badge?: number }[] = [
    { id: 'home', label: 'Khám phá', icon: 'fa-solid fa-compass' },
    { id: 'schedule', label: 'Lịch chiếu', icon: 'fa-regular fa-calendar-days' },
    { id: 'history', label: 'Lịch sử', icon: 'fa-solid fa-clock-rotate-left' },
    { id: 'favorites', label: 'Tủ phim', icon: 'fa-regular fa-heart', badge: favoritesCount },
    { id: 'settings', label: 'Cài đặt', icon: 'fa-solid fa-sliders' }
  ];

  return (
    <nav className="fixed bottom-3 sm:bottom-4 left-1/2 -translate-x-1/2 z-40 w-[94vw] max-w-2xl liquid-nav rounded-3xl px-2 py-1.5 pb-safe">
      <div className="grid grid-cols-5 items-center">
        {tabs.map((tab) => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => onChangeTab(tab.id)}
              className={`relative flex flex-col items-center justify-center gap-0.5 py-1.5 px-2 rounded-2xl transition-all tv-focus-target ${
                isActive
                  ? 'text-indigo-600 dark:text-cyan-400 font-extrabold scale-105'
                  : 'text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-cyan-300 font-medium'
              }`}
            >
              <div className="relative">
                <i className={`${tab.icon} text-base`}></i>
                {typeof tab.badge === 'number' && tab.badge > 0 && (
                  <span className="absolute -top-1.5 -right-2.5 min-w-[16px] h-4 px-1 rounded-full bg-rose-500 text-white text-[9px] font-extrabold flex items-center justify-center shadow">
                    {tab.badge > 99 ? '99+' : tab.badge}
                  </span>
                )}
              </div>
              <span className="text-[10px] tracking-tight">{tab.label}</span>
              {isActive && (
                <span className="w-5 h-0.5 rounded-full bg-gradient-to-r from-indigo-600 to-cyan-500 mt-0.5"></span>
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};
