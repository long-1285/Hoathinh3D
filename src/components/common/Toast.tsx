import React from 'react';

interface ToastProps {
  message: string | null;
  type?: 'success' | 'info' | 'warning' | 'error';
}

export const Toast: React.FC<ToastProps> = ({ message, type = 'success' }) => {
  if (!message) return null;

  const iconMap = {
    success: 'fa-solid fa-circle-check text-emerald-500',
    info: 'fa-solid fa-circle-info text-indigo-500',
    warning: 'fa-solid fa-triangle-exclamation text-amber-500',
    error: 'fa-solid fa-circle-xmark text-rose-500'
  };

  return (
    <div className="fixed top-5 left-1/2 -translate-x-1/2 z-[100] pointer-events-none px-4 max-w-[92vw]">
      <div className="liquid-glass flex items-center gap-2.5 px-4 py-2.5 rounded-full shadow-2xl border border-indigo-500/30">
        <i className={`${iconMap[type]} text-sm shrink-0`}></i>
        <span className="text-xs font-bold text-main truncate">{message}</span>
      </div>
    </div>
  );
};
