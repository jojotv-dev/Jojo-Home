const types = { MOVIE: 'movies', SERIES: 'series', EPISODE: 'series', LIVE: 'live', movie: 'movies', series: 'series', live: 'live', freebox: 'videos' };
export function artworkSource(value) {
  if (typeof value !== 'string') return '';
  const text = value.trim();
  if (/^jojo-home-asset:[a-z0-9_-]{1,64}$/i.test(text)) return '/api/sync/profiles/1/assets/' + text.slice('jojo-home-asset:'.length);
  return /^(?:https?:\/\/|\/api\/)/i.test(text) ? text : '';
}
const parseRecord = value => { try { return typeof value === 'string' ? JSON.parse(value) : value || {}; } catch { return {}; } };
const filePath = entry => entry.contentId?.startsWith('freebox:') || entry.contentType === 'freebox' ? entry.contentId.replace(/^freebox:/, '').split('#fb:')[0].trim() : entry.contentType === 'video' ? entry.contentId.replace(/^file:\/\//, '') : '';
const javaHash = value => { let hash = 0; for (let i = 0; i < value.length; i++) hash = (Math.imul(31, hash) + value.charCodeAt(i)) | 0; return (hash >>> 0).toString(16); };
export function homeResumeEligible(entry, externalItem) {
  const id = String(entry.contentId || ''), type = String(entry.contentType || '').toLowerCase();
  if (type === 'freebox' || id.startsWith('freebox:')) return true;
  if (type === 'video' && /^(?:smb:\/\/|file:|\/)/.test(id)) return true;
  if (id.startsWith('iptv:') && ['movie', 'series'].includes(type)) return type !== 'series' || (entry.season != null && entry.episode != null);
  return id.startsWith('home-external:') && !externalItem?.deleted && /^https?:\/\//i.test(externalItem?.url || entry.videoId || '');
}

// Read Home's existing removal and artwork settings; never change the NAS history.
export function homeResumes(progress, snapshot, profileId, watched = {}) {
  const settings = snapshot?.allSettings || {}, home = {}, removals = new Map();
  for (const [key, revision] of Object.entries(settings)) if (!revision?.deleted) home[key] = revision?.value;
  const put = (key, at) => { if (key && at > 0) removals.set(key, Math.max(removals.get(key) || 0, at)); };
  const prefix = 'home_resume_removed_p' + profileId + '_', restored = 'home_resume_restored_p' + profileId + '_';
  const hidden = Number(profileId) === 1 && Array.isArray(home.jojotv_home_hidden_resume_keys) ? home.jojotv_home_hidden_resume_keys : [];
  for (const [key, value] of Object.entries(home)) {
    if (key.startsWith(prefix)) {
      const identity = key.slice(prefix.length), at = Number(value) || 0;
      if (at >= (Number(home[restored + identity]) || 0)) {
        put(identity, at);
        if (identity.startsWith('com.jojotv.home.file-video|')) put(identity.slice('com.jojotv.home.file-video|'.length).replace(/^file:\/\//, ''), at);
      }
    }
    if (key.startsWith('video_resume_invalidated_')) {
      const path = key.slice('video_resume_invalidated_'.length);
      if (Number(home['video_resume_invalidation_profile_' + path] || 1) === Number(profileId)) put(path.replace(/^file:\/\//, ''), Number(value));
    }
  }
  for (const identity of hidden) {
    if (identity.startsWith('com.jojotv.home.file-video|') && !home[prefix + identity] && !home[restored + identity]) put(identity.slice('com.jojotv.home.file-video|'.length).replace(/^file:\/\//, ''), Infinity);
  }
  const external = new Map();
  for (const [key, revision] of Object.entries(settings)) {
    if (!key.startsWith('external_playback_')) continue;
    const item = parseRecord(revision.value), id = item.id || key.slice('external_playback_'.length);
    if (!/^[a-f0-9]{64}$/.test(id) || Number(item.progressProfileId || 1) !== Number(profileId)) continue;
    external.set('home-external:' + id, item);
    const at = Math.max(Number(revision.changedAt) || 0, Number(item.updatedAt) || 0);
    if (revision.deleted || item.deleted || (item.started === false && item.lastPlayedAt > 0)) put('home-external:' + id, at);
    const masks = ['external-playback|' + id];
    if (item.category === 'series' && item.stalkerSource && (item.stalkerSeriesId || item.stalkerId)) masks.push('external-series-resume|vod-provider|' + item.stalkerSource + '|series|' + (item.stalkerSeriesId || item.stalkerId) + '|0|0');
    const maskedAt = Math.max(...masks.map(mask => removals.get(mask) || 0));
    if (maskedAt || masks.some(mask => hidden.includes(mask))) put('home-external:' + id, maskedAt || Math.max(at, Number(item.lastPlayedAt) || 0));
  }
  for (const marker of progress.tombstones || []) put(marker.key, Number(marker.deletedAt));
  const comparablePath = value => { try { return decodeURIComponent(value).normalize('NFC').replace(/\/+$/, '').toLocaleLowerCase('en'); } catch { return value; } };
  const deletedPaths = new Set(Object.keys(parseRecord(home.freebox_deleted_paths)).map(comparablePath));
  const watchedDeletes = new Map((watched.tombstones || []).map(marker => [marker.key, Number(marker.deletedAt) || 0]));
  const watchedKey = item => JSON.stringify([item.contentType, item.contentId, item.season ?? null, item.episode ?? null]);
  const watchedDates = new Map();
  for (const item of watched.entries || []) if (Number(item.watchedAt) > (watchedDeletes.get(item.key) || 0)) watchedDates.set(watchedKey(item), Math.max(watchedDates.get(watchedKey(item)) || 0, Number(item.watchedAt)));
  for (const entry of progress.entries || []) {
    const path = filePath(entry), identity = entry.contentId.replace(/^home-external:/, '');
    const masks = path ? ['com.jojotv.home.file-video|' + path, 'com.jojotv.home.freebox-folder|' + path] : ['external-playback|' + identity];
    for (const mask of masks) {
      const at = Number(home[prefix + mask]) || 0, restoredAt = Number(home[restored + mask]) || 0;
      if (at >= restoredAt) put(entry.key || entry.contentId, at);
      // The TV keeps its clean hidden set authoritative until an explicit restoration.
      if (hidden.includes(mask) && !(restoredAt > at)) put(entry.key || entry.contentId, Infinity);
    }
    if (path && deletedPaths.has(comparablePath(path))) put(entry.key || entry.contentId, Infinity);
    put(entry.key || entry.contentId, watchedDates.get(watchedKey(entry)) || 0);
  }
  return (progress.entries || []).filter(entry => homeResumeEligible(entry, external.get(entry.contentId)) && Number(entry.lastWatched) > Math.max(removals.get(entry.key) || 0, removals.get(entry.contentId) || 0, removals.get(filePath(entry)) || 0)).map(entry => {
    const path = filePath(entry), externalItem = external.get(entry.contentId);
    const selected = path ? [home['home_videos_file_artwork_' + path], home['file_artwork_' + path], home['resume_artwork_' + javaHash('com.jojotv.home.file-video|' + path)]] : [home['resume_artwork_external_' + entry.contentId.replace(/^home-external:/, '')]];
    return { ...entry, poster: [...selected, externalItem?.artwork, externalItem?.poster, entry.poster, entry.backdrop].map(artworkSource).find(Boolean) || '', backdrop: artworkSource(entry.backdrop) };
  });
}
export function durationMinutes(value) {
  const text = String(value || '').trim();
  if (/^\d+(?:\.\d+)?$/.test(text)) return Number(text);
  const clock = text.match(/^(\d+):(\d{2})(?::(\d{2}))?$/);
  if (clock) return Number(clock[1])*60 + Number(clock[2]) + Number(clock[3] || 0)/60;
  const hours = text.match(/(\d+)\s*h/i), minutes = text.match(/(\d+)\s*(?:min|m\b)/i) || text.match(/h\s*(\d+)/i);
  return (Number(hours?.[1]) || 0)*60 + (Number(minutes?.[1]) || 0);
}
export function favoriteItem(item, account) {
  const native = !account.syncProfile;
  return {
    ...item, id: String(item.id || item.contentId || [item.providerKey, item.contentType, item.remoteContentId].join('|')), title: item.title || item.name || 'Favori IPTV · ' + item.remoteContentId,
    type: types[item.type || item.contentType] || 'videos', artwork: artworkSource(item.artwork) || artworkSource(item.poster) || artworkSource(item.backdrop),
    durationMinutes: durationMinutes(item.duration), favorite: true, nas: true,
    nasFavorite: native, nasType: item.type, accountId: account.id,
    playbackBase: native ? '/api/companion/accounts/' + encodeURIComponent(account.id) + '/favorites/' + encodeURIComponent(item.id) : '',
    description: item.description || item.plot || '', cast: item.cast || '', director: item.director || ''
  };
}
export function resumeItems(entries, favorites, account) {
  const prefix = 'iptv:account:' + account.id + ':';
  const others = 'iptv:account:';
  const result = new Map(favorites.map(item => [item.id, { ...item }]));
  for (const entry of [...entries].sort((a,b) => a.lastWatched-b.lastWatched)) {
    if (entry.contentId.startsWith(others) && !entry.contentId.startsWith(prefix)) continue;
    const id = entry.contentId.startsWith(prefix) ? 'iptv:' + entry.contentId.slice(prefix.length) : entry.contentId;
    const favorite = result.get(id), originalFavorite = favorites.find(item => item.id === id);
    const positionSeconds = (Number(entry.position) || 0)/1000;
    const total = (Number(entry.duration) || 0)/60000;
    const ratio = Number.isFinite(entry.progressPercent) ? entry.progressPercent/100 : total ? positionSeconds/(total*60) : 0;
    if (!positionSeconds || ratio >= (entry.contentType === 'series' ? .93 : .90)) {
      if (originalFavorite) result.set(id, { ...originalFavorite, artwork: originalFavorite.artwork || artworkSource(entry.poster) || artworkSource(entry.backdrop) }); else result.delete(id);
      continue;
    }
    result.set(id, {
      ...(favorite || { id, title: entry.name || 'Sans titre', type: types[entry.contentType] || 'videos', nas: true, accountId: account.id }),
      artwork: favorite?.artwork || artworkSource(entry.poster) || artworkSource(entry.backdrop), description: favorite?.description || entry.description || '',
      positionSeconds, durationMinutes: total || favorite?.durationMinutes || 0,
      episode: entry.season && entry.episode ? 'S'+entry.season+' · Épisode '+entry.episode : '',
      resumeEpisode: { season: entry.season, episode: entry.episode }, lastWatched: entry.lastWatched
    });
  }
  return [...result.values()].sort((a,b) => (b.lastWatched || 0)-(a.lastWatched || 0));
}
export function catalogItem(item, provider, type, favorites) {
  const favorite = provider.origin === 'tv' ? favorites.find(entry => entry.id === item.id) : null;
  if (favorite) return favorite;
  return {
    id: provider.origin + ':' + provider.id + ':' + type + ':' + item.id,
    title: item.title || item.name || 'Sans titre', type, artwork: artworkSource(item.artwork) || artworkSource(item.poster) || artworkSource(item.cover) || artworkSource(item.stream_icon),
    nas: true, provider: provider.name, catalogProvider: provider, catalogId: String(item.id), extension: item.extension || 'mp4'
  };
}
