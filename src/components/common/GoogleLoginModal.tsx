import React, { useState } from 'react';
import { UserProfile } from '../../types';

interface GoogleLoginModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (user: UserProfile) => void;
}

export const GoogleLoginModal: React.FC<GoogleLoginModalProps> = ({
  isOpen,
  onClose,
  onConfirm
}) => {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');

  if (!isOpen) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const finalName = name.trim() || 'Đạo Hữu Tu Tiên';
    const finalEmail = email.trim() || 'daohuu.donghua3d@gmail.com';
    onConfirm({
      name: finalName,
      email: finalEmail,
      avatar: finalName.charAt(0).toUpperCase(),
      lastSync: Date.now()
    });
    onClose();
  };

  return (
    <div className="fixed inset-0 z-[70] bg-slate-950/60 backdrop-blur-md flex items-center justify-center p-4">
      <div className="liquid-glass rounded-3xl max-w-md w-full p-6 space-y-5 shadow-2xl">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-gradient-to-br from-rose-500 via-amber-500 to-sky-500 p-0.5 shadow">
              <div className="w-full h-full rounded-[14px] bg-white dark:bg-slate-900 flex items-center justify-center">
                <i className="fa-brands fa-google text-rose-500 text-lg"></i>
              </div>
            </div>
            <div>
              <h3 className="font-heading font-extrabold text-base text-main">
                Đăng nhập Google Cloud
              </h3>
              <p className="text-[11px] text-muted">
                Đồng bộ lịch sử xem, tủ phim & nguồn web
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-full liquid-pill flex items-center justify-center text-muted hover:text-main tv-focus-target"
          >
            <i className="fa-solid fa-xmark text-xs"></i>
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-3.5">
          <div>
            <label className="block text-xs font-bold text-main mb-1">
              Tên hiển thị
            </label>
            <input
              type="text"
              placeholder="VD: Đạo Hữu Tu Tiên"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full px-3.5 py-2.5 rounded-xl text-xs liquid-input tv-focus-target"
            />
          </div>

          <div>
            <label className="block text-xs font-bold text-main mb-1">
              Email Google
            </label>
            <input
              type="email"
              placeholder="VD: daohuu.donghua3d@gmail.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full px-3.5 py-2.5 rounded-xl text-xs liquid-input tv-focus-target"
            />
          </div>

          <div className="flex items-center justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl liquid-pill text-xs font-bold text-muted tv-focus-target"
            >
              Hủy
            </button>
            <button
              type="submit"
              className="px-5 py-2 rounded-xl bg-gradient-to-r from-indigo-600 to-cyan-500 text-white text-xs font-extrabold shadow-md shadow-indigo-500/25 hover:brightness-110 transition-all tv-focus-target"
            >
              <i className="fa-brands fa-google mr-1.5"></i>
              Xác nhận đăng nhập
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
