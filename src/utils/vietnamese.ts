import { DonghuaMovie } from '../types';

export function removeVietnameseTones(str: string): string {
  if (!str) return '';
  return str
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'd')
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

const DONGHUA_ALIASES: Record<string, string> = {
  dptk: 'dau pha thuong khung',
  tghm: 'the gioi hoan my',
  pntt: 'pham nhan tu tien',
  tptk: 'thon phe tinh khong',
  dldl: 'dau la dai luc',
  vgdt: 'van gioi doc ton',
  vdck: 'vu dong can khon',
  mtk: 'muc than ky',
  nntt: 'nhat niem vinh hang',
  nnvh: 'nhat niem vinh hang',
  lkmvn: 'luyen khi muoi van nam',
  tcct: 'than chung chi ton',
  btdl: 'bach luyen thanh than',
  bltt: 'bach luyen thanh than',
  hh3d: 'hoat hinh 3d'
};

export function expandVietnameseSearchQueries(rawQuery: string): {
  raw: string;
  clean: string;
  expanded: string;
  apiKeywords: string[];
} {
  const raw = (rawQuery || '').trim();
  const clean = removeVietnameseTones(raw);
  const expanded = DONGHUA_ALIASES[clean] || clean;

  const keywords: string[] = [];
  if (raw) keywords.push(raw);
  if (expanded && expanded !== removeVietnameseTones(raw)) {
    keywords.unshift(expanded);
  } else if (clean && clean !== raw.toLowerCase()) {
    keywords.push(clean);
  }

  return {
    raw,
    clean,
    expanded,
    apiKeywords: Array.from(new Set(keywords.filter(Boolean)))
  };
}

export function getTitleAcronym(title: string): string {
  const clean = removeVietnameseTones(title);
  if (!clean) return '';
  return clean
    .split(' ')
    .filter((w) => w.length > 0)
    .map((w) => w[0])
    .join('');
}

export function matchesMovieQuery(target: string, query: string): boolean {
  if (!query || !query.trim()) return true;
  const cleanTarget = removeVietnameseTones(target);
  const { clean, expanded } = expandVietnameseSearchQueries(query);
  if (!clean) return true;

  if (cleanTarget.includes(clean) || cleanTarget.includes(expanded)) {
    return true;
  }

  const tokens = expanded.split(' ').filter(Boolean);
  if (tokens.length > 1 && tokens.every((t) => cleanTarget.includes(t))) {
    return true;
  }

  const acronym = getTitleAcronym(target);
  if (clean.length >= 2 && acronym.startsWith(clean)) {
    return true;
  }

  return false;
}

export function scoreMovieMatch(movie: DonghuaMovie, rawQuery: string): number {
  const q = (rawQuery || '').trim();
  if (!q) return 1;

  const { clean, expanded } = expandVietnameseSearchQueries(q);
  if (!clean) return 1;

  const title = removeVietnameseTones(movie.title);
  const orig = removeVietnameseTones(movie.originalTitle || '');
  const slug = removeVietnameseTones((movie.slug || '').replace(/-/g, ' '));
  const studio = removeVietnameseTones(movie.studio || '');
  const genres = removeVietnameseTones((movie.genres || []).join(' '));
  const acronym = getTitleAcronym(movie.title);

  const candidates = expanded !== clean ? [expanded, clean] : [clean];
  let best = 0;

  for (const term of candidates) {
    if (title === term) best = Math.max(best, 1000);
    else if (title.startsWith(term)) best = Math.max(best, 850);
    else if (title.includes(term)) best = Math.max(best, 720);

    if (orig === term || slug === term) best = Math.max(best, 780);
    else if (orig.includes(term) || slug.includes(term)) best = Math.max(best, 560);

    if (term.length >= 2) {
      if (acronym === term) best = Math.max(best, 520);
      else if (acronym.startsWith(term)) best = Math.max(best, 440);
    }

    const tokens = term.split(' ').filter(Boolean);
    if (tokens.length > 0) {
      const titleHits = tokens.filter((t) => title.includes(t)).length;
      const allText = `${title} ${orig} ${slug}`;
      const allHits = tokens.filter((t) => allText.includes(t)).length;

      if (titleHits === tokens.length) {
        best = Math.max(best, 420 + titleHits * 20);
      } else if (allHits === tokens.length) {
        best = Math.max(best, 360 + allHits * 15);
      } else if (tokens.length >= 2 && titleHits >= Math.ceil(tokens.length * 0.66)) {
        best = Math.max(best, 180 + Math.round((titleHits / tokens.length) * 100));
      }
    }

    if (studio.includes(term) || genres.includes(term)) {
      best = Math.max(best, 80);
    }
  }

  return best;
}
