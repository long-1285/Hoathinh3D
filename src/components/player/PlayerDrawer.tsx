import React from 'react';
import { EpisodeItem, OfflineEpisodeRecord, ServerGroup } from '../../types';
import { OfflineCacheService } from '../../services/offline.service';
import { HlsQualityLevel, PlayerDeviceProfile } from './playerUtils';

export type PlayerDrawerType = 'none' | 'episodes' | 'sources' | 'speed' | 'quality';

interface PlayerDrawerProps {
  activeDrawer: PlayerDrawerType;
  deviceProfile: PlayerDeviceProfile;
  movieId: string;
  episodesList: EpisodeItem[];
  totalEpCount: number;
  currentEpIdx: number;
  epRanges: { label: string; start: number; end: number }[];
  epRangeIdx: number;
  currentRange: { label: string; start: number; end: number };
  serverGroups: ServerGroup[];
  activeServerIdx: number;
  hasM3u8: boolean;
  hasEmbed: boolean;
  useEmbedNow: boolean;
  playbackSpeed: number;
  hlsLevels: HlsQualityLevel[];
  activeQualityIdx: number;
  offlineMap: Record<string, OfflineEpisodeRecord>;
  onClose: () => void;
  onSelectEpRange: (idx: number) => void;
  onSelectEpisode: (realIdx: number) => void;
  onToggleEmbedMode: () => void;
  onSelectServer: (idx: number) => void;
  onSelectSpeed: (spd: number) => void;
  onSelectQuality: (levelIdx: number) => void;
}

const SPEED_OPTIONS = [0.5, 0.75, 1, 1.25, 1.5, 2];

export const PlayerDrawer: React.FC<PlayerDrawerProps> = ({
  activeDrawer,
  deviceProfile,
  movieId,
  episodesList,
  totalEpCount,
  currentEpIdx,
  epRanges,
  epRangeIdx,
  currentRange,
  serverGroups,
  activeServerIdx,
  hasM3u8,
  hasEmbed,
  useEmbedNow,
  playbackSpeed,
  hlsLevels,
  activeQualityIdx,
  offlineMap,
  onClose,
  onSelectEpRange,
  onSelectEpisode,
  onToggleEmbedMode,
  onSelectServer,
  onSelectSpeed,
  onSelectQuality
}) => {
  if (activeDrawer === 'none') return null;

  const isWideDrawer = deviceProfile === 'tv' || deviceProfile === 'tablet' || deviceProfile === 'pc';
  const epGridCols = isWideDrawer ? 'grid-cols-5' : 'grid-cols-4';
  const epBtnText = deviceProfile === 'tv' ? 'py-2.5 text-sm' : 'py-2 text-xs';

  return (
    <div
      data-tv-drawer="true"
      className={`absolute inset-y-0 right-0 z-40 w-full ${
        isWideDrawer ? 'max-w-md' : 'max-w-sm'
      } bg-slate-950/92 backdrop-blur-2xl border-l border-white/15 p-4 sm:p-5 flex flex-col gap-4 shadow-2xl`}
    >
      <div className="flex items-center justify-between border-b border-white/10 pb-3">
        <h3 className="font-heading font-extrabold text-sm sm:text-base text-white flex items-center gap-2">
          {activeDrawer === 'episodes' && (
            <>
              <i className="fa-solid fa-list-ul text-amber-400"></i>
              <span>Danh sách tập ({totalEpCount} tập)</span>
            </>
          )}
          {activeDrawer === 'sources' && (
            <>
              <i className="fa-solid fa-layer-group text-amber-400"></i>
              <span>Nguồn, Máy Chủ &amp; Chất Lượng</span>
            </>
          )}
          {activeDrawer === 'quality' && (
            <>
              <i className="fa-solid fa-sliders text-amber-400"></i>
              <span>Chất Lượng Luồng Phát</span>
            </>
          )}
          {activeDrawer === 'speed' && (
            <>
              <i className="fa-solid fa-gauge-high text-amber-400"></i>
              <span>Tốc Độ Phát</span>
            </>
          )}
        </h3>
        <button
          type="button"
          onClick={onClose}
          className="w-8 h-8 rounded-full bg-white/10 hover:bg-white/20 text-white flex items-center justify-center tv-focus-target"
          aria-label="Đóng bảng"
        >
          <i className="fa-solid fa-xmark text-xs"></i>
        </button>
      </div>

      {activeDrawer === 'episodes' && (
        <>
          {epRanges.length > 1 && (
            <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar pb-1 shrink-0">
              {epRanges.map((rng, idx) => (
                <button
                  key={rng.label}
                  type="button"
                  onClick={() => onSelectEpRange(idx)}
                  className={`px-2.5 py-1 rounded-lg text-[11px] font-bold shrink-0 tv-focus-target ${
                    epRangeIdx === idx
                      ? 'bg-amber-400 text-slate-950'
                      : 'bg-white/10 text-slate-300 hover:bg-white/20'
                  }`}
                >
                  {rng.label}
                </button>
              ))}
            </div>
          )}

          <div className={`grid ${epGridCols} gap-2 overflow-y-auto pr-1 flex-1 content-start`}>
            {episodesList.length > 0
              ? episodesList.slice(currentRange.start, currentRange.end).map((ep, localIdx) => {
                  const realIdx = currentRange.start + localIdx;
                  const isCurrent = realIdx === currentEpIdx;
                  const epNumMatch = (ep.slug || ep.name || '').match(/(\d+(?:\.\d+)?)/);
                  const epNum = epNumMatch ? parseFloat(epNumMatch[1]) : realIdx + 1;
                  const isOffline = Boolean(
                    offlineMap[OfflineCacheService.buildEpisodeKey(movieId, epNum)]
                  );
                  return (
                    <button
                      key={realIdx}
                      type="button"
                      data-tv-autofocus={isCurrent ? 'true' : undefined}
                      onClick={() => onSelectEpisode(realIdx)}
                      className={`${epBtnText} px-1.5 rounded-xl font-bold truncate transition-all tv-focus-target relative ${
                        isCurrent
                          ? 'bg-amber-400 text-slate-950 font-extrabold shadow'
                          : isOffline
                          ? 'bg-emerald-500/20 border border-emerald-400/40 text-emerald-200 hover:bg-emerald-500/30'
                          : 'bg-white/10 text-slate-200 hover:bg-white/20'
                      }`}
                    >
                      {ep.name || `Tập ${realIdx + 1}`}
                    </button>
                  );
                })
              : Array.from(
                  { length: currentRange.end - currentRange.start },
                  (_, i) => currentRange.start + i + 1
                ).map((ep) => (
                  <button
                    key={ep}
                    type="button"
                    data-tv-autofocus={ep === currentEpIdx + 1 ? 'true' : undefined}
                    onClick={() => onSelectEpisode(ep - 1)}
                    className={`${epBtnText} rounded-xl font-bold transition-all tv-focus-target ${
                      ep === currentEpIdx + 1
                        ? 'bg-amber-400 text-slate-950 font-extrabold'
                        : 'bg-white/10 text-slate-200 hover:bg-white/20'
                    }`}
                  >
                    {ep}
                  </button>
                ))}
          </div>
        </>
      )}

      {activeDrawer === 'sources' && (
        <div className="space-y-3 overflow-y-auto flex-1 pr-1">
          {hasM3u8 && hasEmbed && (
            <div className="p-3 rounded-2xl bg-white/5 border border-white/10 flex items-center justify-between gap-2">
              <span className="text-xs font-bold text-white">Chế độ trình phát</span>
              <button
                type="button"
                onClick={onToggleEmbedMode}
                className="px-3 py-1 rounded-lg bg-amber-400 text-slate-950 text-xs font-extrabold tv-focus-target shrink-0"
              >
                {useEmbedNow ? 'Đang dùng Embed (Đổi HLS)' : 'Đang dùng HLS (Đổi Embed)'}
              </button>
            </div>
          )}

          <div className="space-y-2">
            {serverGroups.map((group, idx) => {
              const hasDirect = group.episodes.some((e) => Boolean(e.linkM3u8));
              return (
                <button
                  key={idx}
                  type="button"
                  data-tv-autofocus={activeServerIdx === idx ? 'true' : undefined}
                  onClick={() => onSelectServer(idx)}
                  className={`w-full p-3 rounded-2xl text-left flex items-center justify-between transition-all tv-focus-target ${
                    activeServerIdx === idx
                      ? 'bg-amber-400/20 border border-amber-400 text-amber-300'
                      : 'bg-white/10 text-white hover:bg-white/15'
                  }`}
                >
                  <div className="min-w-0 pr-2">
                    <div className="text-xs sm:text-sm font-extrabold truncate">
                      {group.serverName}
                    </div>
                    <div className="text-[11px] text-slate-400 flex items-center gap-2">
                      <span>{group.episodes.length} tập</span>
                      <span>•</span>
                      <span className={hasDirect ? 'text-emerald-400' : 'text-indigo-300'}>
                        {hasDirect ? 'Trình phát tích hợp HLS' : 'Web Embed'}
                      </span>
                    </div>
                  </div>
                  {activeServerIdx === idx && (
                    <i className="fa-solid fa-circle-check text-amber-400 shrink-0"></i>
                  )}
                </button>
              );
            })}
          </div>

          {hlsLevels.length > 0 && !useEmbedNow && (
            <div className="pt-2 border-t border-white/10">
              <div className="text-xs font-bold text-slate-300 mb-2">Chất lượng HLS</div>
              <div className="flex flex-wrap gap-1.5">
                {hlsLevels.map((lvl) => (
                  <button
                    key={lvl.index}
                    type="button"
                    onClick={() => onSelectQuality(lvl.index)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-bold tv-focus-target ${
                      activeQualityIdx === lvl.index
                        ? 'bg-amber-400 text-slate-950'
                        : 'bg-white/10 text-white hover:bg-white/20'
                    }`}
                  >
                    {lvl.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="pt-2 border-t border-white/10">
            <div className="text-xs font-bold text-slate-300 mb-2">Tốc độ phát</div>
            <div className="grid grid-cols-6 gap-1.5">
              {SPEED_OPTIONS.map((spd) => (
                <button
                  key={spd}
                  type="button"
                  onClick={() => onSelectSpeed(spd)}
                  className={`py-1.5 rounded-lg text-xs font-bold tv-focus-target ${
                    playbackSpeed === spd
                      ? 'bg-amber-400 text-slate-950'
                      : 'bg-white/10 text-white hover:bg-white/20'
                  }`}
                >
                  {spd}x
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {activeDrawer === 'quality' && (
        <div className="space-y-2 overflow-y-auto flex-1">
          {hlsLevels.length > 0 ? (
            hlsLevels.map((lvl) => (
              <button
                key={lvl.index}
                type="button"
                data-tv-autofocus={activeQualityIdx === lvl.index ? 'true' : undefined}
                onClick={() => onSelectQuality(lvl.index)}
                className={`w-full p-3 rounded-2xl text-left text-xs sm:text-sm font-bold flex items-center justify-between tv-focus-target ${
                  activeQualityIdx === lvl.index
                    ? 'bg-amber-400 text-slate-950'
                    : 'bg-white/10 text-white hover:bg-white/15'
                }`}
              >
                <span>{lvl.label}</span>
                {activeQualityIdx === lvl.index && <i className="fa-solid fa-check"></i>}
              </button>
            ))
          ) : (
            <div className="p-4 rounded-2xl bg-white/5 border border-white/10 text-xs text-slate-300 leading-relaxed">
              Luồng phát hiện tại dùng một mức chất lượng cố định hoặc Web Embed. Bạn có thể chuyển máy chủ trong mục <b>Nguồn</b> nếu muốn chọn độ phân giải khác.
            </div>
          )}
        </div>
      )}

      {activeDrawer === 'speed' && (
        <div className="space-y-2 overflow-y-auto flex-1">
          {SPEED_OPTIONS.map((spd) => (
            <button
              key={spd}
              type="button"
              data-tv-autofocus={playbackSpeed === spd ? 'true' : undefined}
              onClick={() => onSelectSpeed(spd)}
              className={`w-full p-3 rounded-2xl text-left text-xs sm:text-sm font-bold flex items-center justify-between tv-focus-target ${
                playbackSpeed === spd
                  ? 'bg-amber-400 text-slate-950'
                  : 'bg-white/10 text-white hover:bg-white/15'
              }`}
            >
              <span>{spd}x {spd === 1 ? '(Chuẩn)' : ''}</span>
              {playbackSpeed === spd && <i className="fa-solid fa-check"></i>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};
