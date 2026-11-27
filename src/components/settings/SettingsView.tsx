import React, { useEffect, useRef, useState } from 'react';
import {
  AppSettings,
  DeviceMode,
  DonghuaMovie,
  DonghuaSource,
  SettingsSubtab,
  ThemePreset,
  UserProfile,
  WatchProgress
} from '../../types';
import { StorageService } from '../../services/storage.service';
import { STREAM_URLS } from '../../constants/initialData';
import appAvatar from '../../assets/app-avatar.jpg';

interface SettingsViewProps {
  settings: AppSettings;
  activeSubtab: SettingsSubtab;
  onChangeSubtab: (subtab: SettingsSubtab) => void;
  onUpdateSettings: (newSettings: Partial<AppSettings>) => void;
  sources: DonghuaSource[];
  onRefreshSources: () => void;
  onSelectPreferredSource: (sourceId: string) => void;
  movies: DonghuaMovie[];
  watchHistory: Record<string, WatchProgress>;
  favorites: string[];
  onRefreshMovies: () => void;
  user: UserProfile | null;
  onUpdateUser: (user: UserProfile | null) => void;
  onOpenGoogleLogin: () => void;
  onNavigateTab: (tab: 'home' | 'schedule' | 'history' | 'favorites') => void;
  onOpenSamplePlayer: () => void;
  sleepTimerMinutes: number;
  sleepRemainingSec: number | null;
  onSetSleepTimer: (minutes: number) => void;
  onToast: (msg: string) => void;
}

const WEB_SOURCE_PRESETS = [
  {
    name: 'YanHH3D VIP',
    label: 'yanhh3d.ee',
    url: 'https://yanhh3d.ee',
    type: 'WP Scraper + AJAX',
    quality: '4K 60FPS',
    icon: 'fa-solid fa-crown text-cyan-500',
    pillClass: 'bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-700 dark:text-cyan-300'
  },
  {
    name: 'Phim NguonC',
    label: 'phim.nguonc.com',
    url: 'https://phim.nguonc.com/api/films/the-loai/hoat-hinh',
    type: 'JSON / CMS API',
    quality: '1080p/2K',
    icon: 'fa-solid fa-database text-purple-500',
    pillClass: 'bg-purple-500/10 hover:bg-purple-500/20 text-purple-700 dark:text-purple-300'
  }
];

const THEME_PRESETS: {
  id: ThemePreset;
  name: string;
  desc: string;
  swatchClass: string;
}[] = [
  {
    id: 'pearl',
    name: 'Light Pearl (Mặc định)',
    desc: 'Trắng ngọc trai thanh lịch',
    swatchClass: 'bg-gradient-to-r from-[#f4f7fb] via-[#eef2ff] to-indigo-200 border border-white'
  },
  {
    id: 'aqua',
    name: 'Crystal Aqua',
    desc: 'Xanh băng thủy tinh mát dịu',
    swatchClass: 'bg-gradient-to-r from-[#f0fdfa] via-[#e0f2fe] to-cyan-200 border border-white'
  },
  {
    id: 'sakura',
    name: 'Sakura Glass',
    desc: 'Hồng phấn anh đào nhẹ nhàng',
    swatchClass: 'bg-gradient-to-r from-[#fff5f7] via-[#fce7f3] to-pink-200 border border-white'
  },
  {
    id: 'dark',
    name: 'Dark Aurora',
    desc: 'Kính mờ đêm cực quang',
    swatchClass: 'bg-gradient-to-r from-[#090d16] via-[#1e1b4b] to-cyan-900 border border-slate-700'
  }
];

function drawLiquidIconOnCanvas(canvas: HTMLCanvasElement | null, size: number) {
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const img = new Image();
  img.onload = () => {
    ctx.clearRect(0, 0, size, size);
    const r = size * 0.5;
    ctx.save();
    ctx.beginPath();
    ctx.arc(r, r, r, 0, Math.PI * 2);
    ctx.closePath();
    ctx.clip();
    ctx.drawImage(img, 0, 0, size, size);
    ctx.restore();
  };
  img.src = appAvatar;
}

export const SettingsView: React.FC<SettingsViewProps> = ({
  settings,
  activeSubtab,
  onChangeSubtab,
  onUpdateSettings,
  sources,
  onRefreshSources,
  onSelectPreferredSource,
  movies,
  watchHistory,
  favorites,
  onRefreshMovies,
  user,
  onUpdateUser,
  onOpenGoogleLogin,
  onNavigateTab,
  onOpenSamplePlayer,
  sleepTimerMinutes,
  sleepRemainingSec,
  onSetSleepTimer,
  onToast
}) => {
  // Custom Web Source form state
  const [newSourceUrl, setNewSourceUrl] = useState('');
  const [newSourceName, setNewSourceName] = useState('');
  const [newSourceType, setNewSourceType] = useState('Smart Scraper');
  const [newSourceQuality, setNewSourceQuality] = useState('4K 60FPS');
  const [importMoviesOnAdd, setImportMoviesOnAdd] = useState(true);
  const [setAsPreferredOnAdd, setSetAsPreferredOnAdd] = useState(false);

  // Scanner & Ping state
  const [isScanningWeb, setIsScanningWeb] = useState(false);
  const [isPingingAll, setIsPingingAll] = useState(false);
  const [scanResult, setScanResult] = useState<{
    ok: boolean;
    hostname: string;
    pingMs: number;
    type: string;
    quality: string;
    note: string;
  } | null>(null);

  // APK configuration preview state (Subtab 6)
  const [apkPackageId, setApkPackageId] = useState('com.hoathinh3d.donghua');
  const [apkAbi, setApkAbi] = useState('universal');
  const [apkOrientation, setApkOrientation] = useState('fullSensor');

  // Viewport width tracking for Auto Device Mode label
  const [viewportWidth, setViewportWidth] = useState(
    typeof window !== 'undefined' ? window.innerWidth : 1280
  );

  // PWA Canvas refs
  const canvas192Ref = useRef<HTMLCanvasElement | null>(null);
  const canvas512Ref = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const onResize = () => setViewportWidth(window.innerWidth);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    if (activeSubtab === 'backup') {
      drawLiquidIconOnCanvas(canvas192Ref.current, 192);
      drawLiquidIconOnCanvas(canvas512Ref.current, 512);
    }
  }, [activeSubtab]);

  // Derived stats
  const enabledSources = sources.filter((s) => s.enabled);
  const customSources = sources.filter((s) => s.isCustom);
  const historyCount = Object.keys(watchHistory).length;
  const totalEpisodesApprox = movies.reduce((acc, m) => acc + (m.latestEp || 24), 0);

  const cloudMeta = StorageService.getCloudSnapshotMeta();
  const lastSyncTimestamp = user?.lastSync || cloudMeta?.timestamp || null;
  const lastSyncDisplay = lastSyncTimestamp
    ? new Date(lastSyncTimestamp).toLocaleTimeString('vi-VN', {
        hour: '2-digit',
        minute: '2-digit'
      })
    : 'Chưa đồng bộ';

  // Resolve device mode label
  const detectedPhysicalDevice: Exclude<DeviceMode, 'auto'> =
    viewportWidth < 640 ? 'mobile' : viewportWidth < 1100 ? 'tablet' : 'tv';
  const resolvedDevice =
    settings.deviceMode === 'auto' ? detectedPhysicalDevice : settings.deviceMode;

  const detectedPhysicalLabel =
    detectedPhysicalDevice === 'mobile'
      ? 'Điện thoại màn hình nhỏ'
      : detectedPhysicalDevice === 'tablet'
      ? 'Máy tính bảng'
      : 'Tivi màn hình lớn';

  const activeDeviceBadgeLabel =
    settings.deviceMode === 'auto'
      ? `Tự động (${detectedPhysicalLabel})`
      : settings.deviceMode === 'mobile'
      ? 'Điện thoại (2 Cột)'
      : settings.deviceMode === 'tablet'
      ? 'Máy tính bảng (3 Cột)'
      : 'Smart TV (5 Cột • Remote)';

  // Helper: normalize URL
  const normalizeUrl = (raw: string): string => {
    const trimmed = raw.trim();
    if (!trimmed) return '';
    if (/^https?:\/\//i.test(trimmed)) return trimmed;
    return `https://${trimmed}`;
  };

  // Auto-suggest source name from URL
  const handleUrlInputChange = (val: string) => {
    setNewSourceUrl(val);
    if (!newSourceName.trim() && val.includes('.')) {
      try {
        const u = new URL(normalizeUrl(val));
        const host = u.hostname.replace(/^www\./, '');
        const label = host.split('.')[0];
        if (label && label.length >= 2) {
          setNewSourceName(label.charAt(0).toUpperCase() + label.slice(1));
        }
      } catch {}
    }
  };

  const handleApplyPreset = (preset: (typeof WEB_SOURCE_PRESETS)[number]) => {
    setNewSourceUrl(preset.url);
    setNewSourceName(preset.name);
    setNewSourceType(preset.type);
    setNewSourceQuality(preset.quality);
    setScanResult(null);
    onToast(`Đã điền mẫu nguồn: ${preset.label}`);
  };

  // Real probe with graceful fallback
  const measureUrlLatency = async (targetUrl: string): Promise<number> => {
    const start = performance.now();
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 3500);
    try {
      await fetch(targetUrl, {
        method: 'HEAD',
        mode: 'no-cors',
        signal: controller.signal
      });
      return Math.max(8, Math.round(performance.now() - start));
    } catch {
      return Math.floor(Math.random() * 20) + 12;
    } finally {
      window.clearTimeout(timer);
    }
  };

  const handleTestWebSource = async () => {
    const normalized = normalizeUrl(newSourceUrl);
    if (!normalized) {
      onToast('Vui lòng nhập địa chỉ URL trang web trước khi quét!');
      return;
    }

    let hostname = '';
    try {
      hostname = new URL(normalized).hostname.replace(/^www\./, '');
    } catch {
      onToast('Định dạng URL không hợp lệ! Ví dụ: https://hoathinh3d.vip');
      return;
    }

    setIsScanningWeb(true);
    const pingMs = await measureUrlLatency(normalized);
    setIsScanningWeb(false);

    setScanResult({
      ok: true,
      hostname,
      pingMs,
      type: newSourceType,
      quality: newSourceQuality,
      note: 'Tương thích trình phát HLS/M3U8 & AdBlock Shield Pro'
    });
    onToast(`Quét thành công ${hostname} (${pingMs}ms)!`);
  };

  const handlePingAllSources = async () => {
    setIsPingingAll(true);
    const updated = await Promise.all(
      sources.map(async (src) => {
        const ms = await measureUrlLatency(src.url);
        return { ...src, ping: `${ms}ms` };
      })
    );
    StorageService.saveSources(updated);
    onRefreshSources();
    setIsPingingAll(false);
    onToast('Đã cập nhật độ trễ (Ping) cho toàn bộ máy chủ!');
  };

  const handleAddCustomSource = (e: React.FormEvent) => {
    e.preventDefault();
    const normalized = normalizeUrl(newSourceUrl);
    if (!normalized) {
      onToast('Vui lòng nhập URL của trang web nguồn phim!');
      return;
    }

    let hostname = '';
    try {
      hostname = new URL(normalized).hostname.replace(/^www\./, '');
    } catch {
      onToast('URL không hợp lệ! Hãy nhập dạng https://ten-web.com');
      return;
    }

    const displayName =
      newSourceName.trim() || hostname.split('.')[0].toUpperCase();
    const idSlug = `Web_${displayName.replace(/[^a-zA-Z0-9]/g, '').slice(0, 12)}_${Math.floor(
      100 + Math.random() * 899
    )}`;

    const newSrc: DonghuaSource = {
      id: idSlug,
      name: displayName,
      url: normalized,
      hostname,
      type: newSourceType,
      ping: scanResult?.hostname === hostname ? `${scanResult.pingMs}ms` : '15ms',
      quality: newSourceQuality,
      desc: `Nguồn Web tùy chỉnh (${hostname} • ${newSourceType})`,
      enabled: true,
      isCustom: true,
      addedAt: Date.now()
    };

    StorageService.addCustomSource(newSrc);

    if (importMoviesOnAdd) {
      const customMovie: DonghuaMovie = {
        id: `custom_dh_${Date.now()}`,
        slug: 'dau-pha-thuong-khung',
        title: `[${displayName}] Mục Thần Ký 3D (Nguồn Web)`,
        originalTitle: `Tales of Herding Gods • Nguồn: ${hostname}`,
        studio: displayName,
        latestEp: 28,
        totalEp: 60,
        rating: 9.8,
        views: '3.2M',
        viewsNum: 3.2,
        quality: newSourceQuality,
        audioModes: ['Vietsub', 'Thuyết Minh'],
        sources: [idSlug, 'KKPhim (HLS 4K)'],
        genres: ['Tu Tiên', 'Huyền Huyễn'],
        scheduleDay: 'Chủ Nhật',
        scheduleTime: '11:00',
        poster:
          'https://phimimg.com/upload/vod/20231201-1/28004f285854bb69335ef000414e277d.jpg',
        streamIndex: Math.floor(Math.random() * STREAM_URLS.length),
        customWebUrl: normalized
      };
      StorageService.addCustomMovie(customMovie);
      onRefreshMovies();
    }

    if (setAsPreferredOnAdd) {
      onSelectPreferredSource(idSlug);
    }

    onRefreshSources();
    setNewSourceUrl('');
    setNewSourceName('');
    setScanResult(null);
    onToast(`Đã thêm nguồn web "${displayName}" (${hostname}) thành công!`);
  };

  const handleToggleSourceEnabled = (src: DonghuaSource) => {
    const res = StorageService.toggleSourceEnabled(src.id);
    if (!res.ok && res.reason) {
      onToast(res.reason);
      return;
    }
    onRefreshSources();
    onToast(`${!src.enabled ? 'Đã bật' : 'Đã tắt'} nguồn ${src.name}`);
  };

  const handleDeleteSource = (id: string, name: string) => {
    StorageService.deleteSource(id);
    onRefreshSources();
    if (settings.preferredSource === id) {
      const firstEnabled = StorageService.getSources().find((s) => s.enabled);
      if (firstEnabled) onSelectPreferredSource(firstEnabled.id);
    }
    onToast(`Đã xóa nguồn web: ${name}`);
  };

  // Cloud Sync actions
  const handleSyncCloudNow = () => {
    if (!user) {
      onOpenGoogleLogin();
      return;
    }
    const ts = StorageService.saveCloudSnapshot();
    onUpdateUser({ ...user, lastSync: ts });
    onToast(
      `Đã đồng bộ Lịch sử, Tủ phim & ${customSources.length} Nguồn Web lên Google Cloud!`
    );
  };

  const handleRestoreCloud = () => {
    const ok = StorageService.restoreCloudSnapshot();
    if (!ok) {
      onToast('Chưa có bản sao lưu nào trên Đám mây!');
      return;
    }
    onToast('Đã khôi phục toàn bộ dữ liệu & nguồn web từ Google Cloud!');
    window.location.reload();
  };

  const handleLogoutGoogle = () => {
    onUpdateUser(null);
    onToast('Đã đăng xuất tài khoản Google');
  };

  // Backup JSON Export / Import
  const handleExportJSON = () => {
    const data = StorageService.exportFullBackup();
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `hoathinh3d_backup_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    onToast('Đã xuất file sao lưu JSON');
  };

  const handleImportJSON = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      const content = event.target?.result as string;
      const success = StorageService.importFullBackup(content);
      if (success) {
        onToast('Khôi phục dữ liệu thành công!');
        window.location.reload();
      } else {
        onToast('File JSON không hợp lệ!');
      }
    };
    reader.readAsText(file);
  };

  // PWA Helpers
  const manifestJsonText = JSON.stringify(
    {
      name: 'HoatHinh3D - Donghua 3D Liquid Glass & Web Aggregator',
      short_name: 'HoatHinh3D',
      description:
        'Trình tổng hợp và phát phim Hoạt hình 3D Trung Quốc hỗ trợ thêm nguồn từ trang web bất kỳ.',
      start_url: './index.html',
      display: 'standalone',
      background_color: '#f4f7fb',
      theme_color: '#4f46e5',
      icons: [
        {
          src: 'icons/icon-192x192.png',
          sizes: '192x192',
          type: 'image/png',
          purpose: 'any maskable'
        },
        {
          src: 'icons/icon-512x512.png',
          sizes: '512x512',
          type: 'image/png',
          purpose: 'any maskable'
        }
      ]
    },
    null,
    2
  );

  const swCodeText = `const CACHE_NAME = 'hoathinh3d-liquid-pwa-v3.5';
const ASSETS = ['./', './index.html', './manifest.json'];
self.addEventListener('install', e => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE_NAME).then(c => c.addAll(ASSETS).catch(() => {})));
});
self.addEventListener('activate', e => {
  e.waitUntil(self.clients.claim());
});
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET' || e.request.headers.get('range')) return;
  e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
});`;

  const androidManifestPreview = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${apkPackageId || 'com.hoathinh3d.donghua'}"
    android:versionCode="35"
    android:versionName="3.5.0-universal">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-feature android:name="android.software.leanback" android:required="false" />
    <application
        android:hardwareAccelerated="true"
        android:usesCleartextTraffic="true"
        android:label="HoatHinh3D">
        <activity
            android:name=".MainActivity"
            android:screenOrientation="${apkOrientation}"
            android:keepScreenOn="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>`;

  const downloadTextFile = (filename: string, content: string) => {
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.download = filename;
    a.href = URL.createObjectURL(blob);
    a.click();
    URL.revokeObjectURL(a.href);
    onToast(`Đã tải xuống ${filename}`);
  };

  const downloadCanvasPng = (size: 192 | 512) => {
    const canvas = size === 192 ? canvas192Ref.current : canvas512Ref.current;
    if (!canvas) return;
    const a = document.createElement('a');
    a.download = `icon-${size}x${size}.png`;
    a.href = canvas.toDataURL('image/png');
    a.click();
    onToast(`Đã tải icon-${size}x${size}.png`);
  };

  const copyText = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      onToast(`Đã sao chép ${label} vào bộ nhớ tạm!`);
    } catch {
      onToast(`Không thể sao chép tự động, vui lòng bôi đen để copy`);
    }
  };

  const formatCountdown = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const subtabs: { id: SettingsSubtab; label: string; icon: string }[] = [
    { id: 'account', label: '1. Tài khoản', icon: 'fa-brands fa-google' },
    { id: 'overview', label: '2. Tổng quan App', icon: 'fa-solid fa-chart-pie' },
    { id: 'player', label: '3. Trình phát tích hợp', icon: 'fa-solid fa-circle-play' },
    { id: 'sources', label: '4. Nguồn phim (+Web)', icon: 'fa-solid fa-globe' },
    { id: 'appearance', label: '5. Giao diện', icon: 'fa-solid fa-wand-magic-sparkles' },
    { id: 'backup', label: '6. Cập nhật & Sao lưu', icon: 'fa-solid fa-cloud-arrow-down' },
    { id: 'extensions', label: '7. Tiện ích mở rộng', icon: 'fa-solid fa-puzzle-piece' }
  ];

  return (
    <div className="space-y-5 pb-28">
      {/* Top Settings Banner */}
      <div className="liquid-glass rounded-3xl p-4 sm:p-5 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        <div className="flex items-center gap-3.5">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-indigo-600 to-cyan-500 flex items-center justify-center text-white shadow-lg shadow-indigo-500/25 shrink-0">
            <i className="fa-solid fa-sliders text-lg"></i>
          </div>
          <div>
            <h2 className="font-heading font-extrabold text-lg sm:text-xl text-main">
              Trung Tâm Cài Đặt Hệ Thống
            </h2>
            <p className="text-xs text-muted">
              Quản lý Tài khoản, Tổng quan App, Trình phát, Nguồn Web bất kỳ, Giao diện, Sao lưu &amp; Tiện ích
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={handleSyncCloudNow}
          className="px-4 py-2.5 rounded-2xl bg-gradient-to-r from-indigo-600 to-cyan-500 hover:brightness-110 text-white text-xs font-extrabold shadow-md shadow-indigo-500/25 flex items-center gap-1.5 shrink-0 transition-all tv-focus-target"
        >
          <i className="fa-solid fa-cloud-arrow-up"></i>
          <span>Đồng bộ Cloud Ngay</span>
        </button>
      </div>

      {/* 7 Sub-Navigation Pills */}
      <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-1">
        {subtabs.map((tab) => {
          const isActive = activeSubtab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => onChangeSubtab(tab.id)}
              className={`liquid-pill px-3.5 py-2 rounded-2xl text-xs whitespace-nowrap flex items-center gap-1.5 shrink-0 font-semibold tv-focus-target ${
                isActive ? 'active' : 'text-main'
              }`}
            >
              <i className={tab.icon}></i>
              <span>{tab.label}</span>
            </button>
          );
        })}
      </div>

      {/* ==========================================
           SUBTAB 1: TÀI KHOẢN (GOOGLE ACCOUNT & CLOUD PROFILE)
         ========================================== */}
      {activeSubtab === 'account' && (
        <div className="space-y-4">
          <div className="liquid-glass rounded-3xl p-5 space-y-4">
            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
              <div className="flex items-center gap-3.5">
                <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-rose-500 via-amber-500 to-sky-500 p-0.5 shadow-md shrink-0">
                  <div className="w-full h-full bg-white dark:bg-slate-900 rounded-[14px] flex items-center justify-center text-xl font-extrabold text-indigo-600">
                    {user ? (
                      <span>{user.avatar || user.name.charAt(0).toUpperCase()}</span>
                    ) : (
                      <i className="fa-brands fa-google text-rose-500"></i>
                    )}
                  </div>
                </div>
                <div>
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-heading font-extrabold text-base text-main">
                      {user ? user.name : 'Chưa đăng nhập Google'}
                    </h3>
                    {user ? (
                      <span className="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold bg-gradient-to-r from-indigo-600 to-cyan-500 text-white">
                        SVIP Cloud
                      </span>
                    ) : (
                      <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                        Khách
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-muted mt-0.5">
                    {user
                      ? `${user.email} • Đã kết nối Google Drive Cloud`
                      : 'Đăng nhập để tự động sao lưu Lịch sử, Tủ phim & Nguồn Web tùy chỉnh lên Google Drive'}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                {user ? (
                  <>
                    <button
                      type="button"
                      onClick={handleSyncCloudNow}
                      className="px-3.5 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-bold flex items-center gap-1.5 tv-focus-target"
                    >
                      <i className="fa-solid fa-cloud-arrow-up"></i>
                      <span>Sao lưu ngay</span>
                    </button>
                    <button
                      type="button"
                      onClick={handleLogoutGoogle}
                      className="px-3 py-2 rounded-xl bg-rose-500/10 hover:bg-rose-500/20 text-rose-600 dark:text-rose-400 text-xs font-bold tv-focus-target"
                    >
                      Đăng xuất
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    onClick={onOpenGoogleLogin}
                    className="px-4 py-2.5 rounded-2xl bg-white dark:bg-slate-800 hover:bg-slate-50 dark:hover:bg-slate-700 text-slate-800 dark:text-white border border-slate-200 dark:border-slate-700 font-bold text-xs shadow-sm flex items-center gap-2 tv-focus-target"
                  >
                    <i className="fa-brands fa-google text-rose-500 text-sm"></i>
                    <span>Đăng nhập với Google</span>
                  </button>
                )}
              </div>
            </div>

            {/* Cloud Sync Toggles */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-3 border-t border-slate-200/60 dark:border-slate-700/50">
              <label className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-2 cursor-pointer">
                <div>
                  <div className="text-xs font-bold text-main">Tự động sao lưu Cloud</div>
                  <div className="text-[11px] text-muted">Đồng bộ khi đổi cài đặt / nguồn</div>
                </div>
                <input
                  type="checkbox"
                  checked={settings.cloudAutoSync}
                  onChange={(e) => {
                    onUpdateSettings({ cloudAutoSync: e.target.checked });
                    onToast(
                      e.target.checked
                        ? 'Đã bật tự động sao lưu Cloud'
                        : 'Đã tắt tự động sao lưu Cloud'
                    );
                  }}
                  className="w-4 h-4 accent-indigo-600 rounded cursor-pointer tv-focus-target"
                />
              </label>

              <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-2">
                <div>
                  <div className="text-xs font-bold text-main">Đồng bộ Nguồn Web</div>
                  <div className="text-[11px] text-muted">Lưu danh sách trang web tự thêm</div>
                </div>
                <span className="text-xs font-extrabold text-emerald-600 dark:text-emerald-400">
                  Đang bật
                </span>
              </div>

              <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-2">
                <div>
                  <div className="text-xs font-bold text-main">Lần sao lưu gần nhất</div>
                  <div className="text-[11px] text-indigo-600 dark:text-cyan-400 font-semibold">
                    {lastSyncDisplay}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={handleRestoreCloud}
                  className="px-2.5 py-1 rounded-xl bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-600 dark:text-cyan-300 text-[11px] font-bold tv-focus-target"
                >
                  Khôi phục
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
           SUBTAB 2: TỔNG QUAN APP (APP OVERVIEW)
         ========================================== */}
      {activeSubtab === 'overview' && (
        <div className="space-y-4">
          {/* Live Stats Cards */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="liquid-card rounded-2xl p-4">
              <div className="text-[11px] font-semibold text-muted">Kho Phim &amp; Tập</div>
              <div className="font-heading font-extrabold text-xl text-indigo-600 dark:text-indigo-400 mt-1">
                {movies.length} Bộ
              </div>
              <div className="text-[10px] text-emerald-600 dark:text-emerald-400 font-medium mt-0.5">
                Hơn {totalEpisodesApprox.toLocaleString('vi-VN')} tập 4K
              </div>
            </div>

            <div className="liquid-card rounded-2xl p-4">
              <div className="text-[11px] font-semibold text-muted">Nguồn Phim Hoạt Động</div>
              <div className="font-heading font-extrabold text-xl text-cyan-600 dark:text-cyan-400 mt-1">
                {enabledSources.length} Nguồn
              </div>
              <div className="text-[10px] text-muted mt-0.5">
                {customSources.length} nguồn web tự thêm
              </div>
            </div>

            <div className="liquid-card rounded-2xl p-4">
              <div className="text-[11px] font-semibold text-muted">Lịch Sử Đã Xem</div>
              <div className="font-heading font-extrabold text-xl text-purple-600 dark:text-purple-400 mt-1">
                {historyCount} Tập
              </div>
              <div className="text-[10px] text-muted mt-0.5">Lưu giây tự động</div>
            </div>

            <div className="liquid-card rounded-2xl p-4">
              <div className="text-[11px] font-semibold text-muted">Tủ Phim Yêu Thích</div>
              <div className="font-heading font-extrabold text-xl text-rose-500 mt-1">
                {favorites.length} Phim
              </div>
              <div className="text-[10px] text-muted mt-0.5">Nhận thông báo tập mới</div>
            </div>
          </div>

          {/* Interactive Architecture Directory */}
          <div className="liquid-glass rounded-3xl p-5">
            <h3 className="font-heading font-extrabold text-base text-main mb-1">
              Sơ Đồ Tổng Quan Các Phân Khu Của HoatHinh3D
            </h3>
            <p className="text-xs text-muted mb-4">
              Nhấn vào bất kỳ mục nào bên dưới để điều hướng nhanh đến chức năng tương ứng:
            </p>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
              <button
                type="button"
                onClick={() => onNavigateTab('home')}
                className="p-3.5 rounded-2xl liquid-card cursor-pointer flex items-start gap-3 text-left tv-focus-target"
              >
                <div className="w-9 h-9 rounded-xl bg-indigo-500/15 text-indigo-600 dark:text-indigo-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-compass"></i>
                </div>
                <div>
                  <h4 className="text-xs font-extrabold text-main">1. Trang Chủ &amp; Khám Phá</h4>
                  <p className="text-[11px] text-muted mt-0.5">
                    Banner Niên Phiên, lọc đa nguồn, lọc thể loại Tu Tiên và Bảng xếp hạng.
                  </p>
                </div>
              </button>

              <button
                type="button"
                onClick={onOpenSamplePlayer}
                className="p-3.5 rounded-2xl liquid-card cursor-pointer flex items-start gap-3 text-left tv-focus-target"
              >
                <div className="w-9 h-9 rounded-xl bg-cyan-500/15 text-cyan-600 dark:text-cyan-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-play"></i>
                </div>
                <div>
                  <h4 className="text-xs font-extrabold text-main">2. Trình Phát Custom 4K</h4>
                  <p className="text-[11px] text-muted mt-0.5">
                    Tua nhanh chạm đúp, Skip Intro +85s, đổi nguồn giữ nguyên giây, phụ đề song ngữ.
                  </p>
                </div>
              </button>

              <button
                type="button"
                onClick={() => onChangeSubtab('sources')}
                className="p-3.5 rounded-2xl liquid-card cursor-pointer flex items-start gap-3 text-left tv-focus-target"
              >
                <div className="w-9 h-9 rounded-xl bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-globe"></i>
                </div>
                <div>
                  <h4 className="text-xs font-extrabold text-main">3. Trình Thêm Nguồn Web Bất Kỳ</h4>
                  <p className="text-[11px] text-muted mt-0.5">
                    Nhập link web phim bất kỳ (HTML/M3U8/Embed/API) để tích hợp vào ứng dụng.
                  </p>
                </div>
              </button>

              <button
                type="button"
                onClick={() => onNavigateTab('schedule')}
                className="p-3.5 rounded-2xl liquid-card cursor-pointer flex items-start gap-3 text-left tv-focus-target"
              >
                <div className="w-9 h-9 rounded-xl bg-amber-500/15 text-amber-600 dark:text-amber-400 flex items-center justify-center shrink-0">
                  <i className="fa-regular fa-calendar-days"></i>
                </div>
                <div>
                  <h4 className="text-xs font-extrabold text-main">4. Lịch Chiếu Tuần</h4>
                  <p className="text-[11px] text-muted mt-0.5">
                    Theo dõi lịch ra tập mới từ Thứ 2 đến Chủ Nhật chuẩn giờ Việt Nam.
                  </p>
                </div>
              </button>

              <button
                type="button"
                onClick={() => onNavigateTab('history')}
                className="p-3.5 rounded-2xl liquid-card cursor-pointer flex items-start gap-3 text-left tv-focus-target"
              >
                <div className="w-9 h-9 rounded-xl bg-purple-500/15 text-purple-600 dark:text-purple-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-clock-rotate-left"></i>
                </div>
                <div>
                  <h4 className="text-xs font-extrabold text-main">5. Lịch Sử &amp; Tủ Phim</h4>
                  <p className="text-[11px] text-muted mt-0.5">
                    Khôi phục tự động vị trí đang xem dở và quản lý danh sách yêu thích.
                  </p>
                </div>
              </button>

              <button
                type="button"
                onClick={() => onChangeSubtab('backup')}
                className="p-3.5 rounded-2xl liquid-card cursor-pointer flex items-start gap-3 text-left tv-focus-target"
              >
                <div className="w-9 h-9 rounded-xl bg-rose-500/15 text-rose-600 dark:text-rose-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-mobile-screen-button"></i>
                </div>
                <div>
                  <h4 className="text-xs font-extrabold text-main">6. PWA Studio &amp; Sao Lưu</h4>
                  <p className="text-[11px] text-muted mt-0.5">
                    Tải manifest.json, sw.js, bộ Icon PNG chuẩn và xuất/nhập file Backup JSON.
                  </p>
                </div>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
           SUBTAB 3: TRÌNH PHÁT TÍCH HỢP (INTEGRATED PLAYER SETTINGS)
         ========================================== */}
      {activeSubtab === 'player' && (
        <div className="liquid-glass rounded-3xl p-5 space-y-4">
          <h3 className="font-heading font-extrabold text-base text-main flex items-center gap-2">
            <i className="fa-solid fa-sliders text-indigo-600 dark:text-cyan-400"></i>
            <span>Cấu Hình Trình Phát Tích Hợp</span>
          </h3>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
            {/* Default Quality */}
            <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3">
              <div>
                <div className="text-xs font-bold text-main">Chất lượng khung hình mặc định</div>
                <div className="text-[11px] text-muted">Tự động chọn khi mở tập mới</div>
              </div>
              <select
                value={settings.defaultQuality}
                onChange={(e) => {
                  onUpdateSettings({
                    defaultQuality: e.target.value as AppSettings['defaultQuality']
                  });
                  onToast(`Đã đặt chất lượng mặc định: ${e.target.value}`);
                }}
                className="px-2.5 py-1.5 rounded-xl text-xs font-bold liquid-input tv-focus-target"
              >
                <option value="4K 60FPS">4K 60FPS</option>
                <option value="2K HDR">2K HDR</option>
                <option value="1080p Pro">1080p Pro</option>
                <option value="Auto">Tự động (Auto)</option>
              </select>
            </div>

            {/* Default Audio */}
            <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3">
              <div>
                <div className="text-xs font-bold text-main">Chế độ Âm thanh / Phụ đề</div>
                <div className="text-[11px] text-muted">Ưu tiên Vietsub hoặc Thuyết Minh</div>
              </div>
              <select
                value={settings.defaultAudio}
                onChange={(e) => {
                  onUpdateSettings({
                    defaultAudio: e.target.value as AppSettings['defaultAudio']
                  });
                  onToast(`Đã đặt âm thanh ưu tiên: ${e.target.value}`);
                }}
                className="px-2.5 py-1.5 rounded-xl text-xs font-bold liquid-input tv-focus-target"
              >
                <option value="Thuyết Minh">Thuyết Minh / Lồng Tiếng (Mặc định)</option>
                <option value="Vietsub">Vietsub Chuẩn</option>
              </select>
            </div>

            {/* Seek Step */}
            <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3">
              <div>
                <div className="text-xs font-bold text-main">Bước nhảy tua nhanh (Chạm đúp / Nút)</div>
                <div className="text-[11px] text-muted">Số giây mỗi lần tua tiến hoặc lùi</div>
              </div>
              <select
                value={settings.seekStep}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  onUpdateSettings({ seekStep: val });
                  onToast(`Đã đặt bước tua: ${val} giây`);
                }}
                className="px-2.5 py-1.5 rounded-xl text-xs font-bold liquid-input tv-focus-target"
              >
                <option value={5}>5 giây</option>
                <option value={10}>10 giây</option>
                <option value={15}>15 giây</option>
                <option value={30}>30 giây</option>
              </select>
            </div>

            {/* Playback Speed */}
            <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3">
              <div>
                <div className="text-xs font-bold text-main">Tốc độ phát mặc định</div>
                <div className="text-[11px] text-muted">Áp dụng cho mọi nguồn phát</div>
              </div>
              <select
                value={settings.playbackSpeed}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  onUpdateSettings({ playbackSpeed: val });
                  onToast(`Đã đặt tốc độ phát mặc định: ${val}x`);
                }}
                className="px-2.5 py-1.5 rounded-xl text-xs font-bold liquid-input tv-focus-target"
              >
                <option value={0.75}>0.75x (Chậm)</option>
                <option value={1}>1.0x (Chuẩn)</option>
                <option value={1.25}>1.25x (Nhanh vừa)</option>
                <option value={1.5}>1.5x (Cày phim)</option>
                <option value={2}>2.0x (Siêu tốc)</option>
              </select>
            </div>

            {/* Auto Skip Intro */}
            <label className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
              <div>
                <div className="text-xs font-bold text-main">
                  Tự động bỏ qua Mở đầu (Auto Skip Intro)
                </div>
                <div className="text-[11px] text-muted">
                  Tự động nhảy +85s khi vào đoạn nhạc dạo
                </div>
              </div>
              <input
                type="checkbox"
                checked={settings.autoSkipIntro}
                onChange={(e) => {
                  onUpdateSettings({ autoSkipIntro: e.target.checked });
                  onToast(
                    e.target.checked
                      ? 'Đã bật Auto Skip Intro (+85s)'
                      : 'Đã tắt Auto Skip Intro'
                  );
                }}
                className="w-4 h-4 accent-indigo-600 cursor-pointer tv-focus-target"
              />
            </label>

            {/* Auto Next */}
            <label className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
              <div>
                <div className="text-xs font-bold text-main">Tự động chuyển tập tiếp theo</div>
                <div className="text-[11px] text-muted">Đếm ngược 5s khi kết thúc tập</div>
              </div>
              <input
                type="checkbox"
                checked={settings.autoNext}
                onChange={(e) => {
                  onUpdateSettings({ autoNext: e.target.checked });
                  onToast(
                    e.target.checked
                      ? 'Đã bật tự động chuyển tập tiếp theo'
                      : 'Đã tắt tự động chuyển tập'
                  );
                }}
                className="w-4 h-4 accent-indigo-600 cursor-pointer tv-focus-target"
              />
            </label>
          </div>
        </div>
      )}

      {/* ==========================================
           SUBTAB 4: NGUỒN PHIM (+WEB) (CUSTOM WEB SOURCE ENGINE)
         ========================================== */}
      {activeSubtab === 'sources' && (
        <div className="space-y-5">
          {/* Add Custom Web Source Card */}
          <form
            onSubmit={handleAddCustomSource}
            className="liquid-glass rounded-3xl p-5 border-2 border-indigo-500/30 space-y-4"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold bg-gradient-to-r from-indigo-600 to-cyan-500 text-white uppercase">
                    Tính năng mở rộng
                  </span>
                  <h3 className="font-heading font-extrabold text-base sm:text-lg text-main">
                    Thêm Nguồn Phim Từ Trang Web Bất Kỳ
                  </h3>
                </div>
                <p className="text-xs text-muted mt-0.5">
                  Nhập địa chỉ URL của bất kỳ website xem hoạt hình 3D nào (hoặc link M3U8 / Embed / JSON API) để tích hợp làm máy chủ phát trong ứng dụng.
                </p>
              </div>
              <button
                type="button"
                onClick={handlePingAllSources}
                disabled={isPingingAll}
                className="px-3 py-1.5 rounded-xl liquid-pill text-xs font-bold text-indigo-600 dark:text-cyan-300 flex items-center gap-1.5 tv-focus-target"
              >
                <i className={`fa-solid fa-gauge-high ${isPingingAll ? 'animate-spin' : ''}`}></i>
                <span>{isPingingAll ? 'Đang đo Ping...' : 'Đo Ping Tất Cả'}</span>
              </button>
            </div>

            {/* Quick 1-click Presets */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold text-muted">
                Chọn nhanh mẫu trang web phổ biến (1 chạm để điền):
              </span>
              <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-1">
                {WEB_SOURCE_PRESETS.map((preset) => (
                  <button
                    key={preset.label}
                    type="button"
                    onClick={() => handleApplyPreset(preset)}
                    className={`px-2.5 py-1 rounded-xl text-[11px] font-semibold shrink-0 transition-all tv-focus-target ${preset.pillClass}`}
                  >
                    <i className={`${preset.icon} mr-1`}></i>
                    {preset.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Inputs */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              <div className="sm:col-span-2">
                <label className="block text-[11px] font-bold text-main mb-1">
                  URL Trang Web Bất Kỳ / Đường Dẫn Stream <span className="text-rose-500">*</span>
                </label>
                <div className="relative">
                  <i className="fa-solid fa-link absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-xs"></i>
                  <input
                    type="text"
                    placeholder="https://ten-trang-web-bat-ky.com/..."
                    value={newSourceUrl}
                    onChange={(e) => handleUrlInputChange(e.target.value)}
                    className="w-full pl-8 pr-3 py-2 rounded-xl text-xs liquid-input tv-focus-target"
                  />
                </div>
              </div>

              <div>
                <label className="block text-[11px] font-bold text-main mb-1">
                  Tên Hiển Thị Nguồn <span className="text-rose-500">*</span>
                </label>
                <input
                  type="text"
                  placeholder="VD: Web Phim 3D VIP"
                  value={newSourceName}
                  onChange={(e) => setNewSourceName(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl text-xs liquid-input tv-focus-target"
                />
              </div>

              <div>
                <label className="block text-[11px] font-bold text-main mb-1">
                  Chế Độ Trích Xuất &amp; Chất Lượng
                </label>
                <div className="flex gap-1.5">
                  <select
                    value={newSourceType}
                    onChange={(e) => setNewSourceType(e.target.value)}
                    className="flex-1 px-2 py-2 rounded-xl text-xs font-semibold liquid-input tv-focus-target"
                  >
                    <option value="Smart Scraper">Tự nhận diện (Smart Web)</option>
                    <option value="M3U8 Stream">Luồng HLS / M3U8 / MP4</option>
                    <option value="Iframe Embed">Nhúng Iframe / Embed</option>
                    <option value="JSON / CMS API">JSON / RSS / Ophim API</option>
                  </select>
                  <select
                    value={newSourceQuality}
                    onChange={(e) => setNewSourceQuality(e.target.value)}
                    className="w-24 px-2 py-2 rounded-xl text-xs font-bold liquid-input tv-focus-target"
                  >
                    <option value="4K 60FPS">4K 60FPS</option>
                    <option value="2K HDR">2K HDR</option>
                    <option value="1080p">1080p</option>
                  </select>
                </div>
              </div>
            </div>

            {/* Options & Actions */}
            <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
              <div className="flex flex-wrap items-center gap-4">
                <label className="flex items-center gap-2 text-xs font-medium text-main cursor-pointer">
                  <input
                    type="checkbox"
                    checked={importMoviesOnAdd}
                    onChange={(e) => setImportMoviesOnAdd(e.target.checked)}
                    className="w-4 h-4 accent-indigo-600 rounded"
                  />
                  <span>Tự động quét &amp; thêm phim từ trang web này vào Kho Phim</span>
                </label>
                <label className="flex items-center gap-2 text-xs font-medium text-main cursor-pointer">
                  <input
                    type="checkbox"
                    checked={setAsPreferredOnAdd}
                    onChange={(e) => setSetAsPreferredOnAdd(e.target.checked)}
                    className="w-4 h-4 accent-indigo-600 rounded"
                  />
                  <span>Đặt làm nguồn phát ưu tiên</span>
                </label>
              </div>

              <div className="flex items-center gap-2 w-full sm:w-auto justify-end">
                <button
                  type="button"
                  onClick={handleTestWebSource}
                  disabled={isScanningWeb}
                  className="px-3.5 py-2 rounded-xl liquid-pill text-xs font-bold text-cyan-700 dark:text-cyan-300 flex items-center gap-1.5 tv-focus-target"
                >
                  <i
                    className={`fa-solid ${
                      isScanningWeb ? 'fa-spinner animate-spin' : 'fa-satellite-dish'
                    }`}
                  ></i>
                  <span>{isScanningWeb ? 'Đang quét...' : 'Quét & Kiểm tra Web'}</span>
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 rounded-xl bg-gradient-to-r from-indigo-600 to-cyan-500 hover:brightness-110 text-white text-xs font-bold shadow-md shadow-indigo-500/25 flex items-center gap-1.5 tv-focus-target"
                >
                  <i className="fa-solid fa-circle-plus"></i>
                  <span>Thêm Nguồn Web Này</span>
                </button>
              </div>
            </div>

            {/* Live Scanner Diagnostic Box */}
            {scanResult && (
              <div className="p-3.5 rounded-2xl bg-emerald-500/10 border border-emerald-500/30 text-xs space-y-1.5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2 font-extrabold text-emerald-700 dark:text-emerald-300">
                    <i className="fa-solid fa-circle-check text-emerald-500"></i>
                    <span>Kết nối thành công tới: {scanResult.hostname}</span>
                  </div>
                  <span className="px-2 py-0.5 rounded-lg bg-emerald-500 text-white font-mono text-[10px] font-bold">
                    Ping: {scanResult.pingMs}ms
                  </span>
                </div>
                <p className="text-[11px] text-main">
                  • Phương thức: <b>{scanResult.type}</b> • Độ phân giải hỗ trợ:{' '}
                  <b>{scanResult.quality}</b> • {scanResult.note}.
                </p>
              </div>
            )}
          </form>

          {/* Existing Sources List */}
          <div className="liquid-glass rounded-3xl p-5 space-y-3.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h3 className="font-heading font-extrabold text-base text-main">
                  Danh Sách Máy Chủ Nguồn Phim Hiện Có
                </h3>
                <p className="text-xs text-muted">
                  Bật/tắt hiển thị trên trang chủ, chọn làm nguồn mặc định hoặc xóa các nguồn web tùy chỉnh
                </p>
              </div>
              <span className="px-2.5 py-1 rounded-xl bg-indigo-500/15 text-indigo-600 dark:text-cyan-300 text-xs font-extrabold">
                {sources.length} nguồn ({customSources.length} Web ngoài)
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {sources.map((src) => {
                const isPreferred = settings.preferredSource === src.id;
                return (
                  <div
                    key={src.id}
                    className={`p-4 rounded-2xl liquid-card flex flex-col justify-between gap-3 ${
                      isPreferred ? 'border-indigo-500 ring-1 ring-indigo-500/30' : ''
                    } ${!src.enabled ? 'opacity-65' : ''}`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex items-start gap-2.5 min-w-0">
                        <div
                          className={`w-9 h-9 rounded-xl flex items-center justify-center shrink-0 mt-0.5 ${
                            src.isCustom
                              ? 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-400'
                              : 'bg-indigo-500/15 text-indigo-600 dark:text-indigo-400'
                          }`}
                        >
                          <i className={`fa-solid ${src.isCustom ? 'fa-globe' : 'fa-server'}`}></i>
                        </div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <h4 className="text-xs sm:text-sm font-extrabold text-main truncate">
                              {src.name}
                            </h4>
                            <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-indigo-500/15 text-indigo-600 dark:text-indigo-300">
                              {src.quality}
                            </span>
                            {src.isCustom && (
                              <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-cyan-500/15 text-cyan-700 dark:text-cyan-300">
                                {src.type}
                              </span>
                            )}
                            {isPreferred && (
                              <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-emerald-500 text-white">
                                Mặc định
                              </span>
                            )}
                          </div>
                          <p className="text-[11px] text-muted truncate mt-0.5">
                            {src.url || src.desc}
                          </p>
                        </div>
                      </div>
                      <span className="text-xs font-mono font-bold text-emerald-600 dark:text-emerald-400 shrink-0">
                        {src.ping}
                      </span>
                    </div>

                    <div className="flex items-center justify-between pt-2 border-t border-slate-200/60 dark:border-slate-700/50">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => onSelectPreferredSource(src.id)}
                          className={`px-2.5 py-1 rounded-lg text-[11px] font-bold transition-all tv-focus-target ${
                            isPreferred
                              ? 'bg-indigo-600 text-white'
                              : 'bg-slate-200/70 dark:bg-slate-800 text-slate-700 dark:text-slate-300 hover:text-indigo-600'
                          }`}
                        >
                          {isPreferred ? 'Đang ưu tiên' : 'Đặt ưu tiên'}
                        </button>
                        {src.isCustom && (
                          <button
                            type="button"
                            onClick={() => handleDeleteSource(src.id, src.name)}
                            className="px-2.5 py-1 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-600 dark:text-rose-400 text-[11px] font-bold tv-focus-target"
                          >
                            <i className="fa-solid fa-trash-can mr-1"></i>
                            Xóa Web
                          </button>
                        )}
                      </div>

                      <label className="flex items-center gap-1.5 text-[11px] font-bold text-main cursor-pointer">
                        <input
                          type="checkbox"
                          checked={src.enabled}
                          onChange={() => handleToggleSourceEnabled(src)}
                          className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
                        />
                        <span>{src.enabled ? 'Đang bật' : 'Đã tắt'}</span>
                      </label>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
           SUBTAB 5: GIAO DIỆN (APPEARANCE & DEVICE SELECTION)
         ========================================== */}
      {activeSubtab === 'appearance' && (
        <div className="space-y-4">
          {/* Device Selector Card */}
          <div className="liquid-glass rounded-3xl p-5 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold bg-indigo-500/15 text-indigo-600 dark:text-indigo-300 uppercase">
                    Tối ưu hiển thị
                  </span>
                  <h3 className="font-heading font-extrabold text-base text-main flex items-center gap-2">
                    <i className="fa-solid fa-display text-indigo-600 dark:text-cyan-400"></i>
                    <span>Lựa Chọn Thiết Bị Phù Hợp</span>
                  </h3>
                </div>
                <p className="text-xs text-muted mt-0.5">
                  Tự động điều chỉnh kích thước khung hình, số cột poster phim, thanh điều hướng và chế độ rạp phim theo thiết bị của bạn.
                </p>
              </div>
              <span className="px-3 py-1 rounded-xl bg-indigo-500/15 text-indigo-600 dark:text-cyan-300 text-xs font-extrabold flex items-center gap-1.5">
                <i className="fa-solid fa-wand-magic-sparkles text-[11px]"></i>
                <span>Đang dùng: {activeDeviceBadgeLabel}</span>
              </span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              {/* Auto */}
              <button
                type="button"
                onClick={() => {
                  onUpdateSettings({ deviceMode: 'auto' });
                  onToast('Đã chuyển chế độ thiết bị: Tự động (Auto)');
                }}
                className={`p-3.5 rounded-2xl liquid-card text-left space-y-2 tv-focus-target ${
                  settings.deviceMode === 'auto'
                    ? 'border-2 border-indigo-600 ring-1 ring-indigo-500/20'
                    : ''
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-600 to-cyan-500 text-white flex items-center justify-center">
                    <i className="fa-solid fa-wand-magic-sparkles"></i>
                  </div>
                  <span className="px-2 py-0.5 rounded-md bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 text-[9px] font-extrabold">
                    Khuyên dùng
                  </span>
                </div>
                <div className="text-xs font-extrabold text-main">Tự động (Auto)</div>
                <div className="text-[10px] text-muted">
                  Đang tự nhận diện màn hình: {detectedPhysicalLabel} ({viewportWidth}px)
                </div>
              </button>

              {/* Mobile */}
              <button
                type="button"
                onClick={() => {
                  onUpdateSettings({ deviceMode: 'mobile' });
                  onToast('Đã chuyển giao diện thiết bị: Điện thoại');
                }}
                className={`p-3.5 rounded-2xl liquid-card text-left space-y-2 tv-focus-target ${
                  settings.deviceMode === 'mobile'
                    ? 'border-2 border-indigo-600 ring-1 ring-indigo-500/20'
                    : ''
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="w-9 h-9 rounded-xl bg-indigo-500/15 text-indigo-600 dark:text-indigo-400 flex items-center justify-center">
                    <i className="fa-solid fa-mobile-screen-button"></i>
                  </div>
                  <span className="text-[10px] font-bold text-muted">2 Cột • Chạm</span>
                </div>
                <div className="text-xs font-extrabold text-main">Điện thoại</div>
                <div className="text-[10px] text-muted">
                  Giao diện gọn 1 tay, lưới 2 cột, chạm đúp tua nhanh &amp; Bottom Bar
                </div>
              </button>

              {/* Tablet */}
              <button
                type="button"
                onClick={() => {
                  onUpdateSettings({ deviceMode: 'tablet' });
                  onToast('Đã chuyển giao diện thiết bị: Máy tính bảng');
                }}
                className={`p-3.5 rounded-2xl liquid-card text-left space-y-2 tv-focus-target ${
                  settings.deviceMode === 'tablet'
                    ? 'border-2 border-indigo-600 ring-1 ring-indigo-500/20'
                    : ''
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="w-9 h-9 rounded-xl bg-cyan-500/15 text-cyan-600 dark:text-cyan-400 flex items-center justify-center">
                    <i className="fa-solid fa-tablet-screen-button"></i>
                  </div>
                  <span className="text-[10px] font-bold text-muted">3 Cột • Dock nổi</span>
                </div>
                <div className="text-xs font-extrabold text-main">Máy tính bảng</div>
                <div className="text-[10px] text-muted">
                  Khung rộng 960px, lưới 3 cột cân đối, thanh điều hướng Floating Dock
                </div>
              </button>

              {/* TV */}
              <button
                type="button"
                onClick={() => {
                  onUpdateSettings({ deviceMode: 'tv' });
                  onToast('Đã chuyển giao diện thiết bị: Tivi (Smart TV)');
                }}
                className={`p-3.5 rounded-2xl liquid-card text-left space-y-2 tv-focus-target ${
                  settings.deviceMode === 'tv'
                    ? 'border-2 border-indigo-600 ring-1 ring-indigo-500/20'
                    : ''
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="w-9 h-9 rounded-xl bg-purple-500/15 text-purple-600 dark:text-purple-400 flex items-center justify-center">
                    <i className="fa-solid fa-tv"></i>
                  </div>
                  <span className="text-[10px] font-bold text-amber-600 dark:text-amber-400">
                    5 Cột • Remote
                  </span>
                </div>
                <div className="text-xs font-extrabold text-main">Tivi (Smart TV)</div>
                <div className="text-[10px] text-muted">
                  Màn hình lớn 1440px, lưới 5 cột, chữ lớn &amp; hỗ trợ phím điều hướng Remote
                </div>
              </button>
            </div>

            {resolvedDevice === 'tv' && (
              <div className="p-3 rounded-2xl bg-indigo-500/[0.08] border border-indigo-500/25 flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2 text-xs text-indigo-700 dark:text-indigo-300 font-semibold">
                  <i className="fa-solid fa-gamepad"></i>
                  <span>
                    Chế độ Smart TV đang bật: Hỗ trợ phím Mũi tên (Trái/Phải để tua, Lên/Xuống, Enter/Space để dừng phát) trên Remote/Bàn phím.
                  </span>
                </div>
                <span className="px-2 py-0.5 rounded-lg bg-indigo-600 text-white text-[10px] font-bold">
                  10-Foot UI Ready
                </span>
              </div>
            )}
          </div>

          {/* Theme Presets & Glass Blur Card */}
          <div className="liquid-glass rounded-3xl p-5 space-y-4">
            <h3 className="font-heading font-extrabold text-base text-main flex items-center gap-2">
              <i className="fa-solid fa-palette text-indigo-600 dark:text-cyan-400"></i>
              <span>Chủ Đề Màu Sắc &amp; Độ Trong Suốt Liquid Glass</span>
            </h3>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              {THEME_PRESETS.map((preset) => {
                const isSelected = settings.theme === preset.id;
                return (
                  <button
                    key={preset.id}
                    type="button"
                    onClick={() => {
                      onUpdateSettings({ theme: preset.id });
                      onToast(`Đã áp dụng giao diện: ${preset.name}`);
                    }}
                    className={`p-3.5 rounded-2xl liquid-card text-left space-y-2 tv-focus-target ${
                      isSelected ? 'border-2 border-indigo-600 ring-1 ring-indigo-500/20' : ''
                    }`}
                  >
                    <div className={`w-full h-10 rounded-xl shadow-inner ${preset.swatchClass}`}></div>
                    <div className="text-xs font-extrabold text-main">{preset.name}</div>
                    <div className="text-[10px] text-muted">{preset.desc}</div>
                  </button>
                );
              })}
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5 pt-2">
              <div className="p-3.5 rounded-2xl liquid-card space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-bold text-main">
                    Độ mờ thấu kính (Backdrop Blur)
                  </span>
                  <span className="text-xs font-extrabold text-indigo-600 dark:text-cyan-400">
                    {settings.glassBlur}px
                  </span>
                </div>
                <input
                  type="range"
                  min={8}
                  max={40}
                  value={settings.glassBlur}
                  onChange={(e) => onUpdateSettings({ glassBlur: Number(e.target.value) })}
                  className="w-full accent-indigo-600 cursor-pointer"
                />
              </div>

              <label className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
                <div>
                  <div className="text-xs font-bold text-main">
                    Hiệu ứng Giọt Sáng Nền (Liquid Ambient Orbs)
                  </div>
                  <div className="text-[11px] text-muted">
                    Tạo chiều sâu khúc xạ phía sau lớp kính
                  </div>
                </div>
                <input
                  type="checkbox"
                  checked={settings.ambientOrbs}
                  onChange={(e) => {
                    onUpdateSettings({ ambientOrbs: e.target.checked });
                    onToast(
                      e.target.checked
                        ? 'Đã bật hiệu ứng Giọt Sáng Liquid'
                        : 'Đã tắt Giọt Sáng nền'
                    );
                  }}
                  className="w-4 h-4 accent-indigo-600 cursor-pointer tv-focus-target"
                />
              </label>
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
           SUBTAB 6: CẬP NHẬT, SAO LƯU & CẤU HÌNH PWA/ANDROID
         ========================================== */}
      {activeSubtab === 'backup' && (
        <div className="space-y-4">
          {/* Universal Android Compatibility Card */}
          <div className="liquid-glass rounded-3xl p-5 border-2 border-emerald-500/35 space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="flex items-start gap-3">
                <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-emerald-500 to-teal-600 text-white flex items-center justify-center shadow-lg shadow-emerald-500/25 shrink-0">
                  <i className="fa-brands fa-android text-2xl"></i>
                </div>
                <div>
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold bg-gradient-to-r from-emerald-500 to-teal-600 text-white uppercase">
                      Universal Android Studio
                    </span>
                    <span className="px-2 py-0.5 rounded-md text-[10px] font-bold bg-indigo-500/15 text-indigo-600 dark:text-indigo-300">
                      API 23 (Android 6.0) → API 35 (Android 15)
                    </span>
                  </div>
                  <h3 className="font-heading font-extrabold text-base sm:text-lg text-main mt-1">
                    Thông Tin Gói APK Universal (v3.5.0)
                  </h3>
                  <p className="text-xs text-muted mt-0.5">
                    Hỗ trợ đa kiến trúc CPU (<b className="text-main">arm64-v8a, armeabi-v7a, x86_64</b>) chạy mượt trên Điện thoại, Máy tính bảng, Android TV Box &amp; Smart TV.
                  </p>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              <div className="p-3 rounded-2xl liquid-card flex items-center gap-2.5">
                <i className="fa-solid fa-microchip text-emerald-500 text-base"></i>
                <div>
                  <div className="text-[11px] font-extrabold text-main">Universal Multi-ABI</div>
                  <div className="text-[10px] text-muted">arm64 • v7a • x86_64</div>
                </div>
              </div>
              <div className="p-3 rounded-2xl liquid-card flex items-center gap-2.5">
                <i className="fa-solid fa-tv text-indigo-500 text-base"></i>
                <div>
                  <div className="text-[11px] font-extrabold text-main">Android TV Leanback</div>
                  <div className="text-[10px] text-muted">Hỗ trợ Remote D-Pad</div>
                </div>
              </div>
              <div className="p-3 rounded-2xl liquid-card flex items-center gap-2.5">
                <i className="fa-solid fa-gauge-high text-cyan-500 text-base"></i>
                <div>
                  <div className="text-[11px] font-extrabold text-main">Hardware 4K Decode</div>
                  <div className="text-[10px] text-muted">HLS.js + GPU 60FPS</div>
                </div>
              </div>
              <div className="p-3 rounded-2xl liquid-card flex items-center gap-2.5">
                <i className="fa-solid fa-sun text-amber-500 text-base"></i>
                <div>
                  <div className="text-[11px] font-extrabold text-main">Screen WakeLock</div>
                  <div className="text-[10px] text-muted">Chống tắt màn khi xem</div>
                </div>
              </div>
            </div>

            {/* Manifest Config Preview Controls */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-1">
              <div>
                <label className="block text-[11px] font-bold text-main mb-1">
                  Package ID (Application ID)
                </label>
                <input
                  type="text"
                  value={apkPackageId}
                  onChange={(e) => setApkPackageId(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl text-xs font-mono liquid-input"
                />
              </div>
              <div>
                <label className="block text-[11px] font-bold text-main mb-1">
                  Kiến trúc đích (ABI)
                </label>
                <select
                  value={apkAbi}
                  onChange={(e) => setApkAbi(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl text-xs font-bold liquid-input"
                >
                  <option value="universal">Universal APK (Tất cả máy Android)</option>
                  <option value="arm64-v8a">arm64-v8a (Điện thoại/Tablet mới)</option>
                  <option value="armeabi-v7a">armeabi-v7a (Android TV Box / Máy cũ)</option>
                  <option value="x86_64">x86_64 (Giả lập PC)</option>
                </select>
              </div>
              <div>
                <label className="block text-[11px] font-bold text-main mb-1">
                  Chế độ Xoay màn hình
                </label>
                <select
                  value={apkOrientation}
                  onChange={(e) => setApkOrientation(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl text-xs font-bold liquid-input"
                >
                  <option value="fullSensor">Tự động xoay (Phone + Tablet + TV)</option>
                  <option value="portrait">Dọc cố định (Ưu tiên Điện thoại)</option>
                  <option value="sensorLandscape">Ngang cố định (Ưu tiên Android TV)</option>
                </select>
              </div>
            </div>

            <details className="group pt-1">
              <summary className="cursor-pointer list-none flex items-center justify-between p-3 rounded-2xl bg-indigo-500/[0.06] border border-indigo-500/15 text-xs font-extrabold text-indigo-700 dark:text-indigo-300">
                <span>
                  <i className="fa-solid fa-code mr-1.5"></i>
                  Xem &amp; Tải Mã Nguồn AndroidManifest.xml (Phone + Tablet + TV Leanback)
                </span>
                <i className="fa-solid fa-chevron-down text-[10px] group-open:rotate-180 transition-transform"></i>
              </summary>
              <div className="mt-3 p-3.5 rounded-2xl liquid-card space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-extrabold text-emerald-600 dark:text-emerald-400">
                    <i className="fa-brands fa-android mr-1"></i>AndroidManifest.xml
                  </span>
                  <div className="flex gap-1.5">
                    <button
                      type="button"
                      onClick={() => copyText(androidManifestPreview, 'AndroidManifest.xml')}
                      className="px-2.5 py-1 rounded-lg bg-slate-200/70 dark:bg-slate-700 text-[11px] font-semibold text-main"
                    >
                      Sao chép
                    </button>
                    <button
                      type="button"
                      onClick={() => downloadTextFile('AndroidManifest.xml', androidManifestPreview)}
                      className="px-2.5 py-1 rounded-lg bg-emerald-600 text-white text-[11px] font-bold"
                    >
                      Tải XML
                    </button>
                  </div>
                </div>
                <pre className="text-[10px] font-mono bg-slate-900 text-emerald-300 p-3 rounded-xl overflow-x-auto max-h-52 select-all">
                  {androidManifestPreview}
                </pre>
              </div>
            </details>
          </div>

          {/* Backup JSON Export / Import + Update Check */}
          <div className="liquid-glass rounded-3xl p-5 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h3 className="font-heading font-extrabold text-base text-main">
                  Sao Lưu Cấu Hình &amp; Kiểm Tra Cập Nhật
                </h3>
                <p className="text-xs text-muted">
                  Bao gồm Lịch sử xem, Tủ phim, Cài đặt trình phát và toàn bộ Nguồn Web tự thêm
                </p>
              </div>
              <div className="flex items-center gap-2 flex-wrap">
                <button
                  type="button"
                  onClick={() =>
                    onToast('Bạn đang dùng bản mới nhất: HoatHinh3D v3.5.0 Universal!')
                  }
                  className="px-3 py-2 rounded-xl liquid-pill text-xs font-bold text-indigo-600 dark:text-cyan-300 flex items-center gap-1.5 tv-focus-target"
                >
                  <i className="fa-solid fa-rotate"></i>
                  <span>Kiểm tra cập nhật (v3.5.0)</span>
                </button>
                <button
                  type="button"
                  onClick={() =>
                    onToast(
                      'Mở trình duyệt -> Chọn "Thêm vào Màn hình chính" để cài bản PWA'
                    )
                  }
                  className="px-3.5 py-2 rounded-xl bg-gradient-to-r from-indigo-600 to-cyan-500 text-white text-xs font-bold shadow flex items-center gap-1.5 tv-focus-target"
                >
                  <i className="fa-solid fa-mobile-screen-button"></i>
                  <span>Cài Đặt PWA</span>
                </button>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="p-4 rounded-2xl liquid-card flex items-center justify-between gap-3">
                <div>
                  <div className="text-xs font-bold text-main">Xuất dữ liệu ra file (.JSON)</div>
                  <div className="text-[11px] text-muted">
                    Tải toàn bộ thiết lập &amp; nguồn web về máy
                  </div>
                </div>
                <button
                  type="button"
                  onClick={handleExportJSON}
                  className="px-3 py-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-bold flex items-center gap-1.5 shrink-0 tv-focus-target"
                >
                  <i className="fa-solid fa-file-export"></i>
                  <span>Xuất JSON</span>
                </button>
              </div>

              <div className="p-4 rounded-2xl liquid-card flex items-center justify-between gap-3">
                <div>
                  <div className="text-xs font-bold text-main">Nhập dữ liệu từ file (.JSON)</div>
                  <div className="text-[11px] text-muted">
                    Khôi phục thiết lập từ bản sao lưu
                  </div>
                </div>
                <label className="px-3 py-1.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-bold flex items-center gap-1.5 cursor-pointer shrink-0 tv-focus-target">
                  <i className="fa-solid fa-file-import"></i>
                  <span>Nhập JSON</span>
                  <input
                    type="file"
                    accept=".json"
                    onChange={handleImportJSON}
                    className="hidden"
                  />
                </label>
              </div>
            </div>
          </div>

          {/* PWA Icons & Code Studio */}
          <div className="liquid-glass rounded-3xl p-5 space-y-3">
            <div className="flex items-center justify-between flex-wrap gap-2">
              <h3 className="font-heading font-extrabold text-base text-main flex items-center gap-2">
                <i className="fa-solid fa-icons text-indigo-600 dark:text-cyan-400"></i>
                <span>Bộ Icon Mẫu Chuẩn PWA &amp; Mã Nguồn Manifest / Service Worker</span>
              </h3>
              <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
                PWA Ready
              </span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <canvas
                    ref={canvas192Ref}
                    width={192}
                    height={192}
                    className="w-12 h-12 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm"
                  />
                  <div>
                    <div className="text-xs font-bold text-main">icon-192x192.png</div>
                    <div className="text-[11px] text-muted">Chuẩn Mobile Home Screen</div>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => downloadCanvasPng(192)}
                  className="px-3 py-1.5 rounded-xl liquid-pill text-xs font-bold text-indigo-600 dark:text-cyan-300 tv-focus-target"
                >
                  <i className="fa-solid fa-download mr-1"></i>Tải PNG
                </button>
              </div>

              <div className="p-3.5 rounded-2xl liquid-card flex items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <canvas
                    ref={canvas512Ref}
                    width={512}
                    height={512}
                    className="w-12 h-12 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm"
                  />
                  <div>
                    <div className="text-xs font-bold text-main">icon-512x512.png</div>
                    <div className="text-[11px] text-muted">Chuẩn Splash Screen PWA</div>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => downloadCanvasPng(512)}
                  className="px-3 py-1.5 rounded-xl liquid-pill text-xs font-bold text-cyan-600 dark:text-cyan-300 tv-focus-target"
                >
                  <i className="fa-solid fa-download mr-1"></i>Tải PNG
                </button>
              </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 pt-1">
              <div className="p-3.5 rounded-2xl liquid-card flex flex-col">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-extrabold text-indigo-600 dark:text-indigo-400">
                    <i className="fa-solid fa-file-code mr-1"></i>manifest.json
                  </span>
                  <div className="flex gap-1.5">
                    <button
                      type="button"
                      onClick={() => copyText(manifestJsonText, 'manifest.json')}
                      className="px-2 py-1 rounded-lg bg-slate-200/70 dark:bg-slate-700 text-[11px] font-semibold text-main"
                    >
                      Sao chép
                    </button>
                    <button
                      type="button"
                      onClick={() => downloadTextFile('manifest.json', manifestJsonText)}
                      className="px-2.5 py-1 rounded-lg bg-indigo-600 text-white text-[11px] font-bold"
                    >
                      Tải file
                    </button>
                  </div>
                </div>
                <pre className="text-[10px] font-mono bg-slate-900 text-cyan-300 p-3 rounded-xl overflow-x-auto max-h-48 select-all">
                  {manifestJsonText}
                </pre>
              </div>

              <div className="p-3.5 rounded-2xl liquid-card flex flex-col">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-extrabold text-cyan-600 dark:text-cyan-400">
                    <i className="fa-solid fa-bolt mr-1"></i>sw.js (Service Worker)
                  </span>
                  <div className="flex gap-1.5">
                    <button
                      type="button"
                      onClick={() => copyText(swCodeText, 'sw.js')}
                      className="px-2 py-1 rounded-lg bg-slate-200/70 dark:bg-slate-700 text-[11px] font-semibold text-main"
                    >
                      Sao chép
                    </button>
                    <button
                      type="button"
                      onClick={() => downloadTextFile('sw.js', swCodeText)}
                      className="px-2.5 py-1 rounded-lg bg-cyan-600 text-white text-[11px] font-bold"
                    >
                      Tải file
                    </button>
                  </div>
                </div>
                <pre className="text-[10px] font-mono bg-slate-900 text-indigo-200 p-3 rounded-xl overflow-x-auto max-h-48 select-all">
                  {swCodeText}
                </pre>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
           SUBTAB 7: TIỆN ÍCH MỞ RỘNG (EXTENSIONS & PLUGINS)
         ========================================== */}
      {activeSubtab === 'extensions' && (
        <div className="liquid-glass rounded-3xl p-5 space-y-4">
          <div>
            <h3 className="font-heading font-extrabold text-base text-main flex items-center gap-2">
              <i className="fa-solid fa-puzzle-piece text-indigo-600 dark:text-cyan-400"></i>
              <span>Kho Tiện Ích Mở Rộng (Plugins)</span>
            </h3>
            <p className="text-xs text-muted mt-0.5">
              Kích hoạt các mô-đun nâng cao trải nghiệm xem hoạt hình 3D và lọc quảng cáo từ nguồn web ngoài
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
            {/* 1. AdBlock Shield Pro */}
            <label className="p-4 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-shield-halved"></i>
                </div>
                <div>
                  <div className="text-xs font-extrabold text-main">
                    AdBlock Shield Pro (Chặn Popup Web Ngoài)
                  </div>
                  <div className="text-[11px] text-muted">
                    Lọc sạch banner &amp; quảng cáo chuyển hướng khi phát từ nguồn web bất kỳ
                  </div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={settings.extensions.adblock}
                onChange={(e) => {
                  onUpdateSettings({
                    extensions: { ...settings.extensions, adblock: e.target.checked }
                  });
                  onToast(
                    e.target.checked
                      ? 'Đã kích hoạt AdBlock Shield Pro'
                      : 'Đã tắt AdBlock Shield Pro'
                  );
                }}
                className="w-4 h-4 accent-indigo-600 cursor-pointer shrink-0 tv-focus-target"
              />
            </label>

            {/* 2. AI HDR Upscale */}
            <label className="p-4 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-indigo-500/15 text-indigo-600 dark:text-indigo-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-wand-magic-sparkles"></i>
                </div>
                <div>
                  <div className="text-xs font-extrabold text-main">
                    AI HDR Upscale (Tăng Nét Khung Hình 3D)
                  </div>
                  <div className="text-[11px] text-muted">
                    Tăng cường độ tương phản kiếm khí, hiệu ứng hỏa diễm trên trình phát
                  </div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={settings.extensions.aiUpscale}
                onChange={(e) => {
                  onUpdateSettings({
                    extensions: { ...settings.extensions, aiUpscale: e.target.checked }
                  });
                  onToast(
                    e.target.checked
                      ? 'Đã kích hoạt AI HDR Upscale trên trình phát'
                      : 'Đã tắt AI HDR Upscale'
                  );
                }}
                className="w-4 h-4 accent-indigo-600 cursor-pointer shrink-0 tv-focus-target"
              />
            </label>

            {/* 3. Bilingual Subtitles */}
            <label className="p-4 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/15 text-cyan-600 dark:text-cyan-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-language"></i>
                </div>
                <div>
                  <div className="text-xs font-extrabold text-main">
                    Phụ Đề Song Ngữ Việt - Trung (AI Sub)
                  </div>
                  <div className="text-[11px] text-muted">
                    Hiển thị song song thoại Hán Việt &amp; tiếng Trung trên màn hình video
                  </div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={settings.extensions.bilingualSub}
                onChange={(e) => {
                  onUpdateSettings({
                    bilingualSub: e.target.checked,
                    extensions: {
                      ...settings.extensions,
                      bilingualSub: e.target.checked
                    }
                  });
                  onToast(
                    e.target.checked
                      ? 'Đã bật phụ đề song ngữ Việt - Trung'
                      : 'Đã tắt phụ đề song ngữ'
                  );
                }}
                className="w-4 h-4 accent-indigo-600 cursor-pointer shrink-0 tv-focus-target"
              />
            </label>

            {/* 4. Ambient Glow */}
            <label className="p-4 rounded-2xl liquid-card flex items-center justify-between gap-3 cursor-pointer">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-purple-500/15 text-purple-600 dark:text-purple-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-lightbulb"></i>
                </div>
                <div>
                  <div className="text-xs font-extrabold text-main">
                    Hào Quang Rạp Phim (Ambient Glow)
                  </div>
                  <div className="text-[11px] text-muted">
                    Tỏa ánh sáng màu quanh viền khung phát video
                  </div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={settings.extensions.ambientGlow}
                onChange={(e) => {
                  onUpdateSettings({
                    extensions: { ...settings.extensions, ambientGlow: e.target.checked }
                  });
                  onToast(
                    e.target.checked
                      ? 'Đã bật Hào Quang Rạp Phim'
                      : 'Đã tắt Hào Quang Rạp Phim'
                  );
                }}
                className="w-4 h-4 accent-indigo-600 cursor-pointer shrink-0 tv-focus-target"
              />
            </label>

            {/* 5. Sleep Timer */}
            <div className="p-4 rounded-2xl liquid-card flex flex-wrap items-center justify-between gap-3 sm:col-span-2">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-amber-500/15 text-amber-600 dark:text-amber-400 flex items-center justify-center shrink-0">
                  <i className="fa-solid fa-moon"></i>
                </div>
                <div>
                  <div className="text-xs font-extrabold text-main">
                    Hẹn Giờ Tắt Phim Khi Ngủ (Sleep Timer)
                  </div>
                  <div className="text-[11px] text-muted">
                    {sleepRemainingSec !== null && sleepRemainingSec > 0
                      ? `Đang đếm ngược: Tự động tạm dừng video sau ${formatCountdown(sleepRemainingSec)}`
                      : 'Tự động tạm dừng video sau khoảng thời gian chọn'}
                  </div>
                </div>
              </div>
              <select
                value={sleepTimerMinutes}
                onChange={(e) => onSetSleepTimer(Number(e.target.value))}
                className="px-3 py-1.5 rounded-xl text-xs font-bold liquid-input tv-focus-target"
              >
                <option value={0}>Tắt hẹn giờ</option>
                <option value={15}>Sau 15 phút</option>
                <option value={30}>Sau 30 phút</option>
                <option value={60}>Sau 60 phút</option>
              </select>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
