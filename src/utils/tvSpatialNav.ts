export type Direction = 'up' | 'down' | 'left' | 'right';

function getActiveTvScope(): HTMLElement | Document {
  const activeDrawer = document.querySelector<HTMLElement>('[data-tv-drawer="true"]');
  if (activeDrawer && activeDrawer.offsetParent !== null) return activeDrawer;

  const modals = Array.from(document.querySelectorAll<HTMLElement>('[data-tv-modal="true"]')).filter(
    (el) => el.offsetParent !== null
  );
  if (modals.length > 0) return modals[modals.length - 1];

  return document;
}

function getScopeTargets(scope: HTMLElement | Document = getActiveTvScope()): HTMLElement[] {
  return Array.from(scope.querySelectorAll<HTMLElement>('.tv-focus-target')).filter((el) => {
    if ((el as HTMLButtonElement).disabled) return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && el.offsetParent !== null;
  });
}

export function focusFirstTvTarget(scopeEl?: HTMLElement | null): boolean {
  const scope = scopeEl || getActiveTvScope();
  const candidates = getScopeTargets(scope);
  if (candidates.length === 0) return false;
  const preferred =
    candidates.find((el) => el.dataset.tvAutofocus === 'true') || candidates[0];
  preferred.focus({ preventScroll: false });
  preferred.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  return true;
}

/**
 * Di chuyển tiêu điểm (Focus) theo không gian 2D giữa các phần tử .tv-focus-target
 * giúp Remote D-Pad trên Android TV Box / Smart TV hoạt động chính xác không bị kẹt.
 */
export function moveSpatialFocus(direction: Direction): boolean {
  const scope = getActiveTvScope();
  const candidates = getScopeTargets(scope);

  if (candidates.length === 0) return false;

  const active = document.activeElement as HTMLElement | null;
  if (!active || !candidates.includes(active)) {
    const preferred =
      candidates.find((el) => el.dataset.tvAutofocus === 'true') || candidates[0];
    preferred.focus({ preventScroll: false });
    preferred.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
    return true;
  }

  const activeRect = active.getBoundingClientRect();
  const ax = activeRect.left + activeRect.width / 2;
  const ay = activeRect.top + activeRect.height / 2;

  let bestEl: HTMLElement | null = null;
  let bestScore = Infinity;

  for (const el of candidates) {
    if (el === active) continue;
    const rect = el.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const dx = cx - ax;
    const dy = cy - ay;

    let isValidDir = false;
    let primaryDist = 0;
    let secondaryDist = 0;

    if (direction === 'right' && dx > 8) {
      isValidDir = true;
      primaryDist = dx;
      secondaryDist = Math.abs(dy);
    } else if (direction === 'left' && dx < -8) {
      isValidDir = true;
      primaryDist = -dx;
      secondaryDist = Math.abs(dy);
    } else if (direction === 'down' && dy > 8) {
      isValidDir = true;
      primaryDist = dy;
      secondaryDist = Math.abs(dx);
    } else if (direction === 'up' && dy < -8) {
      isValidDir = true;
      primaryDist = -dy;
      secondaryDist = Math.abs(dx);
    }

    if (!isValidDir) continue;

    // Ưu tiên phần tử cùng hàng/cột trước khi nhảy xa theo trục phụ
    const score = primaryDist + secondaryDist * 2.4;
    if (score < bestScore) {
      bestScore = score;
      bestEl = el;
    }
  }

  if (bestEl) {
    bestEl.focus({ preventScroll: true });
    bestEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
    return true;
  }

  return false;
}
