import { useEffect } from 'react';
import { moveSpatialFocus } from '../utils/tvSpatialNav';

interface TVRemoteOptions {
  onBack?: () => void;
  onEnter?: () => void;
  onPlayPause?: () => void;
  onSeekForward?: () => void;
  onSeekBackward?: () => void;
  onVolumeUp?: () => void;
  onVolumeDown?: () => void;
  onToggleFullscreen?: () => void;
  onAnyRemoteKey?: () => void;
  spatialNavigation?: boolean;
  enabled?: boolean;
}

export function useTVRemote(options: TVRemoteOptions = {}) {
  const {
    onBack,
    onEnter,
    onPlayPause,
    onSeekForward,
    onSeekBackward,
    onVolumeUp,
    onVolumeDown,
    onToggleFullscreen,
    onAnyRemoteKey,
    spatialNavigation = false,
    enabled = true
  } = options;

  useEffect(() => {
    if (!enabled) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
        if (e.key === 'Escape' || e.key === 'Backspace') {
          target.blur();
        }
        return;
      }

      if (onAnyRemoteKey) onAnyRemoteKey();

      switch (e.key) {
        case 'Escape':
        case 'Backspace':
        case 'BrowserBack':
          if (onBack) {
            e.preventDefault();
            onBack();
          }
          break;

        case 'Enter':
          if (onEnter) {
            onEnter();
          }
          break;

        case ' ':
        case 'MediaPlayPause':
          if (onPlayPause) {
            e.preventDefault();
            onPlayPause();
          }
          break;

        case 'ArrowRight':
          if (spatialNavigation && (!onSeekForward || (target && target.classList.contains('tv-focus-target') && target.tagName !== 'VIDEO'))) {
            if (moveSpatialFocus('right')) {
              e.preventDefault();
              break;
            }
          }
          if (onSeekForward) {
            e.preventDefault();
            onSeekForward();
          } else if (spatialNavigation) {
            e.preventDefault();
            moveSpatialFocus('right');
          }
          break;

        case 'ArrowLeft':
          if (spatialNavigation && (!onSeekBackward || (target && target.classList.contains('tv-focus-target') && target.tagName !== 'VIDEO'))) {
            if (moveSpatialFocus('left')) {
              e.preventDefault();
              break;
            }
          }
          if (onSeekBackward) {
            e.preventDefault();
            onSeekBackward();
          } else if (spatialNavigation) {
            e.preventDefault();
            moveSpatialFocus('left');
          }
          break;

        case 'ArrowUp':
          if (spatialNavigation && (!onVolumeUp || (target && target.classList.contains('tv-focus-target') && target.tagName !== 'VIDEO'))) {
            if (moveSpatialFocus('up')) {
              e.preventDefault();
              break;
            }
          }
          if (onVolumeUp) {
            e.preventDefault();
            onVolumeUp();
          } else if (spatialNavigation) {
            e.preventDefault();
            moveSpatialFocus('up');
          }
          break;

        case 'ArrowDown':
          if (spatialNavigation && (!onVolumeDown || (target && target.classList.contains('tv-focus-target') && target.tagName !== 'VIDEO'))) {
            if (moveSpatialFocus('down')) {
              e.preventDefault();
              break;
            }
          }
          if (onVolumeDown) {
            e.preventDefault();
            onVolumeDown();
          } else if (spatialNavigation) {
            e.preventDefault();
            moveSpatialFocus('down');
          }
          break;

        case 'f':
        case 'F':
          if (onToggleFullscreen) {
            e.preventDefault();
            onToggleFullscreen();
          }
          break;

        case 'ChannelUp':
        case 'MediaTrackNext':
          if (onSeekForward) {
            e.preventDefault();
            onSeekForward();
          }
          break;

        case 'ChannelDown':
        case 'MediaTrackPrevious':
          if (onSeekBackward) {
            e.preventDefault();
            onSeekBackward();
          }
          break;
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [
    enabled,
    spatialNavigation,
    onBack,
    onEnter,
    onPlayPause,
    onSeekForward,
    onSeekBackward,
    onVolumeUp,
    onVolumeDown,
    onToggleFullscreen,
    onAnyRemoteKey
  ]);
}
